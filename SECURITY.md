# Security and privacy

## What this app can do

With the **default SMS app** role and SMS/media permissions, TextImageCleaner can:

- Read MMS parts (images/videos) from the system Telephony provider  
- Delete selected MMS parts or entire MMS rows (when you confirm)  
- Copy media into app-private trash and optionally into the public Gallery  
- Optionally read Contacts to resolve display names (info panel / Settings only)

## What it does not do

- Network upload of your messages or media (no analytics backend in 1.0.0)  
- Delete SMS/MMS outside the explicit URI list for a job  
- Require Contacts permission for scanning or deleting  
- Restore media **into** SMS/MMS (restore is Gallery-only)

## Deletion safety model

1. **Attachments only** — `ContentResolver.delete` on each selected part URI. Parent message and text parts remain.  
2. **Trash (media + message option)** — copy stream to private storage + Room row, then:
   - If **not** all media parts of that MMS were selected → delete only those parts.  
   - If **all** media parts were selected and trashed → delete the MMS message once.  
3. **Date range** — builds a frozen list of matching URIs from the current cleaner list (minus trash), then runs the same worker.

Always read the confirm dialog before proceeding.

## Data at rest

| Data | Location | Cloud backup |
|------|----------|--------------|
| Trash files | `files/trash/` | Excluded |
| Trash metadata / message bodies | Room DB | Excluded |
| Settings (backup folder name) | DataStore | Default rules |

## Reporting issues

Open a GitHub issue with **no real phone numbers or message content** if possible. Describe steps and app version (`1.0.0`).

## Default SMS risk

While this app holds `ROLE_SMS`, incoming SMS/MMS are **not** handled like a full messenger. Switch the default SMS app back after cleaning.
