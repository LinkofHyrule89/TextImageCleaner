# Releases

Installable APKs for TextImageCleaner are published on **GitHub Releases**:

https://github.com/LinkofHyrule89/TextImageCleaner/releases

### Local build output

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Copy and rename if you need a fixed filename:

```bash
mkdir -p releases
cp app/build/outputs/apk/debug/app-debug.apk releases/TextImageCleaner-1.0.0.apk
```

Large APK binaries are **not** committed to git (see root `.gitignore`) to keep the repository small. Use Releases or CI artifacts instead.
