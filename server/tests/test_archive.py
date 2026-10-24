"""Exercise the real archival worker against a TLS Immich protocol fixture."""
import asyncio
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
from pathlib import Path
import ssl
import tempfile
import unittest
import uuid
import xml.etree.ElementTree as ET

from aiohttp import web, FormData
from aiohttp.test_utils import TestClient, TestServer
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from PIL import Image
from PIL.TiffImagePlugin import IFDRational
from twofold.app import create_app
from twofold.archive import ArchiveConfig, Archiver, ArchiveError


class ArchiveUrlTests(unittest.TestCase):
    def test_private_http_requires_explicit_opt_in(self):
        config = ArchiveConfig(url="http://192.168.1.10:2283/")
        worker = Archiver({"store": None}, config)
        with self.assertRaises(ArchiveError):
            worker.url("/assets")
        config.allow_local_http = True
        self.assertEqual(worker.url("/assets"), "http://192.168.1.10:2283/api/assets")

    def test_opt_in_does_not_allow_public_http_dns_or_embedded_credentials(self):
        for url in ("http://1.1.1.1", "http://example.com", "http://immich:2283", "http://169.254.169.254", "http://0.0.0.0",
                    "http://:password@192.168.1.10", "https://:password@example.com", "http://192.168.1.10?key=secret"):
            with self.subTest(url=url), self.assertRaises(ArchiveError):
                Archiver({"store": None}, ArchiveConfig(url=url, allow_local_http=True)).url("/assets")


class ArchiveTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=1)).not_valid_after(now+timedelta(days=1))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), critical=False).sign(key, hashes.SHA256()))
        (root/"cert.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        (root/"key.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        server_ssl = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server_ssl.load_cert_chain(root/"cert.pem", root/"key.pem")
        client_ssl = ssl.create_default_context(cafile=str(root/"cert.pem"))
        self.assets, self.memberships, self.sidecars, self.requests = {}, set(), [], []
        self.uploaded_bytes = []
        self.fail_album = False
        self.legacy = False

        async def upload(request):
            self.assertEqual(request.headers.get("x-api-key"), "fixture-only")
            reader = await request.multipart()
            parts, filenames = {}, {}
            while (part := await reader.next()) is not None:
                filenames[part.name] = part.filename
                parts[part.name] = bytes(await part.read())
            self.assertEqual(filenames['sidecarData'], filenames['assetData'] + '.xmp')
            self.requests.append("deviceId" in parts)
            if self.legacy and "deviceId" not in parts:
                return web.json_response({}, status=400)
            photo = parts["assetData"]
            self.uploaded_bytes.append(photo)
            with Image.open(io.BytesIO(photo)) as image:
                self.assertEqual(image.format, "JPEG")
            self.sidecars.append(ET.fromstring(parts["sidecarData"]))
            checksum = hashlib.sha256(photo).hexdigest()
            duplicate = checksum in self.assets
            self.assets.setdefault(checksum, str(uuid.uuid4()))
            return web.json_response({"id": self.assets[checksum], "status": "duplicate" if duplicate else "created"}, status=200 if duplicate else 201)

        async def add(request):
            self.assertEqual(request.headers.get("x-api-key"), "fixture-only")
            ids = (await request.json())["ids"]
            rows = []
            for ident in ids:
                duplicate = ident in self.memberships
                if not self.fail_album:
                    self.memberships.add(ident)
                rows.append({"id": ident, "success": not (self.fail_album or duplicate),
                    "error": "no_permission" if self.fail_album else "duplicate" if duplicate else None})
            return web.json_response(rows)

        fixture = web.Application()
        fixture.add_routes([web.post("/api/assets", upload), web.put("/api/albums/{id}/assets", add)])
        self.fixture = TestServer(fixture, host="localhost")
        await self.fixture.start_server(ssl=server_ssl)
        config = ArchiveConfig(url=f"https://localhost:{self.fixture.port}", key="fixture-only",
            album_id="22222222-2222-4222-8222-222222222222", retry_base=0.1, ssl_context=client_ssl)
        self.app = create_app(root/"data", archive_config=config)
        self.store = self.app["store"]
        self.token = self.store.enroll(self.store.invite(1, "Person 1"))["token"]
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.fixture.close()
        self.temp.cleanup()

    async def post(self, second=False, originals=None):
        ident = str(uuid.uuid4())
        form = FormData()
        form.add_field("record", json.dumps({"id": ident, "version": 1}))
        form.add_field("metadata", json.dumps({"id": ident, "version": 1, "caption": "a & b <3", "captured_at": "2026-10-23T12:34:56Z"}))
        for index, name in enumerate(["preview", "photo1"] + (["photo2"] if second else [])):
            buffer = io.BytesIO()
            Image.new("RGB", (24,32), (40+index*40, 100, int(ident[:2],16))).save(buffer, "JPEG")
            payload = originals[int(name[-1])-1] if originals and name.startswith('photo') else buffer.getvalue()
            form.add_field(name, payload, filename=name+".jpg", content_type="image/jpeg")
        response = await self.client.post("/v1/posts", data=form, headers={"Authorization": "Bearer "+self.token})
        self.assertEqual(response.status, 201, await response.text())
        return ident

    async def wait_archived(self, ident):
        for _ in range(100):
            row = self.store.get_post(ident)
            if row and row["archived"]:
                return
            await asyncio.sleep(0.025)
        self.fail("Archive was not confirmed: " + str(self.store.get_post(ident)))

    async def test_upload_album_metadata_and_latest_only_retention(self):
        first = await self.post(second=True)
        await self.wait_archived(first)
        self.assertEqual(len(self.assets), 2)
        self.assertEqual(len(self.memberships), 2)
        self.assertIn("a & b <3", "".join(self.sidecars[0].itertext()))
        row = self.store.get_post(first)
        root = self.store.root/row["archive_rel"]
        self.assertTrue((root/"photo1.jpg").read_bytes().startswith(b"\xff\xd8"))
        self.assertEqual(json.loads((root/"metadata.json").read_text())["caption"], "a & b <3")
        second = await self.post()
        await self.wait_archived(second)
        self.assertIsNone(self.store.get_post(first))
        self.assertFalse(root.exists())

    async def test_album_failure_retains_files_and_retry_reuses_uploaded_asset(self):
        self.fail_album = True
        ident = await self.post()
        for _ in range(100):
            row = self.store.get_post(ident)
            if row["archive_error"]:
                break
            await asyncio.sleep(0.025)
        self.assertFalse(row["archived"])
        self.assertTrue((self.store.root/row["archive_rel"]/"photo1.jpg").exists())
        self.assertEqual(len(self.assets), 1)
        self.fail_album = False
        await self.wait_archived(ident)
        self.assertEqual(len(self.assets), 1)
        self.assertEqual(len(self.requests), 1)

    async def test_legacy_upload_and_existing_album_membership(self):
        self.legacy = True
        ident = await self.post()
        await self.wait_archived(ident)
        self.assertEqual(self.requests, [False, True])

    async def test_full_exif_gps_bytes_and_author_sidecar(self):
        source = Image.new('RGB', (24, 32), (20, 80, 140))
        exif = Image.Exif()
        exif[271], exif[272] = 'Camera & Co', 'Fixture Camera'
        exif[34665] = {36867: '2020:07:04 12:34:56', 36881: '-04:00',
            33434: IFDRational(1, 125), 34855: 400, 42036: 'Fixture Lens',
            37500: b'original-maker-note', 37510: b'ASCII\x00\x00\x00original comment'}
        exif[34853] = {1: 'N', 2: (IFDRational(45), IFDRational(30), IFDRational(0)),
            3: 'W', 4: (IFDRational(87), IFDRational(15), IFDRational(0)), 5: 0, 6: IFDRational(200)}
        buffer = io.BytesIO()
        source.save(buffer, 'JPEG', exif=exif)
        original = buffer.getvalue()
        ident = await self.post(originals=[original])
        await self.wait_archived(ident)
        self.assertEqual(self.uploaded_bytes, [original])
        row = self.store.get_post(ident)
        meta = json.loads(row['metadata'])
        photo = meta['images'][0]['metadata']
        self.assertEqual(photo['exif']['model'], 'Fixture Camera')
        self.assertEqual(photo['exif']['exposure_time'], '1/125')
        self.assertEqual(photo['exif']['date_time_original'], '2020:07:04 12:34:56')
        self.assertEqual(photo['exif']['offset_time_original'], '-04:00')
        self.assertEqual(photo['location']['latitude'], 45.5)
        self.assertEqual(photo['location']['longitude'], -87.25)
        root = self.sidecars[0]
        ns = {'rdf': 'http://www.w3.org/1999/02/22-rdf-syntax-ns#', 'dc': 'http://purl.org/dc/elements/1.1/', 'tf': 'urn:twofold:metadata:1'}
        self.assertEqual(root.find('.//dc:creator/rdf:Seq/rdf:li', ns).text, 'Person 1')
        self.assertIn('Twofold user: Person 1', root.find('.//dc:description/rdf:Alt/rdf:li', ns).text)
        description = root.find('.//rdf:Description', ns)
        self.assertNotIn('{http://ns.adobe.com/exif/1.0/}DateTimeOriginal', description.attrib)
        context = json.loads(root.find('.//tf:Metadata', ns).text)
        self.assertEqual(context['original_image_metadata']['location'], photo['location'])
        stored = self.store.root/row['archive_rel']
        self.assertEqual((stored/'photo1.jpg').read_bytes(), original)
        self.assertTrue((stored/'photo1.jpg.xmp').is_file())
        with Image.open(io.BytesIO(self.uploaded_bytes[0])) as check:
            self.assertEqual(check.getexif().get_ifd(34665)[37500], b'original-maker-note')

    async def test_fallback_timestamp_and_context_for_image_without_exif(self):
        ident = await self.post()
        await self.wait_archived(ident)
        ns = {'rdf': 'http://www.w3.org/1999/02/22-rdf-syntax-ns#', 'tf': 'urn:twofold:metadata:1'}
        description = self.sidecars[0].find('.//rdf:Description', ns)
        self.assertEqual(description.get('{http://ns.adobe.com/exif/1.0/}DateTimeOriginal'), '2026-10-23T12:34:56Z')
        context = json.loads(self.sidecars[0].find('.//tf:Metadata', ns).text)
        self.assertEqual(context['moment']['slot'], 1)
        self.assertEqual(context['moment']['caption'], 'a & b <3')

    async def test_embedded_xmp_date_is_not_overridden(self):
        buffer = io.BytesIO()
        Image.new('RGB', (24,32)).save(buffer, 'JPEG', xmp=b'<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreateDate="2020-07-04T12:34:56-04:00"/>')
        ident = await self.post(originals=[buffer.getvalue()])
        await self.wait_archived(ident)
        description = self.sidecars[0].find('.//{http://www.w3.org/1999/02/22-rdf-syntax-ns#}Description')
        self.assertNotIn('{http://ns.adobe.com/exif/1.0/}DateTimeOriginal', description.attrib)

    async def test_native_camera_pair_context_and_searchable_tags(self):
        originals = []
        group = str(uuid.uuid4())
        for facing in ('rear', 'front'):
            context = {'app': 'Twofold', 'capture_group': group, 'author': 'client supplied',
                'camera_id': '0' if facing == 'rear' else '1', 'camera_facing': facing,
                'capture_mode': 'concurrent', 'captured_at': '2026-10-23T12:34:55.123Z',
                'time_source': 'sensor_realtime', 'sensor_timestamp_ns': 123456789}
            exif = Image.Exif()
            exif[315] = 'client supplied'
            exif[34665] = {36867: '2026:10:23 08:34:55', 36881: '-04:00',
                37510: b'ASCII\x00\x00\x00' + json.dumps(context).encode()}
            buffer = io.BytesIO()
            Image.new('RGB', (24, 32)).save(buffer, 'JPEG', exif=exif)
            originals.append(buffer.getvalue())
        ident = await self.post(second=True, originals=originals)
        await self.wait_archived(ident)
        self.assertEqual(self.uploaded_bytes, originals)
        metadata = json.loads(self.store.get_post(ident)['metadata'])
        ns = {'digiKam': 'http://www.digikam.org/ns/1.0/', 'rdf': 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
            'dc': 'http://purl.org/dc/elements/1.1/'}
        for index, facing in enumerate(('rear', 'front')):
            capture = metadata['images'][index]['metadata']['twofold_capture']
            self.assertEqual(capture['capture_group'], group)
            self.assertEqual(capture['camera_facing'], facing)
            tags = [item.text for item in self.sidecars[index].findall('.//digiKam:TagsList/rdf:Seq/rdf:li', ns)]
            self.assertIn('Twofold/' + facing.title(), tags)
            self.assertIn('Twofold/By Person 1', tags)
            self.assertNotIn('Twofold/By client supplied', tags)
            self.assertIn('Camera: ' + facing.title(), self.sidecars[index].find('.//dc:description/rdf:Alt/rdf:li', ns).text)

    async def test_actual_private_http_archive_after_explicit_opt_in(self):
        site = web.TCPSite(self.fixture.runner, "127.0.0.1", 0)
        await site.start()
        try:
            config = self.app["archive_config"]
            config.url = f"http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}"
            ident = await self.post()
            for _ in range(100):
                row = self.store.get_post(ident)
                if row["archive_error"]:
                    break
                await asyncio.sleep(0.025)
            self.assertIn("requires HTTPS", row["archive_error"])
            self.assertFalse(row["archived"])
            self.assertEqual(len(self.assets), 0)
            config.allow_local_http = True
            with self.store.connect() as connection:
                connection.execute("UPDATE posts SET archive_retry_at=0 WHERE id=?", (ident,))
            self.app["archive_wake"].set()
            await self.wait_archived(ident)
            self.assertEqual(len(self.assets), 1)
            self.assertEqual(len(self.memberships), 1)
        finally:
            await site.stop()


if __name__ == "__main__":
    unittest.main()
