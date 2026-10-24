"""Immich archival: HTTPS by default, with explicit private-address HTTP opt-in."""
import asyncio
import base64
import contextlib
from dataclasses import dataclass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import time
from urllib.parse import urlsplit
import uuid
import xml.etree.ElementTree as ET
from .photo_metadata import details

from aiohttp import ClientSession, ClientTimeout, FormData


@dataclass
class ArchiveConfig:
    url: str = ""
    key: str = ""
    album_id: str = ""
    album_name: str = "Twofold"
    retry_base: float = 300
    ssl_context: object = None
    allow_local_http: bool = False

    @classmethod
    def environment(cls):
        key_file = os.environ.get("TWOFOLD_IMMICH_API_KEY_FILE", "")
        key = Path(key_file).read_text().strip() if key_file and Path(key_file).is_file() else ""
        return cls(url=os.environ.get("TWOFOLD_IMMICH_URL", "").rstrip("/"), key=key,
            album_id=os.environ.get("TWOFOLD_IMMICH_ALBUM_ID", ""),
            album_name=os.environ.get("TWOFOLD_IMMICH_ALBUM_NAME", "Twofold"),
            allow_local_http=os.environ.get("TWOFOLD_IMMICH_ALLOW_LOCAL_HTTP", "").lower() == "true")


class ArchiveError(Exception):
    pass


NS = {'x': 'adobe:ns:meta/', 'rdf': 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
      'dc': 'http://purl.org/dc/elements/1.1/', 'exif': 'http://ns.adobe.com/exif/1.0/',
      'xmp': 'http://ns.adobe.com/xap/1.0/', 'tf': 'urn:twofold:metadata:1',
      'digiKam': 'http://www.digikam.org/ns/1.0/'}
for prefix, namespace in NS.items():
    ET.register_namespace(prefix, namespace)


def tag(prefix, name):
    return '{' + NS[prefix] + '}' + name


def xml_text(value):
    return ''.join(c for c in str(value) if c in '\t\n\r' or 0x20 <= ord(c) <= 0xD7FF or 0xE000 <= ord(c) <= 0xFFFD or 0x10000 <= ord(c) <= 0x10FFFF)


def xmp(metadata, index=1, summary=None):
    images = metadata.get('images', [])
    image = images[index - 1] if len(images) >= index else {'part': f'photo{index}'}
    summary = summary if summary is not None else image.get('metadata', {})
    root = ET.Element(tag('x', 'xmpmeta'))
    rdf = ET.SubElement(root, tag('rdf', 'RDF'))
    description = ET.SubElement(rdf, tag('rdf', 'Description'), {tag('rdf', 'about'): '',
        tag('xmp', 'CreatorTool'): 'Twofold', tag('tf', 'MomentId'): metadata['id'],
        tag('tf', 'UserSlot'): str(metadata['slot']), tag('tf', 'Author'): xml_text(metadata['author_name']),
        tag('tf', 'PhotoIndex'): str(index), tag('tf', 'MomentCreatedAt'): metadata['captured_at']})
    # Immich prioritizes sidecar dates. Preserve original EXIF/XMP capture time when present.
    if summary.get('capture_time_source', 'moment') == 'moment':
        description.set(tag('exif', 'DateTimeOriginal'), metadata['captured_at'])
    creator = ET.SubElement(description, tag('dc', 'creator'))
    sequence = ET.SubElement(creator, tag('rdf', 'Seq'))
    ET.SubElement(sequence, tag('rdf', 'li')).text = xml_text(metadata['author_name'])
    caption = f"Twofold user: {metadata['author_name']}"
    if metadata.get('caption'):
        caption += "\n" + metadata['caption']
    camera = summary.get('twofold_capture', {})
    if camera.get('camera_facing') in ('front', 'rear'):
        facing = camera['camera_facing']
        caption += f"\nCamera: {facing.title()} · {camera.get('capture_mode', 'single')} capture"
        tags = ET.SubElement(ET.SubElement(description, tag('digiKam', 'TagsList')), tag('rdf', 'Seq'))
        for keyword in ('Twofold', 'Twofold/' + facing.title(), 'Twofold/By ' + metadata['author_name']):
            ET.SubElement(tags, tag('rdf', 'li')).text = xml_text(keyword)
    caption += f"\nMoment: {metadata['id']} · Photo {index}"
    alternatives = ET.SubElement(ET.SubElement(description, tag('dc', 'description')), tag('rdf', 'Alt'))
    ET.SubElement(alternatives, tag('rdf', 'li'), {'{http://www.w3.org/XML/1998/namespace}lang': 'x-default'}).text = xml_text(caption)
    context = {'moment': metadata, 'image_index': index, 'original_image_metadata': summary}
    ET.SubElement(description, tag('tf', 'Metadata')).text = json.dumps(context, ensure_ascii=True, sort_keys=True)
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


class Archiver:
    def __init__(self, app, config):
        self.app, self.store, self.config = app, app["store"], config
        self.session = None

    def url(self, path):
        url = self.config.url.rstrip("/")
        parsed = urlsplit(url)
        if not parsed.hostname or parsed.username is not None or parsed.query or parsed.fragment:
            raise ArchiveError("Configure a valid HTTPS Immich URL on the server.")
        permitted_http = False
        if parsed.scheme == "http" and self.config.allow_local_http:
            try:
                address = ipaddress.ip_address(parsed.hostname)
                permitted_http = any(address in ipaddress.ip_network(network) for network in (
                    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "::1/128", "fc00::/7"))
            except ValueError:
                pass
        if parsed.scheme != "https" and not permitted_http:
            raise ArchiveError("Immich requires HTTPS, or explicit TWOFOLD_IMMICH_ALLOW_LOCAL_HTTP=true for a private numeric address.")
        return url + ("" if url.endswith("/api") else "/api") + path

    async def request(self, method, path, **kwargs):
        async with self.session.request(method, self.url(path), headers={"x-api-key": self.config.key},
            allow_redirects=False, ssl=self.config.ssl_context, **kwargs) as response:
            payload = await response.content.read(1024 * 1024 + 1)
            if response.status not in (200, 201, 204):
                raise ArchiveError(f"Immich returned HTTP {response.status}; originals retained for retry.")
            if len(payload) > 1024 * 1024:
                raise ArchiveError("Immich response exceeded the expected size.")
            return json.loads(payload) if payload else None

    async def album_id(self):
        if self.config.album_id:
            return str(uuid.UUID(self.config.album_id))
        with self.store.connect() as conn:
            row = conn.execute("SELECT value FROM settings WHERE key='immich_album_id'").fetchone()
        if row:
            return str(uuid.UUID(row[0]))
        result = await self.request("POST", "/albums", json={"albumName": self.config.album_name})
        ident = str(uuid.UUID(result["id"]))
        with self.store.connect() as conn:
            conn.execute("INSERT OR REPLACE INTO settings VALUES ('immich_album_id',?)", (ident,))
        return ident

    async def upload_asset(self, row, index, metadata):
        path = self.store.root / row["archive_rel"] / f"photo{index}.jpg"
        summary = details(path)
        sidecar = xmp(metadata, index, summary)
        sidecar_path = path.with_suffix('.jpg.xmp')
        temporary = sidecar_path.with_suffix('.xmp.tmp')
        temporary.write_bytes(sidecar)
        temporary.replace(sidecar_path)
        filename = f"twofold-{row['id']}-{index}.jpg"
        for legacy in (False, True):
            with path.open("rb") as original:
                checksum = base64.b64encode(hashlib.file_digest(original, "sha1").digest()).decode()
                original.seek(0)
                fields = FormData()
                fields.add_field("fileCreatedAt", metadata["captured_at"])
                fields.add_field("fileModifiedAt", metadata["captured_at"])
                if legacy:
                    fields.add_field("deviceId", f"twofold-{row['slot']}")
                    fields.add_field("deviceAssetId", f"{row['id']}-{index}")
                fields.add_field("assetData", original, filename=filename, content_type="image/jpeg")
                fields.add_field("sidecarData", sidecar, filename=filename+".xmp", content_type="application/xml")
                async with self.session.post(self.url("/assets"), data=fields, allow_redirects=False,
                    headers={"x-api-key": self.config.key, "x-immich-checksum": checksum}, ssl=self.config.ssl_context) as response:
                    body = await response.content.read(1024 * 1024 + 1)
                    if response.status == 400 and not legacy:
                        continue
                    if response.status not in (200, 201) or len(body) > 1024 * 1024:
                        raise ArchiveError(f"Immich upload returned HTTP {response.status}; originals retained for retry.")
                    return str(uuid.UUID(json.loads(body)["id"]))
        raise ArchiveError("Immich upload could not be confirmed.")

    async def archive(self, row):
        if not self.config.key:
            raise ArchiveError("Configure the Immich API key on the server; originals are retained.")
        metadata = json.loads(row["metadata"])
        ids = json.loads(row["archive_assets"])
        for index in range(len(ids) + 1, row["photo_count"] + 1):
            ids.append(await self.upload_asset(row, index, metadata))
            with self.store.connect() as conn:
                conn.execute("UPDATE posts SET archive_assets=? WHERE id=?", (json.dumps(ids), row["id"]))
        album_id = await self.album_id()
        results = await self.request("PUT", f"/albums/{album_id}/assets", json={"ids": ids})
        for ident in ids:
            if not any(result.get("id") == ident and (result.get("success") is True or result.get("error") == "duplicate") for result in results):
                raise ArchiveError("Immich did not confirm album membership; originals retained for retry.")
        with self.store.connect() as conn:
            conn.execute("UPDATE posts SET archived=1,archive_error='' WHERE id=?", (row["id"],))
        from .app import prune
        prune(self.store, row["slot"])
        for queue in list(self.app["listeners"].values()):
            if not queue.full():
                queue.put_nowait(row["seq"])

    async def run(self):
        async with ClientSession(timeout=ClientTimeout(total=120, connect=20), trust_env=False) as session:
            self.session = session
            while True:
                self.app["archive_wake"].clear()
                with self.store.connect() as conn:
                    rows = [dict(row) for row in conn.execute("SELECT * FROM posts WHERE archived=0 AND archive_retry_at<=? ORDER BY seq", (time.time(),))]
                for row in rows:
                    try:
                        await self.archive(row)
                    except asyncio.CancelledError:
                        raise
                    except Exception as exc:
                        message = str(exc) if isinstance(exc, ArchiveError) else "Immich is unavailable; originals retained for retry."
                        delay = min(21600, self.config.retry_base * 2 ** min(row["archive_attempts"], 7))
                        with self.store.connect() as conn:
                            conn.execute("UPDATE posts SET archive_attempts=archive_attempts+1,archive_retry_at=?,archive_error=? WHERE id=?",
                                         (time.time() + delay, message, row["id"]))
                        break
                try:
                    await asyncio.wait_for(self.app["archive_wake"].wait(), timeout=max(0.05, min(30, self.config.retry_base)))
                except asyncio.TimeoutError:
                    pass


async def lifecycle(app):
    config = app["archive_config"] or ArchiveConfig.environment()
    task = asyncio.create_task(Archiver(app, config).run())
    try:
        yield
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
