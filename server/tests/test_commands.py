import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
import urllib.request


class CommandTests(unittest.TestCase):
    def test_server_startup_and_consistent_backup_guard(self):
        with tempfile.TemporaryDirectory() as temp:
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                port = sock.getsockname()[1]
            env = dict(os.environ, TWOFOLD_DATA=temp, TWOFOLD_BIND="127.0.0.1", TWOFOLD_PORT=str(port))
            process = subprocess.Popen([sys.executable, "-m", "twofold.app"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                ready = False
                for _ in range(50):
                    try:
                        with urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz", timeout=1) as response:
                            self.assertEqual(json.load(response), {"status": "ok"})
                            ready = True
                            break
                    except OSError:
                        if process.poll() is not None:
                            break
                        time.sleep(0.1)
                self.assertTrue(ready, "Relay did not start")
                refused = subprocess.run([sys.executable, "-m", "twofold.admin", "backup", str(Path(temp)/"backup.tar.gz")], env=env, capture_output=True)
                self.assertNotEqual(refused.returncode, 0)
                self.assertIn(b"Stop the Twofold server", refused.stderr)
            finally:
                process.terminate()
                process.communicate(timeout=10)
            backup = Path(temp) / "backup.tar.gz"
            done = subprocess.run([sys.executable, "-m", "twofold.admin", "backup", str(backup)], env=env, capture_output=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            with tarfile.open(backup) as archive:
                self.assertIn("twofold.sqlite3", archive.getnames())
            self.assertEqual(backup.stat().st_mode & 0o777, 0o600)
            again = subprocess.run([sys.executable, "-m", "twofold.admin", "backup", str(backup)], env=env, capture_output=True)
            self.assertNotEqual(again.returncode, 0)


if __name__ == "__main__":
    unittest.main()
