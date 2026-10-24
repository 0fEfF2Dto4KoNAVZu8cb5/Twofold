"""Real TLS + RFC8291 crypto fixtures for optional ntfy delivery."""
import asyncio
import base64
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import os
import ssl
import tempfile
import unittest
import uuid

import http_ece
from aiohttp import web, FormData
from aiohttp.test_utils import TestClient, TestServer
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.x509.oid import NameOID
from PIL import Image
from twofold.app import create_app
from twofold.push import PushConfig, decode_key


def b64(raw):
    return base64.urlsafe_b64encode(raw).decode().rstrip('=')


class PushTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'localhost')])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=1)).not_valid_after(now+timedelta(days=1))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost')]), critical=False).sign(key, hashes.SHA256()))
        (root/'cert.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        (root/'key.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        server_ssl = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); server_ssl.load_cert_chain(root/'cert.pem', root/'key.pem')
        client_ssl = ssl.create_default_context(cafile=str(root/'cert.pem'))
        self.key = ec.generate_private_key(ec.SECP256R1()); self.auth = os.urandom(16)
        self.signals, self.headers, self.raw, self.paths = [], [], [], []
        self.fail_publish, self.redirect = False, False

        async def publish(request):
            raw = await request.read()
            self.raw.append(raw); self.headers.append(dict(request.headers)); self.paths.append(request.path)
            if self.redirect:
                return web.Response(status=307, headers={'Location': self.origin + '/unexpected'})
            if self.fail_publish:
                return web.Response(status=503)
            self.signals.append(json.loads(http_ece.decrypt(raw, private_key=self.key, auth_secret=self.auth, version='aes128gcm')))
            return web.json_response({'id': 'fixture-only'})

        fixture = web.Application(); fixture.add_routes([web.post('/{topic}', publish)])
        self.fixture = TestServer(fixture, host='localhost'); await self.fixture.start_server(ssl=server_ssl)
        self.origin = f'https://localhost:{self.fixture.port}'
        self.config = PushConfig(ntfy_url=self.origin, ssl_context=client_ssl, retry_base=0.1)
        self.app = create_app(root/'data', push_config=self.config)
        self.client = TestClient(TestServer(self.app)); await self.client.start_server()
        self.store = self.app['store']
        self.tokens = {slot: self.store.enroll(self.store.invite(slot, f'Person {slot}'))['token'] for slot in (1, 2)}
        self.subscription = {'endpoint': self.origin + '/up' + uuid.uuid4().hex + '?up=1',
            'keys': {'p256dh': b64(self.key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)), 'auth': b64(self.auth)}}

    async def asyncTearDown(self):
        await self.client.close(); await self.fixture.close(); self.temp.cleanup()

    def auth_headers(self, slot=1):
        return {'Authorization': 'Bearer ' + self.tokens[slot]}

    async def wait_for(self, test):
        for _ in range(120):
            if test():
                return
            await asyncio.sleep(0.025)
        self.fail('Push fixture did not reach expected state')

    async def register(self):
        before = len(self.signals)
        response = await self.client.put('/v1/push', json=self.subscription, headers=self.auth_headers())
        self.assertEqual(response.status, 200, await response.text())
        await self.wait_for(lambda: len(self.signals) > before)
        return self.signals[-1]['challenge']

    async def confirm(self):
        challenge = await self.register()
        response = await self.client.post('/v1/push/confirm', json={'challenge': challenge}, headers=self.auth_headers())
        self.assertEqual(response.status, 200)
        self.assertTrue((await response.json())['verified'])
        return challenge

    async def photo(self, slot=2):
        ident = str(uuid.uuid4()); form = FormData()
        form.add_field('record', json.dumps({'id': ident, 'version': 1}))
        form.add_field('metadata', json.dumps({'id': ident, 'version': 1, 'caption': 'PRIVATE-CAPTION-NOT-IN-PUSH', 'captured_at': '2026-10-23T12:00:00Z'}))
        data = io.BytesIO(); Image.new('RGB', (24,32), (50,80,120)).save(data, 'JPEG')
        for part in ('preview', 'photo1'):
            form.add_field(part, data.getvalue(), filename=part+'.jpg', content_type='image/jpeg')
        result = await self.client.post('/v1/posts', data=form, headers=self.auth_headers(slot))
        self.assertEqual(result.status, 201, await result.text())
        return ident

    async def test_verified_encrypted_hint_without_any_photo_context(self):
        challenge = await self.confirm()
        self.assertEqual(set(self.signals[0]), {'event', 'challenge'})
        retry = await self.client.post('/v1/push/confirm', json={'challenge': challenge}, headers=self.auth_headers())
        self.assertEqual(retry.status, 200)
        await self.photo()
        await self.wait_for(lambda: len(self.signals) >= 2)
        self.assertEqual(self.signals[1], {'event': 'sync'})
        headers = {key.lower(): value for key, value in self.headers[-1].items()}
        self.assertEqual(headers['content-encoding'], 'aes128gcm')
        self.assertEqual(headers['x-unifiedpush'], '1')
        self.assertEqual(headers['firebase'], 'no')
        self.assertEqual(headers['ttl'], '86400')
        self.assertNotIn(b'PRIVATE-CAPTION', b''.join(self.raw))
        state = await (await self.client.get('/v1/state', headers=self.auth_headers())).json()
        self.assertTrue(state['push']['verified'])
        self.assertNotIn('endpoint', state['push'])
        self.assertNotIn('keys', state['push'])

    async def test_no_content_hints_until_challenge_returns_to_same_device(self):
        challenge = await self.register()
        wrong = await self.client.post('/v1/push/confirm', json={'challenge': challenge}, headers=self.auth_headers(2))
        self.assertEqual(wrong.status, 400)
        await self.photo()
        await asyncio.sleep(0.1)
        self.assertEqual(len(self.signals), 1)
        await self.client.post('/v1/push/confirm', json={'challenge': challenge}, headers=self.auth_headers())
        await self.photo()
        await self.wait_for(lambda: len(self.signals) == 2)
        # A retry of the same subscription must not rotate a verified channel.
        response = await self.client.put('/v1/push', json=self.subscription, headers=self.auth_headers())
        self.assertTrue((await response.json())['verified'])

    async def test_push_failure_does_not_fail_photos_and_retries(self):
        await self.confirm(); self.fail_publish = True
        ident = await self.photo()
        def failed():
            with self.store.connect() as conn:
                return bool(conn.execute("SELECT 1 FROM push_subscriptions WHERE error!=''").fetchone())
        await self.wait_for(failed)
        self.assertIsNotNone(self.store.get_post(ident))
        self.fail_publish = False
        with self.store.connect() as conn:
            conn.execute('UPDATE push_subscriptions SET retry_at=0')
        self.app['push_wake'].set()
        await self.wait_for(lambda: len(self.signals) >= 2)
        self.assertEqual(self.signals[-1], {'event': 'sync'})

    async def test_unregistration_and_pair_replacement_stop_delivery(self):
        await self.confirm()
        response = await self.client.delete('/v1/push', headers=self.auth_headers())
        self.assertEqual(response.status, 200)
        await self.photo(); await asyncio.sleep(0.1)
        self.assertEqual(len(self.signals), 1)
        await self.confirm()
        self.store.enroll(self.store.invite(1, 'Replacement'))
        with self.store.connect() as conn:
            self.assertEqual(conn.execute('SELECT COUNT(*) FROM push_subscriptions').fetchone()[0], 0)

    async def test_endpoint_allowlist_auth_and_redirect_rejection(self):
        unsafe = ['http://localhost/up1234567890', 'https://example.com/up1234567890',
            self.origin+'/api/delete', self.origin+'/up1234567890?url=http://169.254.169.254',
            self.origin+'/up1234567890#fragment', self.origin+'/up%2fprivate',
            self.origin.replace('https://', 'https://user:pass@')+'/up1234567890']
        for endpoint in unsafe:
            response = await self.client.put('/v1/push', json={**self.subscription, 'endpoint': endpoint}, headers=self.auth_headers())
            self.assertEqual(response.status, 400, endpoint)
        self.assertEqual(self.raw, [])
        self.assertEqual((await self.client.put('/v1/push', json=self.subscription)).status, 401)
        self.redirect = True
        await self.client.put('/v1/push', json=self.subscription, headers=self.auth_headers())
        await self.wait_for(lambda: bool(self.raw))
        await asyncio.sleep(0.05)
        self.assertEqual(self.signals, [])
        # A followed redirect would make the fixture receive a second POST to /unexpected.
        self.assertNotIn('/unexpected', self.paths)
