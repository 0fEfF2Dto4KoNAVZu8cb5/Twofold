"""Local administrator commands; there is deliberately no web admin endpoint."""
import argparse
import fcntl
import json
import os
import sqlite3
import tarfile
import tempfile
from pathlib import Path
from .storage import Store


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", default=os.environ.get("TWOFOLD_DATA", "/data"))
    commands = parser.add_subparsers(dest="command", required=True)
    invite = commands.add_parser("invite", help="Generate a single-use, 24-hour phone invitation")
    invite.add_argument("slot", type=int, choices=[1, 2])
    invite.add_argument("name")
    revoke = commands.add_parser("revoke", help="Disconnect a lost/replaced phone")
    revoke.add_argument("slot", type=int, choices=[1, 2])
    commands.add_parser("status")
    commands.add_parser("archive-retry", help="Retry pending Immich archival within 30 seconds")
    backup = commands.add_parser("backup", help="Consistent database and JPEG/JSON archive backup")
    backup.add_argument("output")
    args = parser.parse_args()
    store = Store(args.data)
    if args.command == "invite":
        print("One-time invitation (expires in 24 hours):")
        print(store.invite(args.slot, args.name))
    elif args.command == "revoke":
        with store.connect() as conn:
            conn.execute("UPDATE devices SET active=0 WHERE slot=?", (args.slot,))
            conn.execute("DELETE FROM invites WHERE slot=?", (args.slot,))
        print("Phone access revoked. Already downloaded content cannot be recalled.")
    elif args.command == "status":
        with store.connect() as conn:
            rows = conn.execute("SELECT u.slot,u.name,COUNT(p.seq) AS photos FROM users u "
                                "LEFT JOIN posts p USING(slot) GROUP BY u.slot").fetchall()
        print(json.dumps([dict(row) for row in rows], indent=2))
    elif args.command == "archive-retry":
        with store.connect() as conn:
            conn.execute("UPDATE posts SET archive_retry_at=0,archive_attempts=0 WHERE archived=0")
        print("Pending archival will retry within 30 seconds.")
    else:
        process_lock = (store.root / ".server.lock").open("a")
        try:
            fcntl.flock(process_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.error("Stop the Twofold server before making a consistent backup.")
        output = Path(args.output).resolve()
        if output.exists():
            parser.error("Choose a new backup filename; existing backups are never overwritten.")
        with tempfile.TemporaryDirectory() as temp:
            copy_db = Path(temp) / "twofold.sqlite3"
            with store.connect() as source:
                destination = sqlite3.connect(copy_db)
                source.backup(destination)
                rels = [row[0] for row in destination.execute("SELECT archive_rel FROM posts ORDER BY seq")]
                destination.close()
            with tarfile.open(output, "x:gz") as archive:
                archive.add(copy_db, arcname="twofold.sqlite3")
                for rel in rels:
                    archive.add(store.root / rel, arcname=rel)
        print(f"JPEG/JSON backup created: {output}")


if __name__ == "__main__":
    main()
