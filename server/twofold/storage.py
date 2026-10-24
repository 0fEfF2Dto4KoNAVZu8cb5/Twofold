import contextlib
import hashlib
import os
import secrets
import sqlite3
import time
from pathlib import Path


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class Store:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        (self.root / "archive").mkdir(exist_ok=True, mode=0o700)
        (self.root / "tmp").mkdir(exist_ok=True, mode=0o700)
        self.db = self.root / "twofold.sqlite3"
        with self.connect() as conn:
            conn.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS users(
                    slot INTEGER PRIMARY KEY CHECK(slot IN (1,2)), name TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS invites(
                    hash TEXT PRIMARY KEY, slot INTEGER NOT NULL REFERENCES users(slot),
                    expires INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0, device_hash TEXT);
                CREATE TABLE IF NOT EXISTS devices(
                    hash TEXT PRIMARY KEY, slot INTEGER NOT NULL REFERENCES users(slot),
                    created INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1);
                CREATE TABLE IF NOT EXISTS posts(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
                    slot INTEGER NOT NULL REFERENCES users(slot), received_at TEXT NOT NULL,
                    metadata TEXT NOT NULL, photo_count INTEGER NOT NULL,
                    archive_rel TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0,
                    archive_assets TEXT NOT NULL DEFAULT '[]', archive_attempts INTEGER NOT NULL DEFAULT 0,
                    archive_retry_at REAL NOT NULL DEFAULT 0, archive_error TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS receipts(
                    id TEXT PRIMARY KEY, slot INTEGER NOT NULL, seq INTEGER NOT NULL,
                    received_at TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS posts_slot_seq ON posts(slot, seq DESC);
                CREATE TABLE IF NOT EXISTS rounds(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS round_members(
                    round_id TEXT NOT NULL REFERENCES rounds(id), slot INTEGER NOT NULL REFERENCES users(slot),
                    post_id TEXT NOT NULL, submitted_at TEXT NOT NULL, PRIMARY KEY(round_id,slot));
                CREATE TABLE IF NOT EXISTS push_subscriptions(
                    device_hash TEXT PRIMARY KEY REFERENCES devices(hash), endpoint TEXT NOT NULL,
                    keys_json TEXT NOT NULL, challenge TEXT NOT NULL, challenge_hash TEXT NOT NULL,
                    verified INTEGER NOT NULL DEFAULT 0, pending INTEGER NOT NULL DEFAULT 0,
                    generation INTEGER NOT NULL DEFAULT 0, retry_at REAL NOT NULL DEFAULT 0,
                    attempts INTEGER NOT NULL DEFAULT 0, last_sent REAL NOT NULL DEFAULT 0,
                    error TEXT NOT NULL DEFAULT '');
            """)
            if 'round_id' not in {row['name'] for row in conn.execute('PRAGMA table_info(posts)')}:
                conn.execute("ALTER TABLE posts ADD COLUMN round_id TEXT NOT NULL DEFAULT ''")
        os.chmod(self.db, 0o600)

    @contextlib.contextmanager
    def connect(self):
        conn = sqlite3.connect(self.db, timeout=15)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        try:
            with conn:
                yield conn
        finally:
            conn.close()

    def invite(self, slot, name):
        if slot not in (1, 2) or not name.strip() or len(name.strip()) > 60:
            raise ValueError("Use slot 1 or 2 and a name between 1 and 60 characters.")
        code = secrets.token_urlsafe(24)
        with self.connect() as conn:
            conn.execute("INSERT INTO users VALUES (?,?) ON CONFLICT(slot) DO UPDATE SET name=excluded.name",
                         (slot, name.strip()))
            conn.execute("DELETE FROM invites WHERE slot=?", (slot,))
            conn.execute("INSERT INTO invites(hash,slot,expires) VALUES (?,?,?)",
                         (digest(code), slot, int(time.time()) + 86400))
        return code

    def enroll(self, invite, token=None):
        token = token or secrets.token_urlsafe(32)
        with self.connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT * FROM invites WHERE hash=? AND expires>?",
                               (digest(invite), int(time.time()))).fetchone()
            if row is None:
                raise ValueError("Invitation is invalid, expired, or already used.")
            if row["used"] and row["device_hash"] != digest(token):
                raise ValueError("Invitation is invalid, expired, or already used.")
            if row["used"]:
                active = conn.execute("SELECT active FROM devices WHERE hash=?", (digest(token),)).fetchone()
                if not active or not active[0]:
                    raise ValueError("Invitation is invalid, expired, or already used.")
                name = conn.execute("SELECT name FROM users WHERE slot=?", (row["slot"],)).fetchone()[0]
                return {"token": token, "slot": row["slot"], "name": name}
            conn.execute("UPDATE invites SET used=1,device_hash=? WHERE hash=?", (digest(token), digest(invite)))
            conn.execute("UPDATE devices SET active=0 WHERE slot=?", (row["slot"],))
            conn.execute('DELETE FROM push_subscriptions WHERE device_hash IN (SELECT hash FROM devices WHERE slot=? AND active=0)', (row['slot'],))
            conn.execute("INSERT INTO devices(hash,slot,created) VALUES (?,?,?)",
                         (digest(token), row["slot"], int(time.time())))
            name = conn.execute("SELECT name FROM users WHERE slot=?", (row["slot"],)).fetchone()[0]
        return {"token": token, "slot": row["slot"], "name": name}

    def authenticate(self, token):
        if not token or len(token) > 128:
            return None
        with self.connect() as conn:
            row = conn.execute("SELECT d.slot,u.name FROM devices d JOIN users u USING(slot) "
                               "WHERE d.hash=? AND d.active=1", (digest(token),)).fetchone()
        return dict(row) if row else None

    def get_post(self, post_id):
        with self.connect() as conn:
            row = conn.execute("SELECT * FROM posts WHERE id=?", (post_id,)).fetchone()
        return dict(row) if row else None

    @staticmethod
    def public(row):
        import json
        return {**{key: row[key] for key in ("seq", "id", "slot", "received_at", "photo_count", "archived")},
                "metadata": json.loads(row["metadata"])}
