# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-07-09

First **stable** release after a full refactor with **Grok 4.5** / **Grok Build** (xAI). Supersedes the pre-alpha Gemini-era prototype.

### Added

- In-app **Settings** (default SMS controls, backup album name, optional Contacts, about)
- **Date-range delete** with Material3 range picker; only months that contain media are selectable
- Selection action bar, confirm dialogs, WorkManager progress overlay (product loop restored)
- Lazy **info panel** (conversation peers + message body on demand)
- Optional **READ_CONTACTS** for contact names (not required for core features)
- Predictive back for Settings overlay
- Coil memory/disk cache and loading placeholders on the media grid
- DataStore-backed backup folder preference
- Instrumented tests seeding MMS images/videos/text (DeletionWorker, load, safety, restore)
- JVM unit tests for folder sanitize and date-range logic
- `scripts/run-device-tests.sh` for ADB verification
- CI: unit tests + debug APK artifact upload
- SECURITY.md and expanded safety documentation

### Changed

- Fast MMS browse scan (no per-part body/size I/O on load)
- Trash UI shows **original message date/time**; message body always visible when present
- Trash list ordered by original date
- Cleaner excludes URIs already in the trash database (calendar cannot re-select them)
- Deletion semantics: full MMS delete only when every media part is selected (Option A)
- Version **1.0.0** / versionCode **100**
- README fully rewritten for stable release

### Fixed

- Compilation errors from incomplete ViewModel/UI split
- Media type filter recomposition
- Worker cancel, double completion notification, video backup path
- JVM property setter clashes (`setCurrentTab` / `setMediaTypeFilter`)
- Experimental Compose / Permissions opt-ins

### Security

- Multi-attachment sibling protection in trash mode
- Control-message safety covered by instrumented tests
- Backup/extraction rules exclude trash and Room databases
- Worker rejects URI list files outside app `cacheDir`

## [0.x] - pre-1.0.0

Unstable / pre-alpha builds (historical). Treat as unsupported.

[1.0.0]: https://github.com/LinkofHyrule89/TextImageCleaner/releases/tag/v1.0.0
