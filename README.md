**Description:** TextImageCleaner is a modern Android utility application built with Kotlin and Jetpack Compose designed to help users manage storage by efficiently deleting media attachments from MMS messages.
Unlike standard SMS apps, this tool aggregates all image and video attachments into a single, chronological grid view, allowing for bulk management without needing to scroll through individual conversation threads. It utilizes Android's RoleManager to temporarily acquire Default SMS Handler permissions to perform deletions safely.

**Key Features:**
• Media Aggregation: Scans and groups all MMS images and videos by month and year.
• Bulk Selection: Select specific items or entire groups for mass deletion.
• Date Range Filtering: Integrated DateRangePicker to identify and remove media within specific timeframes.
• Smart Deletion: Option to delete attachments only (keeping the text message body) or remove empty/expired messages entirely.
• Background Processing: Uses WorkManager to handle large deletion tasks in the background without blocking the UI.

**Tech Stack:**
• Kotlin & Coroutines
• Jetpack Compose (Material3)
• WorkManager
• Coil (Image Loading)
• Accompanist (Permissions)

**WARNING:** This app is very untested and pre-alpha it's very likely you can and will accidentally delete text messages, pictures, and videos from your system level SMS/MMS storage that you don't intent to use at your own RISK.

**TLDR:** I made this app because I was tired of the fact Google Messages doesn't have a way to bulk delete old pictures and videos and there wasn't a good way to do it and after a few months the system level message storage was well over 12GB but I've had it get even bigger than that over time. So this was mostly to solve my own problem but I thought I'd share it.

Requirement to use the app:
To use this app you will have to temporarily set the app as the default SMS app since this is the only way to access the message storage. You will not be able to text while it is deleting the images/videos and sometimes this can take a while. i did make it work in the background with a notification though.

## License

This project is licensed under the AGPL-3.0 License. See the [LICENSE](LICENSE) file for details.

For commercial inquiries or alternative licensing, please contact the project maintainers.
