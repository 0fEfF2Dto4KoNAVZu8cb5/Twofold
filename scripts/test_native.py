#!/usr/bin/env python3
"""Exercise production Java HTTPS client and device tokens against a local fixture.

Usage: python scripts/test_native.py --jdk /path/to/jdk --json-jar /path/to/json.jar
The org.json JAR is a host-test dependency only; Android provides org.json itself.
"""
import argparse
import base64
from datetime import datetime, timedelta, timezone
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID


class Fixture(BaseHTTPRequestHandler):
    calls = []
    errors = []

    def log_message(self, *args):
        pass

    def send_json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_body(self):
        if self.headers.get("Transfer-Encoding") == "chunked":
            chunks = []
            while True:
                n = int(self.rfile.readline().strip(), 16)
                if not n:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(n))
                self.rfile.read(2)
            return b"".join(chunks)
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def do_POST(self):
        try:
            assert self.headers["Authorization"] == "Bearer test-only-token"
            body = self.read_body()
            if self.path == "/json":
                assert json.loads(body) == {"hello": "world"}
            else:
                assert self.path == "/upload"
                message = BytesParser(policy=policy.default).parsebytes(
                    ("Content-Type: " + self.headers["Content-Type"] + "\r\nMIME-Version: 1.0\r\n\r\n").encode() + body)
                parts = {part.get_param("name", header="content-disposition"): part.get_payload(decode=True) for part in message.iter_parts()}
                assert parts["photo1"] == b"jpeg test original"
                assert json.loads(parts["record"]) == {"version": 1}
            self.send_json(200, {"ok": True})
        except Exception as exc:
            self.errors.append(repr(exc))
            self.send_json(500, {"error": "fixture assertion failed"})

    def do_GET(self):
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/stolen")
            self.end_headers()
        elif self.path == "/denied":
            self.send_json(403, {})
        else:
            self.errors.append("An HTTP redirect was followed")
            self.send_json(500, {})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--json-jar", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory() as temp:
        temp = Path(temp)
        source = root / "android/app/src/main/java/org/twofold/app"
        subprocess.run([str(args.jdk / "bin/javac"), "-cp", str(args.json_jar.resolve()), "-d", str(temp),
            str(root / "tests/native/android/util/Base64.java"), str(root / "tests/native/NativeChecks.java"),
            *[str(source / f"{name}.java") for name in ("Tokens", "Api")]], check=True)
        java = [str(args.jdk / "bin/java"), "-cp", str(temp) + ":" + str(args.json_jar.resolve())]
        private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(private.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=1))
            .not_valid_after(now + timedelta(days=1)).add_extension(x509.SubjectAlternativeName([
                x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
            .sign(private, hashes.SHA256()))
        cert_path, key_path = temp / "cert.pem", temp / "key.pem"
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        key_path.write_bytes(private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        subprocess.run([str(args.jdk / "bin/keytool"), "-importcert", "-noprompt", "-alias", "fixture", "-file", str(cert_path),
                        "-keystore", str(temp / "trust.p12"), "-storepass", "test-only"], check=True, capture_output=True)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert_path, key_path)
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            subprocess.run(java[:1] + [f"-Djavax.net.ssl.trustStore={temp / 'trust.p12'}", "-Djavax.net.ssl.trustStorePassword=test-only"] + java[1:] +
                ["NativeChecks", f"https://localhost:{server.server_port}"], check=True)
        finally:
            server.shutdown()
            server.server_close()
        assert not Fixture.errors, Fixture.errors



if __name__ == "__main__":
    main()
