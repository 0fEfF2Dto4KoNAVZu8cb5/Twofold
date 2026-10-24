"""Authenticated JPEG/JSON storage + SSE. HTTPS terminates at your reverse proxy."""
import asyncio
import collections
import fcntl
import json
import logging
import os
import re
import shutil
import tempfile
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from aiohttp import web
from .storage import Store, digest
from PIL import Image, ImageFilter
from . import rounds
from . import push

MAX_UPLOAD = 36 * 1024 * 1024
MAX_PART = 16 * 1024 * 1024
LOG = logging.getLogger("twofold")


@web.middleware
async def errors(request, handler):
    try:
        response = await handler(request)
    except web.HTTPException as exc:
        response = web.json_response({"error": exc.reason}, status=exc.status)
    except (ValueError, json.JSONDecodeError, UnicodeDecodeError):
        response = web.json_response({"error": "Invalid request."}, status=400)
    except asyncio.CancelledError:
        raise
    except Exception:
        LOG.exception("Request failed (credentials and content are not logged)")
        response = web.json_response({"error": "Server error. Retry later."}, status=500)
    if not response.prepared:
        response.headers.update({"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
                                 "Content-Security-Policy": "default-src 'none'"})
    return response


@web.middleware
async def authentication(request, handler):
    if request.path not in ("/healthz", "/v1/enroll"):
        auth = request.headers.get("Authorization", "")
        token = auth[7:] if auth.startswith("Bearer ") else ""
        user = request.app["store"].authenticate(token)
        if not user:
            raise web.HTTPUnauthorized(reason="Pair this phone again with a fresh invitation.")
        request["user"] = user
        request["token"] = token
    return await handler(request)


async def health(request):
    return web.json_response({"status": "ok"})


async def enroll(request):
    # Deliberately ignore forwarded addresses; forged headers cannot bypass this limit.
    ip = request.remote or "unknown"
    now = time.monotonic()
    attempts = request.app["attempts"]
    if len(attempts) >= 1024:
        for key in list(attempts):
            if not attempts[key] or attempts[key][-1] < now - 900:
                del attempts[key]
        if len(attempts) >= 1024 and ip not in attempts:
            raise web.HTTPTooManyRequests(reason="Try pairing again later.")
    queue = attempts.setdefault(ip, collections.deque())
    while queue and queue[0] < now - 900:
        queue.popleft()
    if len(queue) >= 20:
        raise web.HTTPTooManyRequests(reason="Too many pairing attempts. Wait 15 minutes.")
    queue.append(now)
    if request.content_type != "application/json":
        raise web.HTTPUnsupportedMediaType()
    if request.content_length is None or request.content_length > 4096:
        raise web.HTTPRequestEntityTooLarge(max_size=4096, actual_size=request.content_length or 4097)
    body = await request.json()
    if not isinstance(body, dict):
        raise ValueError()
    invite = body.get("invite", "")
    token = body.get("device_token", "")
    if not isinstance(invite, str) or len(invite) > 128:
        raise ValueError()
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
        raise ValueError()
    try:
        result = request.app["store"].enroll(invite, token)
    except ValueError:
        raise web.HTTPUnauthorized(reason="Invitation is invalid, expired, or already used.")
    return web.json_response(result)


async def me(request):
    store = request.app["store"]
    with store.connect() as conn:
        partner = conn.execute("SELECT slot,name FROM users WHERE slot!=?", (request["user"]["slot"],)).fetchone()
    return web.json_response({**request["user"], "partner": dict(partner) if partner else None})


async def disconnect(request):
    with request.app["store"].connect() as conn:
        conn.execute("UPDATE devices SET active=0 WHERE hash=?", (digest(request["token"]),))
        conn.execute('DELETE FROM push_subscriptions WHERE device_hash=?', (digest(request['token']),))
    return web.json_response({"disconnected": True})


async def state(request):
    store = request.app["store"]
    slot = request["user"]["slot"]
    with store.connect() as conn:
        own = conn.execute("SELECT * FROM posts WHERE slot=? ORDER BY seq DESC LIMIT 1", (slot,)).fetchone()
        partner = conn.execute("SELECT * FROM posts WHERE slot!=? ORDER BY seq DESC LIMIT 1", (slot,)).fetchone()
        seq = conn.execute("SELECT COALESCE(MAX(seq),0) FROM posts").fetchone()[0]
        pending = conn.execute("SELECT COUNT(*) FROM posts WHERE archived=0").fetchone()[0]
        error = conn.execute("SELECT archive_error FROM posts WHERE archived=0 AND archive_error!='' ORDER BY seq LIMIT 1").fetchone()
    return web.json_response({"protocol_version": 2, "seq": seq, "own": rounds.visible(store, own, slot),
        "partner": rounds.visible(store, partner, slot), "round": rounds.current(store, slot),
        "preferences": rounds.preferences(store), "archive_pending": pending,
        "push": push.status(request.app, request['token']),
        "archive_status": error[0] if error else ("Archive pending on server" if pending else "Archived to Immich")})


async def history(request):
    try:
        before = int(request.query.get("before", "9223372036854775807"))
        limit = min(50, max(1, int(request.query.get("limit", "20"))))
    except ValueError:
        raise web.HTTPBadRequest()
    store = request.app["store"]
    with store.connect() as conn:
        rows = conn.execute("SELECT * FROM posts WHERE seq<? ORDER BY seq DESC LIMIT ?", (before, limit)).fetchall()
    return web.json_response({"items": [rounds.visible(store, row, request['user']['slot']) for row in rows],
                              "next_before": rows[-1]["seq"] if len(rows) == limit else None})


async def preferences(request):
    if request.content_type != 'application/json' or request.content_length is None or request.content_length > 4096:
        raise web.HTTPBadRequest(reason='Send a small JSON preferences object.')
    body = await request.json()
    if not isinstance(body, dict) or set(body) != {'share_to_reveal'} or type(body['share_to_reveal']) is not bool:
        raise web.HTTPBadRequest(reason='share_to_reveal must be true or false.')
    with request.app['store'].connect() as conn:
        conn.execute("INSERT INTO settings(key,value) VALUES ('share_to_reveal',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                     ('true' if body['share_to_reveal'] else 'false',))
    for queue in list(request.app['listeners'].values()):
        if not queue.full():
            queue.put_nowait(0)
    push.changed(request.app)
    return web.json_response(rounds.preferences(request.app['store']))


async def upload(request):
    if request.content_type != "multipart/form-data":
        raise web.HTTPUnsupportedMediaType()
    store = request.app["store"]
    slot = request["user"]["slot"]
    # Two phones need very little upload concurrency. Bound memory/disk pressure.
    async with request.app["upload_slots"]:
        root = tempfile.mkdtemp(prefix="upload-", dir=store.root / "tmp")
        try:
            parts = await asyncio.wait_for(receive_upload(request, root), timeout=180)
            data = json.loads(parts["record"].read_text("utf-8"))
            if not isinstance(data, dict) or not isinstance(data.get("id"), str):
                raise ValueError()
            post_id = str(uuid.UUID(data["id"]))
            if data["id"] != post_id or data.get("version") != 1:
                raise ValueError()
            metadata = json.loads(parts["metadata"].read_text("utf-8"))
            if not isinstance(metadata, dict) or metadata.get("id") != post_id or metadata.get("version") != 1:
                raise ValueError()
            caption = metadata.get("caption", "")
            if not isinstance(caption, str) or len(caption) > 500:
                raise ValueError()
            captured_at = metadata.get("captured_at", "")
            if not isinstance(captured_at, str):
                raise ValueError()
            date = datetime.fromisoformat(captured_at.replace("Z", "+00:00"))
            if date.tzinfo is None:
                raise ValueError()
            count = 2 if "photo2" in parts else 1
            layout = metadata.get('layout', {'main_photo': 1, 'inset_corner': 'top_left'})
            if (not isinstance(layout, dict) or type(layout.get('main_photo')) is not int
                    or layout['main_photo'] not in range(1, count + 1)
                    or layout.get('inset_corner') not in ('top_left', 'top_right', 'bottom_left', 'bottom_right')):
                raise web.HTTPBadRequest(reason='Choose a valid main photo and inset corner.')
            metadata['layout'] = {'main_photo': layout['main_photo'], 'inset_corner': layout['inset_corner']}
            if set(parts) != {"record", "metadata", "preview", "photo1"} | ({"photo2"} if count == 2 else set()):
                raise ValueError()
            for name in ("preview", "photo1") + (("photo2",) if count == 2 else ()):
                try:
                    with Image.open(parts[name]) as image:
                        if image.format != "JPEG" or image.width * image.height > 60000000:
                            raise ValueError()
                        if name == 'preview' and image.width * image.height > 2_000_000:
                            raise web.HTTPBadRequest(reason='Preview must be at most 2 megapixels.')
                        image.verify()
                except OSError:
                    raise web.HTTPBadRequest(reason="Use valid JPEG photos.")
            from .photo_metadata import details
            metadata.update({"author_name": request["user"]["name"], "slot": slot,
                "images": [{"part": f"photo{i}", "filename": f"photo{i}.jpg", "metadata": details(parts[f"photo{i}"])} for i in range(1, count + 1)]})
            current = store.get_post(post_id)
            if current:
                if current["slot"] != slot:
                    raise web.HTTPConflict(reason="Photo identifier already exists.")
                return web.json_response(store.public(current))
            with store.connect() as conn:
                receipt = conn.execute("SELECT * FROM receipts WHERE id=?", (post_id,)).fetchone()
            if receipt:
                if receipt["slot"] != slot:
                    raise web.HTTPConflict()
                return web.json_response({**dict(receipt), "retired": True})
            received = datetime.now(timezone.utc)
            rel = f"archive/{received:%Y/%m/%d}/{slot}/{post_id}"
            destination = store.root / rel
            record = {"version": 1, "id": post_id, "slot": slot, "received_at": received.isoformat(),
                      "photo_count": count, "format": "JPEG originals with JSON metadata; TLS transport"}
            (parts["record"]).write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
            parts["metadata"].write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            for name in list(parts):
                parts[name].rename(parts[name].with_name(name + (".jpg" if name.startswith("photo") or name == "preview" else ".json")))
            # A tiny, heavily blurred, metadata-free preview can be shown while locked.
            with Image.open(Path(root) / 'preview.jpg') as image:
                image.thumbnail((20, 20))
                image.convert('RGB').filter(ImageFilter.GaussianBlur(5)).resize((360, 480)).save(Path(root) / 'preview-locked.jpg', 'JPEG', quality=65)
            # Acknowledgment transfers responsibility for the originals to this server.
            # Flush their bytes before committing the database receipt.
            for path in Path(root).iterdir():
                with path.open("rb") as saved:
                    os.fsync(saved.fileno())
            destination.parent.mkdir(parents=True, exist_ok=True)
            with store.connect() as conn:
                conn.execute("BEGIN IMMEDIATE")
                current = conn.execute("SELECT * FROM posts WHERE id=?", (post_id,)).fetchone()
                if current:
                    if current["slot"] != slot:
                        raise web.HTTPConflict(reason="Photo identifier already exists.")
                    return web.json_response(store.public(current))
                if destination.exists():
                    # A previous process stopped between rename and commit. No committed post owns it.
                    shutil.rmtree(destination)
                round_id = rounds.join(conn, slot, post_id, record['received_at'])
                metadata['round_id'] = round_id
                record['round_id'] = round_id
                for name, value in (('metadata.json', metadata), ('record.json', record)):
                    with (Path(root) / name).open('w', encoding='utf-8') as saved:
                        json.dump(value, saved, ensure_ascii=False, indent=2)
                        saved.flush()
                        os.fsync(saved.fileno())
                os.replace(root, destination)
                root = None
                directory = destination
                while True:
                    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
                    try:
                        os.fsync(descriptor)
                    finally:
                        os.close(descriptor)
                    if directory == store.root:
                        break
                    directory = directory.parent
                conn.execute("INSERT INTO posts(id,slot,received_at,metadata,photo_count,archive_rel,round_id) "
                             "VALUES (?,?,?,?,?,?,?)", (post_id, slot, record["received_at"],
                             json.dumps(metadata, ensure_ascii=False), count, rel, round_id))
                created = conn.execute("SELECT seq FROM posts WHERE id=?", (post_id,)).fetchone()[0]
                conn.execute("INSERT INTO receipts VALUES (?,?,?,?)", (post_id, slot, created, record["received_at"]))
            post = store.get_post(post_id)
            prune(store, slot)
            request.app["archive_wake"].set()
            push.changed(request.app, 3 - slot)
            for queue in list(request.app["listeners"].values()):
                if not queue.full():
                    queue.put_nowait(post["seq"])
            return web.json_response(store.public(post), status=201)
        finally:
            if root:
                shutil.rmtree(root, ignore_errors=True)


async def receive_upload(request, root):
    reader = await request.multipart()
    parts, total = {}, 0
    limits = {"record": 1024, "metadata": 32768, "preview": 2*1024*1024,
              "photo1": MAX_PART, "photo2": MAX_PART}
    while (part := await reader.next()) is not None:
        name = part.name
        if name not in limits or name in parts or part.headers.get("Content-Encoding"):
            raise web.HTTPBadRequest(reason="Unexpected or duplicate photo part.")
        path = Path(root) / name
        count = 0
        with path.open("xb") as output:
            while chunk := await part.read_chunk(65536):
                count += len(chunk)
                total += len(chunk)
                if count > limits[name] or total > MAX_UPLOAD:
                    raise web.HTTPRequestEntityTooLarge(max_size=MAX_UPLOAD, actual_size=total)
                output.write(chunk)
        if count < 1:
            raise web.HTTPBadRequest(reason="Empty photo part.")
        parts[name] = path
    if not {"record", "metadata", "preview", "photo1"}.issubset(parts):
        raise web.HTTPBadRequest(reason="Missing photo parts.")
    return parts


def prune(store, slot):
    # Only superseded posts confirmed archived by the server's Immich worker are removable.
    with store.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        rows = conn.execute("SELECT id,archive_rel FROM posts WHERE slot=? AND archived=1 "
                            "AND seq < (SELECT MAX(seq) FROM posts WHERE slot=?)", (slot, slot)).fetchall()
        for row in rows:
            conn.execute("DELETE FROM posts WHERE id=?", (row["id"],))
    for row in rows:
        shutil.rmtree(store.root / row["archive_rel"], ignore_errors=True)


async def media(request):
    post_id = request.match_info["id"]
    part = request.match_info["part"]
    if part not in ("photo1", "photo2", "preview", "metadata"):
        raise web.HTTPNotFound()
    post = request.app["store"].get_post(post_id)
    if not post or part == "photo2" and post["photo_count"] != 2:
        raise web.HTTPNotFound()
    is_locked = rounds.locked(request.app['store'], post, request['user']['slot'])
    if is_locked and part != 'preview':
        raise web.HTTPForbidden(reason='Share a moment in this round to reveal this photo.')
    filename = 'preview-locked.jpg' if is_locked else part + ('.json' if part == 'metadata' else '.jpg')
    path = request.app["store"].root / post["archive_rel"] / filename
    if not path.is_file():
        raise web.HTTPNotFound()
    return web.FileResponse(path, headers={"Content-Type": "application/json" if part == "metadata" else "image/jpeg",
                                           "Content-Disposition": "attachment"})


async def events(request):
    app = request.app
    key = digest(request["token"])
    if key in app["listeners"] or len(app["listeners"]) >= 4:
        raise web.HTTPConflict(reason="A direct connection is already active. Retry shortly.")
    queue = asyncio.Queue(maxsize=1)
    app["listeners"][key] = queue
    response = web.StreamResponse(headers={"Content-Type": "text/event-stream",
        "Cache-Control": "no-store", "X-Accel-Buffering": "no", "X-Content-Type-Options": "nosniff"})
    try:
        await response.prepare(request)
        await response.write(b"event: ready\ndata: {}\n\n")
        while True:
            try:
                seq = await asyncio.wait_for(queue.get(), timeout=120)
                payload = f"event: changed\ndata: {{\"seq\":{seq}}}\n\n".encode()
            except asyncio.TimeoutError:
                payload = b": heartbeat\n\n"
            if app["store"].authenticate(request["token"]) is None:
                break
            await response.write(payload)
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        app["listeners"].pop(key, None)
    return response


def create_app(root=None, archive_config=None, push_config=None):
    app = web.Application(middlewares=[errors, authentication], client_max_size=MAX_UPLOAD)
    app["store"] = Store(root or os.environ.get("TWOFOLD_DATA", "/data"))
    app["listeners"] = {}
    app["attempts"] = {}
    app["upload_slots"] = asyncio.Semaphore(2)
    app["archive_wake"] = asyncio.Event()
    app["archive_config"] = archive_config
    app['push_config'] = push_config if push_config is not None else push.PushConfig.environment()
    app['push_wake'] = asyncio.Event()
    app.cleanup_ctx.append(push.lifecycle)
    from .archive import lifecycle
    app.cleanup_ctx.append(lifecycle)
    app.add_routes([web.get("/healthz", health), web.post("/v1/enroll", enroll),
        web.get("/v1/me", me), web.delete("/v1/device", disconnect),
        web.get("/v1/state", state), web.get("/v1/posts", history),
        web.post("/v1/posts", upload),
        web.put('/v1/preferences', preferences),
        web.put('/v1/push', push.register), web.delete('/v1/push', push.unregister),
        web.post('/v1/push/confirm', push.confirm),
        web.get("/v1/media/{id}/{part}", media),
        web.get("/v1/events", events)])
    return app


def main():
    os.umask(0o077)
    logging.basicConfig(level=logging.WARNING)
    app = create_app()
    process_lock = (app["store"].root / ".server.lock").open("a")
    fcntl.flock(process_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    web.run_app(app, host=os.environ.get("TWOFOLD_BIND", "0.0.0.0"),
        port=int(os.environ.get("TWOFOLD_PORT", "8080")), access_log=None,
        print=None, shutdown_timeout=5, handler_cancellation=True)


if __name__ == "__main__":
    main()
