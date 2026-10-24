import asyncio
import json
import io
from PIL import Image
import os
from pathlib import Path
import secrets
import tempfile
import unittest
import uuid

from aiohttp import FormData
from aiohttp.test_utils import TestClient, TestServer
from twofold.app import create_app


class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.app = create_app(self.temp.name)
        self.store = self.app["store"]
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        self.tokens = {}
        for slot in (1, 2):
            code = self.store.invite(slot, f"Person {slot}")
            token = secrets.token_urlsafe(32)
            result = await self.client.post("/v1/enroll", json={"invite": code, "device_token": token})
            self.assertEqual(result.status, 200)
            self.tokens[slot] = (await result.json())["token"]

    async def asyncTearDown(self):
        await self.client.close()
        self.temp.cleanup()

    def headers(self, slot=1):
        return {"Authorization": "Bearer " + self.tokens[slot]}

    async def upload(self, slot=1, post_id=None, second=False, duplicate=False, layout=None):
        post_id = post_id or str(uuid.uuid4())
        buffer = io.BytesIO()
        Image.new("RGB", (24, 32), (40, 100, 150)).save(buffer, "JPEG")
        payload = buffer.getvalue()
        form = FormData()
        form.add_field("record", json.dumps({"version": 1, "id": post_id}), content_type="application/json")
        metadata = {"id": post_id, "version": 1, "caption": "Hello", "captured_at": "2026-10-23T12:34:56Z"}
        if layout is not None:
            metadata['layout'] = layout
        form.add_field("metadata", json.dumps(metadata), content_type="application/json")
        for part in ["preview", "photo1"] + (["photo2"] if second else []):
            form.add_field(part, payload, filename=part+".jpg", content_type="image/jpeg")
        if duplicate:
            form.add_field("photo1", payload, filename="x")
        response = await self.client.post("/v1/posts", data=form, headers=self.headers(slot))
        return response, post_id, payload

    async def test_pairing_is_one_time_but_same_device_can_retry(self):
        code = self.store.invite(1, "Replacement")
        token = secrets.token_urlsafe(32)
        body = {"invite": code, "device_token": token}
        self.assertEqual((await self.client.post("/v1/enroll", json=body)).status, 200)
        self.assertEqual((await self.client.post("/v1/enroll", json=body)).status, 200)
        body["device_token"] = secrets.token_urlsafe(32)
        self.assertEqual((await self.client.post("/v1/enroll", json=body)).status, 401)
        self.assertEqual((await self.client.get("/v1/state", headers=self.headers(1))).status, 401)

    async def test_bad_invite_does_not_create_device(self):
        body = {"invite": "wrong", "device_token": secrets.token_urlsafe(32)}
        self.assertEqual((await self.client.post("/v1/enroll", json=body)).status, 401)

    async def test_authentication_required_including_media(self):
        response, ident, _ = await self.upload()
        self.assertEqual(response.status, 201)
        for path in ["/v1/state", "/v1/posts", "/v1/events", f"/v1/media/{ident}/photo1"]:
            self.assertEqual((await self.client.get(path)).status, 401)

    async def test_upload_two_photos_and_partner_view(self):
        response, ident, payload = await self.upload(second=True)
        self.assertEqual(response.status, 201)
        body = await response.json()
        self.assertEqual(body["photo_count"], 2)
        self.assertEqual(body["metadata"]["caption"], "Hello")
        state = await (await self.client.get("/v1/state", headers=self.headers(2))).json()
        self.assertEqual(state["partner"]["id"], ident)
        self.assertIsNone(state["own"])
        self.assertTrue(state['partner']['locked'])
        await self.upload(slot=2)
        media = await self.client.get(f"/v1/media/{ident}/photo2", headers=self.headers(2))
        self.assertEqual(await media.read(), payload)
        self.assertEqual(media.headers["Cache-Control"], "no-store")

    async def test_round_gate_redacts_history_metadata_and_originals(self):
        _, ident, payload = await self.upload(second=True)
        state = await (await self.client.get('/v1/state', headers=self.headers(2))).json()
        self.assertTrue(state['round']['waiting_for_you'])
        self.assertEqual(state['partner']['metadata'], {'author_name': 'Person 1'})
        history = await (await self.client.get('/v1/posts', headers=self.headers(2))).json()
        self.assertTrue(history['items'][0]['locked'])
        self.assertNotIn('caption', history['items'][0]['metadata'])
        for part in ('photo1', 'photo2', 'metadata'):
            self.assertEqual((await self.client.get(f'/v1/media/{ident}/{part}', headers=self.headers(2))).status, 403)
        blurred = await self.client.get(f'/v1/media/{ident}/preview', headers=self.headers(2))
        self.assertEqual(blurred.status, 200)
        self.assertNotEqual(await blurred.read(), payload)
        self.assertEqual((await self.client.get(f'/v1/media/{ident}/photo1', headers=self.headers(1))).status, 200)

    async def test_reply_unlocks_both_without_a_new_round(self):
        _, first, _ = await self.upload()
        initial = await (await self.client.get('/v1/state', headers=self.headers(2))).json()
        round_id = initial['round']['id']
        # A retake before a reply stays in the same round.
        _, retake, _ = await self.upload()
        await self.upload(slot=2)
        for slot in (1, 2):
            state = await (await self.client.get('/v1/state', headers=self.headers(slot))).json()
            self.assertEqual(state['round']['id'], round_id)
            self.assertTrue(state['round']['complete'])
            self.assertFalse(state['partner']['locked'])
        self.assertEqual((await self.client.get(f'/v1/media/{first}/metadata', headers=self.headers(2))).status, 200)
        # Retrying an already accepted upload must not open a new round.
        await self.upload(post_id=retake)
        state = await (await self.client.get('/v1/state', headers=self.headers())).json()
        self.assertEqual(state['round']['id'], round_id)
        _, next_id, _ = await self.upload()
        state = await (await self.client.get('/v1/state', headers=self.headers(2))).json()
        self.assertNotEqual(state['round']['id'], round_id)
        self.assertTrue(state['partner']['locked'])
        self.assertEqual(state['partner']['id'], next_id)

    async def test_simultaneous_pair_joins_one_round_and_survives_restart(self):
        results = await asyncio.gather(self.upload(slot=1), self.upload(slot=2))
        self.assertTrue(all(response.status == 201 for response, _, _ in results))
        from twofold.storage import Store
        from twofold.rounds import current
        reopened = Store(self.temp.name)
        round_state = current(reopened, 1)
        self.assertTrue(round_state['complete'])
        with reopened.connect() as conn:
            self.assertEqual(conn.execute('SELECT COUNT(*) FROM rounds').fetchone()[0], 1)

    async def test_optional_reveal_rule_and_layout_validation(self):
        layout = {'main_photo': 2, 'inset_corner': 'bottom_right'}
        response, ident, _ = await self.upload(second=True, layout=layout)
        self.assertEqual((await response.json())['metadata']['layout'], layout)
        result = await self.client.put('/v1/preferences', json={'share_to_reveal': False}, headers=self.headers(2))
        self.assertEqual(result.status, 200)
        state = await (await self.client.get('/v1/state', headers=self.headers(2))).json()
        self.assertFalse(state['partner']['locked'])
        self.assertEqual((await self.client.get(f'/v1/media/{ident}/photo1', headers=self.headers(2))).status, 200)
        await self.client.put('/v1/preferences', json={'share_to_reveal': True}, headers=self.headers())
        self.assertEqual((await self.client.get(f'/v1/media/{ident}/photo1', headers=self.headers(2))).status, 403)
        self.assertEqual((await self.upload(layout={'main_photo': 2, 'inset_corner': 'bottom_right'}))[0].status, 400)
        self.assertEqual((await self.upload(layout={'main_photo': True, 'inset_corner': 'top_left'}))[0].status, 400)
        self.assertEqual((await self.client.put('/v1/preferences', json={'share_to_reveal': 'true'}, headers=self.headers())).status, 400)
        self.assertEqual((await self.client.put('/v1/preferences', json={'share_to_reveal': True})).status, 401)

    async def test_archival_pruning_keeps_participation_and_past_unlocks(self):
        _, old, _ = await self.upload()
        _, partner, _ = await self.upload(slot=2)
        with self.store.connect() as conn:
            conn.execute('UPDATE posts SET archived=1 WHERE id=?', (old,))
        await self.upload()
        self.assertIsNone(self.store.get_post(old))
        state = await (await self.client.get('/v1/state', headers=self.headers())).json()
        self.assertFalse(state['partner']['locked'])
        self.assertEqual((await self.client.get(f'/v1/media/{partner}/photo1', headers=self.headers())).status, 200)

    async def test_existing_database_migrates_without_relocking_legacy_posts(self):
        _, ident, _ = await self.upload()
        with self.store.connect() as conn:
            # Recreate alpha.3's schema shape while preserving actual pairing/photo rows.
            conn.execute('DROP TABLE round_members')
            conn.execute('DROP TABLE rounds')
            conn.execute('ALTER TABLE posts DROP COLUMN round_id')
        from twofold.storage import Store
        from twofold.rounds import visible, current
        upgraded = Store(self.temp.name)
        self.assertEqual(upgraded.authenticate(self.tokens[1])['slot'], 1)
        legacy = visible(upgraded, upgraded.get_post(ident), 2)
        self.assertFalse(legacy['locked'])
        self.assertEqual(legacy['metadata']['caption'], 'Hello')
        self.assertIsNone(current(upgraded, 1))

    async def test_archive_failure_preserves_superseded_originals(self):
        _, first, _ = await self.upload()
        _, second, _ = await self.upload()
        self.assertIsNotNone(self.store.get_post(first))
        self.assertEqual((await self.client.get(f"/v1/media/{first}/photo1", headers=self.headers())).status, 200)
        latest = await (await self.client.get("/v1/state", headers=self.headers())).json()
        self.assertEqual(latest["own"]["id"], second)

    async def test_prunes_only_archived_superseded_post(self):
        _, first, _ = await self.upload()
        first_path = self.store.root / self.store.get_post(first)["archive_rel"]
        # There is no client endpoint to claim that an archive happened.
        denied = await self.client.post(f"/v1/posts/{first}/archived", json={}, headers=self.headers())
        self.assertIn(denied.status, (404, 405))
        with self.store.connect() as conn:
            conn.execute("UPDATE posts SET archived=1 WHERE id=?", (first,))
        self.assertTrue(first_path.exists())  # Latest picture is always retained.
        _, second, _ = await self.upload()
        self.assertIsNone(self.store.get_post(first))
        self.assertFalse(first_path.exists())
        self.assertIsNotNone(self.store.get_post(second))
        again, _, _ = await self.upload(post_id=first)
        self.assertEqual(again.status, 200)
        self.assertTrue((await again.json())["retired"])
        self.assertIsNone(self.store.get_post(first))
    async def test_upload_retry_is_idempotent_and_identity_owned(self):
        first, ident, _ = await self.upload()
        seq = (await first.json())["seq"]
        again, _, _ = await self.upload(post_id=ident)
        self.assertEqual(again.status, 200)
        self.assertEqual((await again.json())["seq"], seq)
        collision, _, _ = await self.upload(slot=2, post_id=ident)
        self.assertEqual(collision.status, 409)

    async def test_duplicate_multipart_part_rejected_and_temp_cleaned(self):
        response, _, _ = await self.upload(duplicate=True)
        self.assertEqual(response.status, 400)
        self.assertEqual(list((Path(self.temp.name)/"tmp").iterdir()), [])

    async def test_disconnect_revokes_token(self):
        self.assertEqual((await self.client.delete("/v1/device", headers=self.headers())).status, 200)
        self.assertEqual((await self.client.get("/v1/state", headers=self.headers())).status, 401)

    async def test_sse_delivers_changes_without_photo_payload(self):
        events = await self.client.get("/v1/events", headers=self.headers(2))
        self.assertEqual(events.status, 200)
        self.assertEqual(await events.content.readline(), b"event: ready\n")
        await events.content.readline()
        await events.content.readline()
        response, ident, _ = await self.upload()
        self.assertEqual(response.status, 201)
        line = await asyncio.wait_for(events.content.readline(), 3)
        self.assertEqual(line, b"event: changed\n")
        payload = await events.content.readline()
        self.assertIn(b'"seq"', payload)
        self.assertNotIn(ident.encode(), payload)
        events.close()


if __name__ == "__main__":
    unittest.main()
