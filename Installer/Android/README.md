# TLC Android installer/update app

## Update gate

When an installed TLC version is being replaced, the Android app must show a blocking dialog:

**Please create a backup copy before continuing.**

Buttons:
- **Create Backup** — create/verify a TLC_Backup.tlcb in user-selected storage.
- **I Already Have a Backup** — let the user select an existing backup and validate it.
- **Cancel Update** — leave the installed version untouched.

A normal Android app update must preserve the app's persistent TLC data and therefore preserve the installation encryption key. It must never generate a new key merely because the APK version changed.

## Restore

Provide a separate explicit **Restore TLC Backup** flow. Never silently overwrite existing database/key files. Restore should validate the backup before changing persistent data and should require an explicit replacement confirmation if data already exists.

## Release requirements

- Do not embed a real TLC encryption key in the APK.
- Keep persistent data outside replaceable application resources.
- Build a real Android Studio/Gradle project before producing the release APK.
- Sign release APKs with a release keystore; never commit the keystore or passwords.

This repository currently contains the installer contract, not a signed APK binary.
