# Alpha.4 downloads

The APK is the original signed alpha.4 release (versionCode 3). Its signing
certificate and build checks are documented in ../BUILD-INFO.txt. Install over
the previous Twofold APK to retain pairing.

The server update ZIP contains server/ plus setup/license files and the separate
TrueNAS example. It does not replace compose.yaml or include data/API keys.

The source ZIP includes the corresponding Android/server source, tests, build
wrapper and public documentation. The two public ZIPs use generic deployment
examples; their implementation files match the delivered alpha.4 source.

Verify downloads with `sha256sum -c SHA256SUMS`. Keep private signing keys outside
this repository. No signing key or password is included in any download.
