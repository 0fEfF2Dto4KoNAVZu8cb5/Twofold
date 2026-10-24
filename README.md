# Twofold

A small, self-hosted Android photo app for two people. Send a photo or two-photo
set and see your partner's latest moment on your home screen. Immich holds your
permanent album; Twofold normally retains only the latest set per person.

**0.1.0-alpha.4** adds a simpler photo-first interface, shared rounds, reminders,
and optional encrypted ntfy delivery. It includes a native Android app, Docker
service, and automatic server-to-Immich archival. This remains an alpha release.
Real-phone camera behavior, standby delivery, and battery usage need testing.
Docker execution and your installed Immich version need deployment checks.
Random daily BeReal prompts are not implemented; send a moment whenever you want.

## Included

- Native Java Android app, Android 8+ (API 26), targeting Android 15 / API 35.
- One clear capture action, a partner-photo feed and home-screen widget.
- Choose either photo as the main image and place the inset in any corner.
- Shared rounds: share yours to reveal theirs, with optional turn reminders.
- Optional second photo and a 500-character caption.
- Direct HTTPS events, optional ntfy through UnifiedPush, or periodic-only checks.
  All modes retain periodic recovery at 15/30/60/120-minute intervals. Android
  can delay background work; no fixed delivery latency is promised.
- HTTPS transport with certificate validation; ordinary JPEG and JSON files on
  your server. No shared photo key or application-level photo encryption.
- Automatic Immich original upload, caption/date XMP, and album addition.
- Upload retries and retention until every original is confirmed in the album.
- One Python process and SQLite. No Redis, Postgres, or GPU required by Twofold.
  Your existing Immich installation runs separately.
- AGPL-3.0-or-later, FOSS dependencies, no analytics, FCM, advertising SDKs,
  background location tracking, or Google Play services dependency.

Read [privacy details](docs/PRIVACY.md) and [testing notes](docs/TESTING.md).

## Downloads

- [Android APK — 0.1.0-alpha.4](https://github.com/0fEfF2Dto4KoNAVZu8cb5/Twofold/raw/refs/heads/main/downloads/Twofold-0.1.0-alpha.4.apk)
- [Docker server update ZIP — 0.1.0-alpha.4](https://github.com/0fEfF2Dto4KoNAVZu8cb5/Twofold/raw/refs/heads/main/downloads/Twofold-server-0.1.0-alpha.4.zip)
- [Full source ZIP — 0.1.0-alpha.4](https://github.com/0fEfF2Dto4KoNAVZu8cb5/Twofold/raw/refs/heads/main/downloads/Twofold-0.1.0-alpha.4-source.zip)

Checksums are in [downloads/SHA256SUMS](downloads/SHA256SUMS). Install the APK over
the existing app to keep pairing. The server update leaves your working
`compose.yaml`, data and Immich key in place; read [UPDATE.txt](UPDATE.txt).

For a fresh installation, use the generic `compose.yaml` and `.env.example` below.
For TrueNAS, `compose.twofold-truenas-alpha4.yaml` is a reference example using
`192.168.1.10` and `/mnt/tank/apps/twofold` in the documentation. Substitute your
own NAS address and dataset path. Existing installations should keep their
working Compose file and add `TWOFOLD_NTFY_URL` if desired.

The app, server, tests, build wrapper, license notices, privacy notes and setup
guides are all included in this repository. Credentials, pairing databases,
photo archives and private signing keys belong outside Git.


## 1. Configure the server and Immich

Requirements: Docker Engine with Compose, an HTTPS reverse proxy with a valid
certificate, and disk space for the latest sets and pending originals. Both
phones need access to Twofold; only the server needs access to Immich. A private
VPN works if both phones have a route through it.

On the server, in this directory:

```bash
cp .env.example .env
sudo install -d -m 0700 -o 10001 -g 10001 data
sudo install -d -m 0700 secrets
sudo touch secrets/immich_api_key
sudo chmod 0600 secrets/immich_api_key
```

In Immich, create an API key with `asset.upload` and `albumAsset.create`. To let
Twofold create its album, also grant `album.create`. A dedicated Immich account
keeps this archive separate. Use its album, or one it can add assets to.

Edit `.env` with your final Immich HTTPS URL. Set `IMMICH_ALBUM_ID` to an existing
album UUID, or leave it blank to create an album named `Twofold` once. The created
ID is saved in SQLite so restarting does not create another album. Put only the
API key in the secret file:

```bash
sudoedit secrets/immich_api_key
sudo chown 10001:10001 secrets/immich_api_key
sudo chmod 0400 secrets/immich_api_key
docker compose up -d --build
docker compose ps
curl --fail http://127.0.0.1:8088/healthz
```

The service runs as UID 10001. Local Compose file secrets preserve host file
permissions, so that UID must be able to read the file. The API key is not put
in `.env`, the phone app, or source control. An empty secret file allows sharing
without Immich; all unarchived originals then remain on the server. Configure
archival before extended use to avoid accumulating files.

The worker uses `POST /api/assets` with `sidecarData`, then
`PUT /api/albums/{id}/assets`. It verifies every album-add result, accepts duplicate
assets/membership, and saves partial progress for retries. It uses modern upload
fields first, retrying a validation failure with older device fields for Immich
2.x compatibility. Verify your installed Immich version during deployment.

## 2. Put Twofold behind HTTPS

Compose binds only to host loopback on port 8088. Put it behind your existing
HTTPS reverse proxy. Phones validate certificates and reject redirects; use the
final canonical URL.

Example Nginx location inside an existing TLS virtual host:

```nginx
location / {
    proxy_pass http://127.0.0.1:8088;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_request_buffering off;
    proxy_read_timeout 300s;
    client_max_body_size 36m;
}
```

Disable SSE buffering and use a timeout longer than the 120-second heartbeat.
If the proxy is in Docker, connect it through a shared Docker network to service
port 8080; its own loopback cannot reach the host service. TLS protects
phone-to-proxy traffic; this example's local proxy-to-service hop uses loopback.
Secure that hop too if it crosses another machine or untrusted network.
Server-to-Immich transfers default to HTTPS. For the same NAS, explicitly enable
`IMMICH_ALLOW_LOCAL_HTTP=true` to use a numeric private address over HTTP. The
phone app always requires HTTPS; your existing Cloudflare Tunnel can provide it.

## 3. Pair the phones

Generate the first phone's invitation:

```bash
docker compose exec twofold python -m twofold.admin invite 1 "Person One"
```

Install the APK and enter the Twofold HTTPS address and invitation. No Google
account, push-provider account, or shared encryption key is needed.

Generate the second invitation; replace `Partner` with the desired name:

```bash
docker compose exec twofold python -m twofold.admin invite 2 "Person Two"
```

Enter that invitation on the second phone. Invitations expire after 24 hours and
are single use, with same-device retry support after a lost response. A new
invitation replaces unused invitations for that slot. Successful pairing revokes
the slot's previous device. Keep invitations private.

## 4. Share moments and choose delivery

Tap **Take a moment**, capture front + back, then review. **Swap** chooses the main
photo. The four corner buttons move the inset. Add an optional note and tap
**Share moment**. Both original JPEGs stay separate and unchanged; layout is
recorded in the moment JSON/XMP and used for the combined app/widget preview.

The first person to post starts a shared round. Their partner sees a blurred
preview and gets a **Your turn** reminder. Once the partner posts, both sets
unlock. Retakes before a reply stay in that round. After both have posted, the
next upload starts a new round. Imported JPEGs also count; this is a sharing
ritual, without a capture-freshness attestation. Pre-update moments stay visible.

Settings contains a shared **Share to reveal** switch that either person can
change. The server blocks the locked original files and metadata in Twofold;
Immich and the server administrator can still access the archive. Blurred
previews expose coarse colors. This feature is not an encryption boundary.

Turn reminders are optional per phone. One additional, optional nudge is scheduled
about an hour later if you still have not replied. Replying cancels it. Android
standby and network availability can delay either reminder. There is no exact
alarm, fixed daily prompt, streak counter or public social feed.

### Direct, ntfy and periodic checks

In Settings, grant notifications, allow background delivery for direct mode, and
add the partner-photo widget. Some stock Android phones also need their vendor's
auto-start/background setting adjusted. Test this on her phone.

Direct mode uses one HTTPS event connection and an Android `specialUse`
foreground service. It has a quiet ongoing notification with a pause action, no
idle CPU wake lock, bounded wake locks for sync, and reconnect delay with jitter.
The default also schedules hourly recovery. ntfy mode uses the connection in
your existing ntfy Android app, with no Twofold ongoing connection. Each phone
chooses independently, so your partner can use Direct without installing ntfy.
See [NTFY-SETUP.txt](NTFY-SETUP.txt) for the optional server setting and phone setup.
Encrypted ntfy messages contain only a generic sync hint or registration challenge.
Photos, captions, GPS and names travel directly through your Twofold HTTPS server.

Periodic-only mode avoids the ongoing
connection but updates can be substantially delayed in standby. Force-stopping
prevents background delivery until the app is reopened. No fixed battery usage
or instant delivery guarantee is claimed.

### Camera and full metadata

The built-in camera has one shutter for **front + back**. On Android 11+ it
checks the phone's advertised concurrent-camera pairs and verifies the stream
configuration before opening them. Both cameras open before either session is
configured. Separate JPEG capture requests are submitted together; exposure is
not hardware-synchronized. Unsupported phones use a clearly labeled sequential
pair (rear, then front). A failed concurrent setup offers a sequential retry.
One-photo capture and selecting existing JPEGs remain available.

Concurrent mode uses the platform's conservative stream limits: a preview up to
1280 × 720 and a JPEG up to 1920 × 1440 per camera. Single/sequential capture
chooses a supported JPEG up to 4096 × 3072. Actual sizes depend on the phone.
The camera screen shows both previews; review the pair before sending. Camera
access is requested at runtime. No microphone permission is requested.

New camera JPEGs are tagged without recompressing their pixels: timestamp with
subseconds/timezone, phone make/model, author, camera facing/ID, capture mode and
pair identifier. Exposure, ISO, aperture and focal length use actual Camera2
results when available. Driver EXIF is retained where supported by AndroidX;
proprietary camera processing and every vendor MakerNote field are not guaranteed.
The camera closes whenever its screen pauses. Image quality, orientation and
concurrent support still require testing on each real phone.

**GPS is optional and starts off.** Enable the camera's location switch and grant
precise location to add satellite GPS coordinates, fix time and accuracy. Twofold
requests the Android GPS provider only while its camera screen is open, stops
after a fresh fix, and limits a search to 45 seconds. It can reuse a GPS fix up
to two minutes old; it refreshes an old fix only while that screen remains open.
It does not request the network location provider or background location access.
Android's satellite assistance behavior remains controlled by your OS settings.
If GPS is unavailable, the camera asks before capturing without location.

**Full metadata is enabled by default in this update.** After camera tagging, or
when choosing an existing JPEG, original bytes are copied unchanged through the
outbox and server to Immich. Existing EXIF, GPS, XMP, MakerNote and comments remain
in those bytes. In Settings, **Preserve full photo metadata** controls newly sent
moments. Turning it off removes GPS/XMP/MakerNote and replaces UserComment with
a moment identifier; native-camera role tags are then omitted from the sidecar.
Previews remain downscaled JPEGs. The picker accepts JPEGs up to 15 MiB/60 MP.

When importing photos from another camera app, that camera must have saved the
metadata. GrapheneOS Camera normally strips most EXIF; change its More Settings
and geotagging settings if desired. Twofold's built-in camera is independent of
those settings. On Android 10+, the picker can request `ACCESS_MEDIA_LOCATION`
to read already-saved GPS that Android may otherwise redact. Denying it still
allows selecting photos. Missing source metadata cannot be reconstructed.

The server reads an EXIF/GPS summary into `metadata.json` and supplies a correctly
named `.jpg.xmp` sidecar to Immich. This contains the verified Twofold user, caption,
moment ID, photo index, camera/GPS summary and a full JSON record of the moment.
The user is also put in the description so it is visible in Immich. Native camera
photos receive digiKam XMP tags for Twofold, front/rear, and the verified user.
An original
EXIF/XMP date is kept in preference to the time the photo was shared; the moment
time is used when no source capture date exists. Full source EXIF remains in the
JPEG even if Immich does not display every field.

Identical original files use Immich's normal checksum deduplication; selecting
the exact same JPEG again can reuse an existing asset and its first sidecar.
New camera captures naturally have distinct original bytes. The optional metadata
stripping mode retains the earlier moment-ID comment behavior.

## Storage and retention

Files are readable without a special recovery tool:

```text
data/archive/YYYY/MM/DD/slot/moment-uuid/
    photo1.jpg
    photo1.jpg.xmp
    photo2.jpg       (when present)
    photo2.jpg.xmp    (when present)
    preview.jpg
    preview-locked.jpg   (strongly blurred, metadata-free)
    metadata.json
    record.json
```

The date is the server receipt date in UTC. JSON includes the capture timestamp,
caption, verified author, moment identifier, image list, and EXIF/GPS summaries. SQLite holds pairing, latest
state, shared round membership, optional push subscriptions, and archive progress. File contents and directory entries are flushed
before an upload receipt is committed. The phone removes its outbox copy only
after the server acknowledges receipt.

After Immich confirms every original and album membership, a superseded set can
be deleted. The latest set for each person remains available even if Immich goes
offline later. During an outage, pending sets are intentionally retained. The
phone keeps pending uploads and latest previews, without a permanent history.
It accepts up to 64 pending moments. Tiny receipts and round membership stay in SQLite to recognize
retries and preserve unlock state after a set has been pruned. These contain
identifiers, slots and receipt times; they do not retain photos or captions.

## Maintenance and backup

```bash
docker compose exec twofold python -m twofold.admin status
docker compose exec twofold python -m twofold.admin revoke 2
```

Revocation rejects new authenticated requests immediately and closes the event
connection by its next heartbeat. Pair a replacement with a fresh invitation.
For new Immich settings or a rotated key, recreate the service to reload them,
then reset delayed archive attempts if needed:

```bash
docker compose up -d --force-recreate twofold
docker compose exec twofold python -m twofold.admin archive-retry
```

Retries increase to a maximum six-hour delay; `archive-retry` makes pending items
eligible within about 30 seconds.

Stop the service for a consistent backup of its database and retained JPEG/JSON:

```bash
docker compose stop twofold
docker compose run --rm twofold python -m twofold.admin backup /data/twofold-backup.tar.gz
docker compose up -d twofold
```

Choose a new filename each time; backup refuses to overwrite or run while the
server holds its data lock. It excludes the Immich API secret. Back up `.env` and
that secret privately. Back up Immich separately: it is the permanent archive.
Restore into an empty `data` directory while stopped, preserve ownership for UID
10001, then start the service. Backups contain readable photos and token hashes;
give them the same access protection as the originals.

## Build from source

Install JDK 17 and Android SDK platform 35 / build-tools 35.0.0. Import `android/`
into Android Studio, or run:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug lint
./gradlew assembleRelease
```

Set `ANDROID_HOME` or create `android/local.properties` containing
`sdk.dir=/your/android/sdk`. The source excludes machine paths and signing
secrets. Release builds are unsigned. Use `scripts/sign-release.sh` or Android
Studio to sign with your own key; keep it for future updates. The supplied alpha
APK's signing identity and validation are in `BUILD-INFO.txt`. The separate private
signing backup belongs only to you; do not publish it.

## Upgrade the deployed TrueNAS server

See `UPDATE.txt` in the server-only update ZIP. Replace only `server/` and rebuild
using the existing `ix-twofold` project. Keep your working Compose configuration,
port 10001, data directory and API key. Install the same-signed new APK over the
existing app on both phones to preserve pairing. Startup performs an additive SQLite migration for rounds and optional push
subscriptions. Take a stopped-service backup or dataset snapshot first. No extra
Immich API permission is required. Changes apply to new moments. Previously
stripped metadata is recoverable only from an original that still contains it.

Optional ntfy uses `TWOFOLD_NTFY_URL` (an HTTPS origin without a topic). Leaving it
empty disables outgoing push publishing. Existing direct delivery keeps working.
The supplied `compose.twofold-truenas-alpha4.yaml` shows port 10001, UID 10001,
a read-only filesystem and file secrets. Its NAS address is an example; keep
your working address and Immich settings when upgrading.
