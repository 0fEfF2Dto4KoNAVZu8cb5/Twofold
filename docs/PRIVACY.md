# Privacy and data handling

Twofold is AGPL-3.0-or-later, with FOSS dependencies. The Android app has no
analytics, advertising, FCM, Google Play services, contacts permission,
background location access, or broad photo-library access. It contacts your configured
Twofold HTTPS endpoint. Your server contacts your configured Immich endpoint and,
only if enabled, your explicitly allowed ntfy HTTPS origin. The optional ntfy
Android app connects to its configured server as a separate application.
Capture uses Twofold’s built-in Camera2 screen; JPEG selection uses the system
document picker. No microphone permission is requested.

## Transport and storage

HTTPS protects credentials, photos, and metadata in transit. The app and Immich
uploader validate TLS certificates and reject automatic redirects. An explicit
private-numeric-address HTTP option is available for same-NAS Immich uploads. Use a trusted
certificate on the final hostname, including with a VPN. The included service
binds HTTP to host loopback for your HTTPS reverse proxy. If that final hop
crosses a network, secure it too.

Photos are ordinary JPEGs and metadata is ordinary JSON on the server. There is
no application-level photo encryption, shared photo key, or encrypted archive
format. Your administrator, server root, and anyone able to read the data directory
or its backups can read the photos. Immich stores readable originals under its
own access controls. Restrict host access and backup access accordingly.

The app keeps raw pending JPEGs and latest previews in its private app directory.
The login token/configuration is wrapped with Android Keystore; this protects
credentials and does not add photo encryption. System cloud backup and device
transfer are excluded. App screenshots and recent-task previews are blocked.
The widget intentionally exposes a partner preview to your launcher and anyone
looking at your home screen. A locked round displays a blurred preview or a
placeholder. The widget clears an old full preview before showing locked state.
Previously downloaded content cannot be recalled.

The built-in camera writes new JPEGs into private cache and adds camera metadata
and Twofold context without recompressing pixels. The camera closes on pause,
and no other camera app receives the photos. Drafts are deleted after a successful
queue operation or explicit discard; incomplete/canceled captures are deleted.
Abandoned captures are cleaned on later launches. Deletion is not secure erase
on flash; normal phone storage encryption and screen lock still apply.

Full metadata preservation is the default for this update. After native-camera tagging, or for an imported JPEG,
bytes are copied unchanged to the server and Immich, including GPS location, capture dates, camera/lens/exposure
information, XMP, MakerNote, and original comments when present. Your partner,
server administrator, Immich, and anyone allowed to read those originals can
read this metadata. The app's Settings switch can instead strip GPS, XMP and
MakerNote from newly sent moments. That mode also replaces EXIF UserComment with
a random moment identifier. Original pixels are not recompressed in either mode.

Native camera geotagging is optional and off initially. Turning it on requests
precise foreground location. Twofold uses Android's GPS provider while its camera
screen is open, stops after a fresh fix or a 45-second search, and can reuse a fix
up to two minutes old. An expired fix is refreshed only while that screen remains
open. Closing/pausing the camera stops location requests. No background location
permission, Google Play services library, network-location-provider request, or
custom geocoding service is used. Your OS still controls satellite assistance.
Without a fix, you can wait or explicitly capture without GPS. GPS coordinates,
accuracy and fix time are embedded in tagged JPEGs and shared with the photos.

The optional `ACCESS_MEDIA_LOCATION` permission separately reads saved GPS from
existing photos selected through the system picker. It does not enable live
geotagging. A provider or denied permission may redact existing location before
Twofold receives the image. Missing metadata is not invented. Native captures
record actual Camera2 results where supplied, device make/model, timestamp with
timezone/subseconds, your enrolled name, camera facing/ID, capture mode and a
random pair identifier. No hardware serial number or advertising ID is read.

The server also records a small readable EXIF/GPS summary in the moment JSON and
in an XMP sidecar. The sidecar identifies the verified Twofold user/slot, caption,
moment time, original time source and photo index. The user is included in the
Immich description. Native photos also receive front/rear and author XMP tags.
Embedded photo metadata is client-supplied; the sidecar author comes from the
authenticated Twofold account. Immich displays only some XMP fields; the complete context
remains in its sidecar, with full original camera metadata retained in the JPEG.
Identical original files can be deduplicated by Immich and reuse the first asset's
sidecar. No external location service or reverse-geocoding API is called by
Twofold; Immich may process metadata according to its own configuration.

## Access and server visibility

The server sees images, captions, capture dates, names, network addresses/timing,
file sizes, token hashes, moment identifiers, and archive status. Access logging
is disabled in the service; configure reverse-proxy logs separately.

There are two account slots and one active token per slot. Pairing uses expiring
single-use invitations, with same-device retry after a lost response. Possession
of a valid invitation authorizes pairing, so keep it private. There is no public
registration or public photo URL. State, media, metadata, and events require an
active bearer token. The health endpoint has no personal information. Tokens are
stored as hashes on the server.

Revocation prevents future access and closes the existing event stream by its
next heartbeat. Replacement pairing revokes the previous token. Neither recalls
content already downloaded to a lost phone. Your server is trusted to serve the
pair's content; there is no protection from a compromised server or administrator.

## Immich and retention

Only the server holds the Immich key, read from a private secret file at startup.
Phones never receive it. Grant `asset.upload` and `albumAsset.create`, plus
`album.create` if automatic album creation is enabled. These are Immich permissions,
not an app-enforced album-only authorization boundary. A dedicated account limits
the exposed account data.

The server uploads originals with caption/date XMP and records each Immich asset
ID. It checks confirmation for every album-add result, including duplicates.
Interrupted uploads/additions retry without intentionally discarding originals.
Only a confirmed, superseded set is pruned. The newest set per person stays on
the server for the app and widget.

During outages, older pending sets remain and retry with increasing delay. There
is no server photo-count limit that silently drops pending originals. Monitor
free disk space. Until the server accepts an upload, the phone retains its copy,
with a 64-moment queue limit. Afterwards the server owns archival retries. The
phone normally keeps latest previews and unsent moments. Tiny receipt records
remain in SQLite for retries; they contain identifiers and receipt times without
photos or captions. Round membership also retains the round ID, slot, post ID
and server receipt time to preserve unlock state after pruning.

Immich acceptance is not a separate backup. Back up Immich independently and keep
private backups of pending server data and configuration. If an archived asset
is later deleted from Immich, Twofold cannot restore superseded originals it has
already pruned.


## Shared rounds and notifications

Share-to-reveal is enabled by default for new rounds and can be changed by either
paired account. Server-side membership, based on receipt order rather than the
phone clock, controls access to partner originals and metadata. Until a reply,
Twofold exposes a strongly blurred preview, author name, IDs, count and receipt
time. Blur can reveal coarse colors. Old moments remain visible, and imported
JPEGs count as replies. This is a consensual app feature; administrators and
Immich access remain outside the rule, and downloaded images cannot be recalled.

Reminders use generic local notification text with a private lock-screen version.
No photo or caption is embedded in a notification. There is an initial turn
reminder and, if enabled, one inexact follow-up around an hour later. No continuous
reminder polling, exact-alarm permission or random daily prompt is used. The
normal periodic sync remains as recovery. You can disable reminders or the
follow-up independently in Settings.

## Optional ntfy delivery

Each phone can choose Direct, ntfy, or Periodic. ntfy requires the official ntfy
Android app configured for the same HTTPS server allowed in TWOFOLD_NTFY_URL.
The maintained FOSS UnifiedPush connector handles distributor authentication and
Web Push decryption. No embedded FCM distributor, Google account or Google Play
services library is included. The connector's Tink cryptography dependency is
FOSS and does not call Google services.

Twofold publishes RFC 8291 encrypted generic sync hints, plus a random challenge
during registration. No names, photos, captions, coordinates, moment IDs, server
login token, or Immich key enter the push payload. The phone then fetches state
and previews from Twofold over authenticated HTTPS. A confirmation round trip
is required before a channel receives updates. The server pins endpoints to the
administrator's configured HTTPS origin and UnifiedPush topic prefix, rejects
redirects, and retries failed signals without failing a photo upload.

ntfy and any proxy serving it can observe topic capability URLs, network
addresses, size and timing. They cannot read the encrypted payload with only the
public subscription data. Your trusted Twofold server knows the endpoint and
Web Push key material used to send signals. Protect its database and backups;
do not post endpoint URLs in logs or support messages. Unlinking or replacing a
paired device removes its Twofold push registration. Revocation cannot erase
previously cached encrypted signals from a provider.

The optional ntfy app has its own battery, permission and server settings. If you
use a third-party ntfy service, its privacy policy and network visibility apply.
Self-hosting ntfy preserves your existing infrastructure. Leaving TWOFOLD_NTFY_URL
empty prevents the Twofold publisher from making outgoing ntfy requests.
