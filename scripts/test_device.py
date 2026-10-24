#!/usr/bin/env python3
"""Boot an isolated AOSP emulator and test actual Android code with local TLS fixtures.

Needs the Android emulator, system-images;android-35;aosp_atd;x86_64, an AVD called
twofold-test, and built debug + androidTest APKs. Requires aiohttp, Pillow, and
cryptography on the host. Refuses to use a real phone. Test credentials are fake.
"""
import argparse
import asyncio
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import io
import ipaddress
import json
import os
from pathlib import Path
import ssl
import sys
import tempfile
import time
import uuid

from aiohttp import web, ClientSession, FormData
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from twofold.app import create_app
from twofold.archive import ArchiveConfig



async def command(*args, timeout=45):
    process = await asyncio.create_subprocess_exec(*map(str, args), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
    try:
        output, _ = await asyncio.wait_for(process.communicate(), timeout)
    except asyncio.TimeoutError:
        process.kill()
        await process.wait()
        raise
    return process.returncode, output.decode(errors="replace")


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--avd-home", type=Path, required=True)
    parser.add_argument("--avd", default="twofold-test")
    parser.add_argument("--log-directory", type=Path, required=True)
    args = parser.parse_args()
    args.log_directory.mkdir(parents=True, exist_ok=True)
    adb = args.sdk / "platform-tools/adb"
    await command(adb, "start-server")
    env = dict(os.environ, ANDROID_HOME=str(args.sdk), ANDROID_AVD_HOME=str(args.avd_home))
    serial = "emulator-5580"
    with tempfile.TemporaryDirectory() as temp:
        temp = Path(temp)
        private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Twofold isolated test fixture")])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(private.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=1)).not_valid_after(now+timedelta(days=1))
            .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"),
                x509.IPAddress(ipaddress.ip_address("127.0.0.1")), x509.IPAddress(ipaddress.ip_address("10.0.2.2"))]), critical=False)
            .sign(private, hashes.SHA256()))
        cert_path, key_path = temp / "cert.pem", temp / "key.pem"
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        key_path.write_bytes(private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert_path, key_path)
        client_tls = ssl.create_default_context(cafile=str(cert_path))
        archive_config = ArchiveConfig(key="test-only-api-key", album_id="22222222-2222-4222-8222-222222222222", retry_base=0.1, ssl_context=client_tls)
        relay = create_app(temp / "data", archive_config=archive_config)
        store = relay["store"]
        tokens = {}
        for slot in (1, 2):
            invitation = store.invite(slot, "Mike" if slot == 1 else "Partner")
            tokens[slot] = store.enroll(invitation)["token"]
        relay_runner = web.AppRunner(relay, access_log=None, handler_cancellation=True)
        await relay_runner.setup()
        relay_site = web.TCPSite(relay_runner, "127.0.0.1", 0, ssl_context=tls)
        await relay_site.start()
        relay_port = relay_site._server.sockets[0].getsockname()[1]
        uploaded, memberships = {}, set()

        async def archive(request):
            assert request.headers.get("x-api-key") == "test-only-api-key"
            reader = await request.multipart()
            parts = {}
            while (part := await reader.next()) is not None:
                parts[part.name] = await part.read()
            data = bytes(parts["assetData"])
            image = Image.open(io.BytesIO(data))
            # Full metadata is preserved by default; the second moment tests stripping.
            assert image.format == "JPEG"
            assert b"xmpmeta" in parts["sidecarData"]
            checksum = hashlib.sha256(data).hexdigest()
            duplicate = checksum in uploaded
            uploaded.setdefault(checksum, str(uuid.uuid4()))
            return web.json_response({"id": uploaded[checksum], "status": "duplicate" if duplicate else "created"}, status=200 if duplicate else 201)

        async def album(request):
            body = await request.json()
            failed = request.match_info["id"].startswith("3333")
            rows = []
            for ident in body["ids"]:
                if not failed:
                    memberships.add(ident)
                rows.append({"id": ident, "success": not failed, "error": "no_permission" if failed else None})
            return web.json_response(rows)

        immich = web.Application()
        immich.add_routes([web.post("/api/assets", archive), web.put("/api/albums/{id}/assets", album)])
        immich_runner = web.AppRunner(immich, access_log=None)
        await immich_runner.setup()
        immich_site = web.TCPSite(immich_runner, "127.0.0.1", 0, ssl_context=tls)
        await immich_site.start()
        immich_port = immich_site._server.sockets[0].getsockname()[1]
        archive_config.url = f"https://localhost:{immich_port}"
        buffer = io.BytesIO()
        Image.new("RGB", (240, 320), (190, 140, 100)).save(buffer, "JPEG")
        plain = buffer.getvalue()
        ident = str(uuid.uuid4())
        meta = {"version": 1, "id": ident, "slot": 2, "author_name": "Partner", "caption": "A fixture moment",
            "captured_at": "2026-10-23T11:00:00Z", "images": [{"part": "photo1", "sha256": hashlib.sha256(plain).hexdigest()}]}
        form = FormData()
        form.add_field("record", json.dumps({"id": ident, "version": 1}))
        for part, data in [("metadata", json.dumps(meta).encode()), ("preview", plain), ("photo1", plain)]:
            form.add_field(part, data, filename=part)
        client_tls = ssl.create_default_context(cafile=str(cert_path))
        async with ClientSession() as client:
            response = await client.post(f"https://localhost:{relay_port}/v1/posts", data=form,
                headers={"Authorization": "Bearer " + tokens[2]}, ssl=client_tls)
            assert response.status == 201, await response.text()
        log = (args.log_directory / "emulator.log").open("w")
        emulator = await asyncio.create_subprocess_exec(str(args.sdk / "emulator/emulator"), "-avd", args.avd, "-port", "5580",
            "-read-only", "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-accel", "off", "-gpu", "swiftshader_indirect",
            "-memory", "2048", "-cores", "4", "-no-metrics", env=env, stdout=log, stderr=asyncio.subprocess.STDOUT)
        try:
            print("Booting an isolated software Android emulator…", flush=True)
            deadline = time.monotonic() + 90
            last_status = 0
            while time.monotonic() < deadline:
                if emulator.returncode is not None:
                    raise RuntimeError("Emulator exited: " + (args.log_directory / "emulator.log").read_text()[-2500:])
                try:
                    _, boot = await command(adb, "-s", serial, "shell", "getprop", "sys.boot_completed", timeout=5)
                except asyncio.TimeoutError:
                    boot = ""
                if boot.strip() == "1":
                    break
                if time.monotonic() - last_status > 30:
                    print("Waiting for Android to finish booting…", flush=True)
                    last_status = time.monotonic()
                await asyncio.sleep(2)
            else:
                raise RuntimeError("Software emulator did not finish booting within 90 seconds")
            print("Android booted; installing app and test-only runner…", flush=True)
            for apk in [ROOT / "android/app/build/outputs/apk/debug/app-debug.apk",
                        ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
                status, output = await command(adb, "-s", serial, "install", "-r", apk)
                assert status == 0 and "Success" in output, output
            command_args = [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
                "-e", "ca", base64.b64encode(cert.public_bytes(serialization.Encoding.DER)).decode(),
                "-e", "relay", f"https://10.0.2.2:{relay_port}", "-e", "immich", f"https://10.0.2.2:{immich_port}",
                "-e", "token", tokens[1], "org.twofold.app.test/org.twofold.app.DeviceChecks"]
            _, output = await command(*command_args, timeout=240)
            (args.log_directory / "instrumentation.txt").write_text(output)
            _, logs = await command(adb, "-s", serial, "logcat", "-d", "-t", "600")
            (args.log_directory / "logcat.txt").write_text(logs)
            assert "DEVICE_CHECKS_PASSED" in output, output
            for _ in range(100):
                if len(uploaded) == 4 and len(memberships) == 4:
                    break
                await asyncio.sleep(0.1)
            assert len(uploaded) == 4 and len(memberships) == 4, (len(uploaded), len(memberships))
            with store.connect() as conn:
                assert conn.execute("SELECT COUNT(*) FROM posts").fetchone()[0] == 2
            print(output, flush=True)
            print("Device integration passed; four originals archived and only two latest picture sets retained.", flush=True)
        finally:
            try:
                await command(adb, "-s", serial, "emu", "kill", timeout=10)
            except asyncio.TimeoutError:
                pass
            if emulator.returncode is None:
                emulator.terminate()
                try:
                    await asyncio.wait_for(emulator.wait(), 10)
                except asyncio.TimeoutError:
                    emulator.kill()
                    await emulator.wait()
            log.close()
            await relay_runner.cleanup()
            await immich_runner.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
