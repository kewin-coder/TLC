# TLC security policy

TLC is currently a development prototype. Do not expose an unreviewed TLC server directly to the public internet or use it for sensitive real-world communication.

## Protect these files and values

- `tlc-data/encryption.key`
- `tlc.db` and SQLite WAL/SHM files
- `*.tlcb` backups
- `TLC_ADMIN_SECRET`
- `TLC_ENCRYPTION_KEY` when used for compatibility
- Android release keystores and Windows signing credentials

The TLC backup contains the database and the matching encryption key. Anyone who obtains that backup can potentially decrypt the backed-up message data. Keep backups private and use a separate protected backup mechanism for higher-security deployments.

## Current encryption model

Messages use AES-256-GCM for encryption at rest. The server holds the key and decrypts messages for approved clients. This is **not end-to-end encryption**.

## Deployment rules

- Prefer trusted LAN deployment.
- Use HTTPS before sending credentials across an untrusted network.
- Never commit secrets, keys, databases, backups, or signing credentials.
- Rotate any secret that has been exposed.
- Stop TLC before backup/restore operations.
- Test restore procedures before relying on a backup.

## Reporting a security issue

For a private deployment, do not post encryption keys, passwords, database files, or backup archives in a public issue. Remove sensitive data and describe the issue with the smallest useful reproduction.
