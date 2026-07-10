# Releases

Installable APKs are published on **GitHub Releases**:

https://github.com/LinkofHyrule89/TextImageCleaner/releases

| Asset | Signing |
|-------|---------|
| `TextImageCleaner-release.apk` | **Release** keystore (CI secrets / local maintainer key) |
| `TextImageCleaner-1.0.0.apk` (legacy) | Debug (early sideload) |

### Local builds

```bash
./gradlew assembleDebug     # debug-signed
./gradlew assembleRelease   # needs keystore.properties or KEYSTORE_* env (see README)
```

Large APKs are gitignored. Use Releases or CI artifacts.
