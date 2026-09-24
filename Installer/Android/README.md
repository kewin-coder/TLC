# TLC Android installer/update app

The Android installer must detect existing TLC data and show a backup gate before an update.

Required choices:
- Create Backup
- I Already Have a Backup
- Cancel

The APK must preserve the existing encryption key across normal updates, never silently delete the SQLite database, and provide an explicit restore flow for TLC_Backup.tlcb.

Do not put a real TLC encryption key inside the APK.

This repository currently contains the installer contract, not a complete Android Studio/Gradle project or signed APK binary. The APK must be built and signed from source on a build machine or CI runner.
