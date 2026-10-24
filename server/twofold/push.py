"""Optional encrypted ntfy wake signals; photos and notification text never go here."""
import asyncio
import base64
import contextlib
from dataclasses import dataclass
import hmac
import json
import os
import re
import secrets
import time
from urllib.parse import urlsplit, parse_qsl

from aiohttp import web, ClientSession, ClientTimeout
from .storage import digest


def url_parts(value):
    if not isinstance(value, str) or not value or len(value) > 1000 or any(ord(c) <= 32 or ord(c) == 127 for c in value):
        raise ValueError('Use a valid HTTPS ntfy address.')
    p = urlsplit(value)
    if p.scheme != 'https' or not p.hostname or p.username is not None or p.password is not None or p.fragment:
        raise ValueError('ntfy requires HTTPS without embedded credentials.')
    origin = (p.scheme, p.hostname.lower(), p.port or 443)
    return p, origin


@dataclass
class PushConfig:
    ntfy_url: str = ''
    ssl_context: object = None
    retry_base: float = 30

    def __post_init__(self):
        self.ntfy_url = self.ntfy_url.rstrip('/')
        if self.ntfy_url:
            parsed, _ = url_parts(self.ntfy_url)
            if parsed.path or parsed.query:
                raise ValueError('TWOFOLD_NTFY_URL must be the HTTPS ntfy origin, without a path or query.')

    @classmethod
    def environment(cls):
        return cls(ntfy_url=os.environ.get('TWOFOLD_NTFY_URL', ''))

    def validate_endpoint(self, endpoint):
        if not self.ntfy_url:
            raise ValueError('Configure TWOFOLD_NTFY_URL on your Twofold server first.')
        p, origin = url_parts(endpoint)
        _, permitted = url_parts(self.ntfy_url)
        if origin != permitted or not re.fullmatch(r'/up[A-Za-z0-9_-]{8,200}', p.path):
            raise ValueError('Use a UnifiedPush topic on the ntfy server configured for Twofold.')
        if any(key.lower() not in ('up', 'unifiedpush', 'x-unifiedpush') or value != '1' for key, value in parse_qsl(p.query, keep_blank_values=True)):
            raise ValueError('Unexpected ntfy endpoint parameters.')
        return endpoint


def decode_key(value, size):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', value):
        raise ValueError('Invalid Web Push key.')
    raw = base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
    if len(raw) != size:
        raise ValueError('Invalid Web Push key size.')
    return raw


def validate_keys(keys):
    if not isinstance(keys, dict) or set(keys) != {'p256dh', 'auth'}:
        raise ValueError('Include the Web Push public key and authentication secret.')
    from cryptography.hazmat.primitives.asymmetric import ec
    ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), decode_key(keys['p256dh'], 65))
    decode_key(keys['auth'], 16)
    return {'p256dh': keys['p256dh'], 'auth': keys['auth']}


def encrypt_signal(keys, payload):
    import http_ece
    from cryptography.hazmat.primitives.asymmetric import ec
    return http_ece.encrypt(json.dumps(payload, separators=(',', ':')).encode(),
        private_key=ec.generate_private_key(ec.SECP256R1()), dh=decode_key(keys['p256dh'], 65),
        auth_secret=decode_key(keys['auth'], 16), version='aes128gcm')


def status(app, token):
    with app['store'].connect() as conn:
        row = conn.execute('SELECT verified,error FROM push_subscriptions WHERE device_hash=?', (digest(token),)).fetchone()
    return {'available': bool(app['push_config'].ntfy_url), 'has_endpoint': row is not None,
            'verified': bool(app['push_config'].ntfy_url and row and row['verified']),
            'status': row['error'] if row and row['error'] else ('ntfy connected' if row and row['verified'] else 'Waiting for ntfy confirmation' if row else 'Not connected to ntfy')}


async def body(request):
    if request.content_type != 'application/json' or request.content_length is None or request.content_length > 4096:
        raise web.HTTPBadRequest(reason='Send a small JSON push configuration.')
    value = await request.json()
    if not isinstance(value, dict):
        raise web.HTTPBadRequest(reason='Invalid push configuration.')
    return value


async def register(request):
    value = await body(request)
    config = request.app['push_config']
    try:
        endpoint = config.validate_endpoint(value.get('endpoint'))
        keys = validate_keys(value.get('keys'))
    except (ValueError, TypeError) as exc:
        raise web.HTTPBadRequest(reason=str(exc))
    device_hash = digest(request['token'])
    key_json = json.dumps(keys, sort_keys=True)
    with request.app['store'].connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        current = conn.execute('SELECT * FROM push_subscriptions WHERE device_hash=?', (device_hash,)).fetchone()
        if current and current['endpoint'] == endpoint and current['keys_json'] == key_json:
            # Preserve the challenge on retries; a delayed callback can still confirm it.
            if not current['verified'] and time.time() - current['last_sent'] > 60:
                conn.execute('UPDATE push_subscriptions SET pending=1,retry_at=0,generation=generation+1 WHERE device_hash=?', (device_hash,))
        else:
            challenge = secrets.token_urlsafe(24)
            conn.execute('INSERT OR REPLACE INTO push_subscriptions(device_hash,endpoint,keys_json,challenge,challenge_hash,pending) VALUES (?,?,?,?,?,1)',
                         (device_hash, endpoint, key_json, challenge, digest(challenge)))
    request.app['push_wake'].set()
    return web.json_response(status(request.app, request['token']))


async def confirm(request):
    value = await body(request)
    challenge = value.get('challenge')
    if not isinstance(challenge, str) or not re.fullmatch(r'[A-Za-z0-9_-]{32}', challenge):
        raise web.HTTPBadRequest(reason='Invalid push confirmation.')
    with request.app['store'].connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        row = conn.execute('SELECT * FROM push_subscriptions WHERE device_hash=?', (digest(request['token']),)).fetchone()
        if row is None or not hmac.compare_digest(row['challenge_hash'], digest(challenge)):
            raise web.HTTPBadRequest(reason='Push channel changed. Reconnect ntfy on this phone.')
        conn.execute("UPDATE push_subscriptions SET verified=1,challenge='',error='',pending=0,generation=generation+1 WHERE device_hash=?", (digest(request['token']),))
    return web.json_response(status(request.app, request['token']))


async def unregister(request):
    with request.app['store'].connect() as conn:
        conn.execute('DELETE FROM push_subscriptions WHERE device_hash=?', (digest(request['token']),))
    return web.json_response({'unregistered': True})


def changed(app, recipient_slot=None):
    with app['store'].connect() as conn:
        conn.execute('UPDATE push_subscriptions SET pending=1,retry_at=0,attempts=0,generation=generation+1 '
                     'WHERE verified=1 AND device_hash IN (SELECT hash FROM devices WHERE active=1' +
                     (' AND slot=?' if recipient_slot else '') + ')', (recipient_slot,) if recipient_slot else ())
    app['push_wake'].set()


class Publisher:
    def __init__(self, app):
        self.app, self.store, self.config = app, app['store'], app['push_config']

    async def send(self, session, row):
        # Validate again after restarts/config changes. No redirects or environment proxy.
        self.config.validate_endpoint(row['endpoint'])
        payload = {'event': 'sync'}
        if not row['verified']:
            payload['challenge'] = row['challenge']
        encrypted = encrypt_signal(json.loads(row['keys_json']), payload)
        async with session.post(row['endpoint'], data=encrypted, ssl=self.config.ssl_context, allow_redirects=False,
            headers={'Content-Type': 'application/octet-stream', 'Content-Encoding': 'aes128gcm',
                     'X-UnifiedPush': '1', 'Firebase': 'no', 'TTL': '86400', 'Urgency': 'normal'}) as response:
            await response.content.read(65537)
            if response.status not in (200, 201, 202, 204):
                raise ValueError(f'ntfy returned HTTP {response.status}; periodic delivery remains available.')

    async def run(self):
        async with ClientSession(timeout=ClientTimeout(total=15, connect=5), trust_env=False) as session:
            while True:
                self.app['push_wake'].clear()
                with self.store.connect() as conn:
                    conn.execute('DELETE FROM push_subscriptions WHERE device_hash IN (SELECT hash FROM devices WHERE active=0)')
                    rows = [dict(r) for r in conn.execute('SELECT p.* FROM push_subscriptions p JOIN devices d ON p.device_hash=d.hash WHERE d.active=1 AND p.pending=1 AND p.retry_at<=?', (time.time(),))]
                for row in rows:
                    try:
                        await self.send(session, row)
                    except asyncio.CancelledError:
                        raise
                    except Exception as exc:
                        message = str(exc) if isinstance(exc, ValueError) else 'ntfy is unavailable; update signal will retry.'
                        delay = min(1800, self.config.retry_base * 2 ** min(row['attempts'], 6))
                        with self.store.connect() as conn:
                            conn.execute('UPDATE push_subscriptions SET error=?,attempts=attempts+1,retry_at=? WHERE device_hash=? AND generation=?',
                                         (message, time.time() + delay, row['device_hash'], row['generation']))
                    else:
                        with self.store.connect() as conn:
                            conn.execute("UPDATE push_subscriptions SET pending=0,error='',attempts=0,last_sent=? WHERE device_hash=? AND generation=?",
                                         (time.time(), row['device_hash'], row['generation']))
                try:
                    await asyncio.wait_for(self.app['push_wake'].wait(), timeout=max(0.05, min(30, self.config.retry_base)))
                except asyncio.TimeoutError:
                    pass


async def lifecycle(app):
    task = asyncio.create_task(Publisher(app).run()) if app['push_config'].ntfy_url else None
    try:
        yield
    finally:
        if task:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
