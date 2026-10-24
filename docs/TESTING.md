# Testing the alpha

## Automated checks

Tests exercise the Python service and native Java network code. Run them on a
disposable development machine, never against your photo library.

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r server/requirements.txt cryptography
PYTHONPATH=server python3 -m unittest discover -s server/tests -v
```

The 32 server tests cover authentication, invitation retry/reuse, revocation,
raw multipart, private media, SSE, archive retention, pruning and backup. Immich
fixtures exercise actual HTTPS, byte-for-byte JPEG/metadata preservation, XMP,
album membership, partial retry and older upload fields. Shared-round tests cover
locked state/history/originals, blur-only previews, reply/unlock, retakes,
simultaneous uploads, restart, legacy-database migration, layout validation and
membership surviving pruning. ntfy fixtures exercise real HTTPS and RFC 8291
wire decryption, endpoint restrictions, challenge confirmation, revocation,
publish failure and retry. No fixture accesses a real photo library or ntfy account.

`cryptography` is a production dependency for encrypted generic push hints,
not encryption of stored photos. It also creates ephemeral TLS test certificates.

For host checks of the app's actual `Api.java` and `Tokens.java`, use JDK 17 and a
host-only `org.json` jar (tested with 20250517):

```bash
python3 scripts/test_native.py --jdk /path/to/jdk17 --json-jar /path/to/json-20250517.jar
```

They check random tokens, JSON, raw multipart, HTTPS-only URLs, redirect rejection,
and authentication failure reporting. The host Base64 shim and JSON jar are not
included in the app.

Build variants and run lint:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lint
```

Ten Robolectric API 35 Android framework tests run production View, bitmap,
EXIF, queue, notification and RemoteViews code with native graphics. They check
all inset corners/main selection, pixel colors/aspect, unchanged originals,
layout persistence, separate locked caches, reminders/opt-out/queue suppression,
decrypted-push callback filtering/setup backoff, widget clearing, and real UI
controls. They render feed/review screens with synthetic fixture images for
visual review. A test-only Vault shadow supplies fixture credentials instead of
hardware Keystore. These tests do not establish physical camera support,
background IPC between ntfy and Twofold, actual standby delivery or battery use.

The emulator-only runner lives in `src/androidTest`; no fixture CA or test trust
hooks enter the release APK. Build debug/test APKs, install the AOSP Android 35
ATD x86_64 system image, create an isolated AVD, then run
`python3 scripts/test_device.py --help`. The script starts ephemeral HTTPS
relay/Immich fixtures and an emulator. It refuses to run on a physical phone.

See `BUILD-INFO.txt` for checks completed for the supplied APK. Building a test APK
alone does not establish that device integration checks passed.

## First deployment and phone checks

1. Start Docker and verify health, data write access, secret read access, and final
   HTTPS hostnames from the phones and from the server to Immich.
2. Pair both phones. Send one photo, two photos, and a caption with punctuation.
   Check the partner preview and widget.
3. Verify originals, caption/date, and album in Immich. Send another set; confirm
   only the newest per slot remains in Twofold after archival.
4. Stop Immich temporarily and send several moments. Confirm pending originals
   remain. Restore it and use `archive-retry`; confirm every original reaches the
   album before old local sets disappear. Check free disk space.
5. Test offline upload, Wi-Fi/mobile handoffs, reboot, notification denial, and
   direct pause/resume. Check duplicates, camera rotation, and camera behavior.
6. Leave each phone locked overnight in Direct, ntfy (where installed) and Periodic modes. Check
   delays, widget freshness, system battery usage, and vendor background policy.
   Emulator tests do not establish standby battery use. Force-stop requires
   reopening the app.
7. Revoke a test pairing and check access. Test backup and restore into a separate
   disposable data directory before relying on it.

This alpha has no random daily prompt or production battery benchmark. Live
Immich compatibility, Docker operation, GrapheneOS, and the stock phone's
background policy need deployment checks.

For this camera/metadata update:

- Install over the existing APK and confirm pairing survives.
- Deny camera access; confirm choosing a JPEG still works. Then grant access.
- Try front + back. Check the screen says concurrent or sequential accurately.
  Concurrent operation must be tested on real hardware; two capture requests do
  not guarantee identical exposure times. Test manual sequential retry too.
- Review portrait/landscape JPEG orientation, mirrored selfie preview versus
  unmirrored saved photo, field of view, focus, brightness and actual dimensions.
- Switch single-camera facing, retake a pair, cancel mid-capture, background the
  camera, and return. No partial pair should replace the existing draft or send.
- Check camera indicators disappear on pause and another camera app can open.
- GPS starts off. Enable it, grant precise location, wait for a fix outdoors,
  then check map position, accuracy, fix time and stopped location requests.
  Deny/choose approximate permission, disable device location, and capture
  without a fix only through the explicit no-GPS choice. Close the screen and
  verify there are no continuing camera/location requests.
- Check Immich's camera/ISO/exposure, original date with timezone, verified user
  in the description, front/rear tags, and separate original JPEGs.
- Import a JPEG with EXIF/GPS and compare original bytes. Test picker permission
  denial and the metadata-stripping switch. Already stripped metadata cannot be
  restored. Test a repeated identical JPEG to observe Immich deduplication.

The instrumentation runner includes JPEG metadata/pixel-preservation assertions.
It does not establish physical dual-camera behavior. See BUILD-INFO.txt for
which automated checks actually ran.


For the alpha.4 interface, rounds and ntfy update:

- Upgrade both apps and verify pairing, Immich and preserved metadata still work.
- Swap the main/inset photos and try all four corners. Check the recipient and
  a resized widget show the complete preview. Check Immich keeps two originals.
- Post from A. B must see a blurred card/widget and no caption/full metadata.
  Reply from B. Both unlock without an extra reply requirement. Retake before a
  reply and send simultaneously. Old pre-update images should stay visible.
- Wait for the initial turn reminder and one follow-up. Reply before the follow-up,
  disable each reminder setting, deny notifications, and test an offline queued
  reply. Opening reminders should lead to capture or the unfinished draft.
- Try ntfy on one phone and Direct on the other. Verify the encrypted setup round
  trip and send with the app backgrounded. Rotate/reconnect the endpoint, reboot,
  switch away from ntfy and simulate an outage. No photo upload should fail merely
  because the push provider is down. Periodic checks should recover state.
- Check behavior under your Cloudflared/proxy setup and ntfy access rules. The
  included TLS publisher fixture is not an end-to-end ntfy Android acceptance test.
