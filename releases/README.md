# Releases

**Download page:** https://github.com/LinkofHyrule89/TextImageCleaner/releases

### v1.0.0

Only **one** installable file is published:

| Asset | Notes |
|-------|--------|
| `TextImageCleaner-1.0.0.apk` | **Release-signed** production key |
| `TextImageCleaner-1.0.0.apk.sha256` | Checksum |

There is no separate “debug” or “release”-named APK on the release page — that avoided confusion.

### Local builds

```bash
./gradlew assembleDebug     # debug key (dev only)
./gradlew assembleRelease   # needs keystore.properties or KEYSTORE_* env
```

APK binaries are gitignored; use GitHub Releases or CI artifacts.
