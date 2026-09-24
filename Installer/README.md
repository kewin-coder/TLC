# TLC Installers

TLC has two installer targets:

- Windows: Installer/Windows/install.ps1 — update/installation gate and backup workflow.
- Android: Installer/Android/ — Android installer/update project source.

The installer must never silently replace TLC's database or encryption key.

## Update rule

1. Show “Please create a backup copy before continuing.”
2. Offer Create Backup, I Already Have a Backup, and Cancel.
3. Do not continue until the user confirms a backup exists.
4. Install new application files without deleting tlc-data/encryption.key or the SQLite database.
5. Restore only into a fresh data location or after explicit replacement confirmation.

The backup file is TLC_Backup.tlcb and contains the SQLite database plus its matching encryption key.

The repository contains installer source/configuration, not signed distributable APK/EXE binaries. Release binaries should be generated and signed from this source on a build machine or CI runner.
