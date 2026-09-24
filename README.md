# TLC — Translocal Communication

A local-first communication project built with Java and a lightweight browser client. TLC is an evolving prototype; it is not yet a production-ready messenger.

## Design goals

- Local-network communication without mandatory cloud services.
- A browser-based client served by the Java server.
- Account-first access: new accounts remain pending until an owner approves them.
- Explicit configuration rather than secrets embedded in source code.
- Internet access remains optional. Do not expose the server directly to the public internet.
- Persistent per-installation encryption identity so normal TLC updates do not create a new message key.

## Same-origin integration

`Server/TlcServer.java` serves the files from `Client/` and the API from one HTTP origin:

- `/` serves `Client/index.html`.
- `/account.html`, `/admin.html`, and `/profile.html` serve their matching files from `Client/`.
- Static paths are normalized and checked to remain inside the configured client directory.
- The browser pages call API paths relative to their own origin, so cookies and requests use the same host and port.

By default, start the server with the repository root as the working directory so the `Client/` directory is found. Set `TLC_CLIENT_DIR` to an absolute or working-directory-relative path if the client files are elsewhere.

## Current API routes

| Route | Method | Purpose |
|---|---|---|
| `/api/status` | `GET` | Public server health/version |
| `/api/register` | `POST` | Create a pending account; JSON `{ "username", "password" }` |
| `/api/login` | `POST` | Verify credentials; approved users receive a session cookie |
| `/api/logout` | `POST` | Clear the current session |
| `/api/account/me` | `GET` | Return the current approved account |
| `/api/admin/pending` | `GET` | Owner-only pending account list; requires `X-TLC-Admin-Secret` |
| `/api/admin/status` | `POST` | Owner-only status change; JSON `{ "username", "status" }` |
| `/api/messages` | `GET`, `POST` | Read/send messages; requires an approved session. POST JSON is `{ "text" }`; sender comes from the session. |

Pending login returns `AWAITING_SYSADMIN_APPROVAL` and does not create a chat session. Message reads and writes require an approved session. Changing a user's status away from approved invalidates matching in-memory sessions.

## Client pages

- `Client/index.html` — server health, sign-in/registration, encrypted persistent chat send/read, and links to other pages.
- `Client/account.html` — registration, login, session check, and logout.
- `Client/admin.html` — owner-only approval interface; the owner secret is entered at runtime and is not embedded in the page.
- `Client/profile.html` — checks the signed-in session. Profile editing and server-side profile storage are not implemented yet.

## Encryption and key lifecycle

TLC message text is encrypted at rest with AES-256-GCM before it is written to SQLite. The server decrypts messages when an approved client requests them; this is **server-side encryption at rest, not end-to-end encryption**.

The installation encryption key is persistent:

1. If the persistent key file exists, TLC loads and validates it. This preserves the key across normal updates and restores.
2. If no key file exists but `TLC_ENCRYPTION_KEY` is supplied, TLC validates it and persists it for compatibility.
3. Otherwise, a new random 256-bit key is generated on first use.
4. The key is stored outside `Client/`, by default at `tlc-data/encryption.key`.
5. Normal application updates reuse the same key.
6. A fresh installation without its old data creates a new key.
7. Restoring the TLC backup restores the database and its matching key.

Never commit `tlc-data/encryption.key` or any real encryption key to GitHub.

## Manual TLC backup

TLC now includes `Server/TlcBackup.java` for a single-file backup containing the SQLite database and the installation key.

**Stop TLC before creating or restoring a backup.**

Create:

```text
java TlcBackup backup TLC_Backup.tlcb
```

Restore into a fresh TLC data location:

```text
java TlcBackup restore TLC_Backup.tlcb
```

The restore code refuses to overwrite an existing database or key. Keep the `.tlcb` backup private because it contains the encryption key needed to decrypt the backed-up messages.

The planned installer/update flow is:

```text
New TLC version detected
        ↓
⚠️ Please create a backup copy
        ↓
Create/confirm TLC_Backup.tlcb
        ↓
Continue update
        ↓
New version keeps the existing encryption key
```

The repository currently contains the backup engine, but the final OS/Android installer popup belongs in the installer/APK layer rather than the Java HTTP server. It should block the update until the user confirms that a backup was created or selects an existing backup.

## Requirements

- Java 11 or newer (JDK, including `javac` and `java`).
- SQLite JDBC driver on the classpath.
- A modern browser.

## Configuration

Set these environment variables on the device running the server:

- `TLC_PORT` — HTTP port; defaults to `8080`.
- `TLC_DATABASE_URL` — JDBC URL; defaults to `jdbc:sqlite:tlc.db`.
- `TLC_CLIENT_DIR` — client directory; defaults to `Client` relative to the process working directory.
- `TLC_KEY_FILE` — optional persistent encryption-key path; defaults to `tlc-data/encryption.key`.
- `TLC_ENCRYPTION_KEY` — optional existing Base64-encoded 256-bit key for compatibility. Do not commit it.
- `TLC_ADMIN_SECRET` — private owner secret, at least 24 characters. Never commit it, embed it in client-side code, or share it in screenshots. Replace it if exposed.
- `TLC_ALLOWED_ORIGIN` — optional exact CORS origin for separate-origin use; same-origin deployment does not need it.

The owner routes send the secret in `X-TLC-Admin-Secret`; use only from a trusted owner environment. Do not embed this secret in a public page.

## Run locally

Run the Java server with the repository root as its working directory, and include the SQLite JDBC driver on the classpath. Compile `Server/*.java` and launch `TlcServer` with the appropriate classpath and environment variables. Exact commands depend on where the JDBC JAR is stored and on the Android Java environment being used.

Once running, open `http://<server-host>:<port>/` in a browser. For another device on the same trusted Wi-Fi network, use the server device's LAN IP and configured port. Do not use GitHub Pages or `file://` for this same-origin setup; open the page through the Java server. Do not configure router port forwarding.

## Security status — read before use

This is a development prototype, not a hardened public service. In particular:

- Sessions are held in memory and are lost on restart.
- Use HTTPS before sending credentials across any network; the built-in HTTP server does not provide TLS itself.
- Owner-secret authentication needs stronger operational protection and rate limiting before public use.
- The lightweight request-field parser is not a full JSON parser and must be replaced before relying on adversarial input.
- CORS is not authentication. Keep the service on a trusted LAN and avoid public exposure.
- Backups contain the encryption key and therefore must be treated as sensitive data.
- Have a trusted adult or experienced developer review deployment and account/security changes before real users depend on TLC.

## Security files

- `.gitignore` blocks TLC runtime databases, encryption keys, backups, build output, and signing material from normal commits.
- `SECURITY.md` documents the current threat model and deployment rules.

## Development priorities

1. Replace ad-hoc JSON parsing with a maintained JSON library and consistent error handling.
2. Add automated tests for registration, pending/approved/rejected login, access revocation, encryption, and backup/restore.
3. Replace owner-secret-per-request access with a protected owner session and rate limiting.
4. Replace the current manual backup command with the final installer/APK backup gate and restore flow.
5. Add secure, persistent session management and HTTPS deployment guidance.
6. Implement server-side profiles and privacy controls, then friends/groups and notifications.
7. Harden registration so account creation and pending approval are atomic.

## Update log

> New development updates are appended here. This log records changes made through the connected GitHub workflow; it does not imply that browser, end-to-end, or device tests were run.

### 2026-09-24 — Persistent encryption identity and backup foundation

- Added `Server/TlcKeyStore.java` for per-installation persistent 256-bit key generation/storage.
- Updated `Server/TlcCrypto.java` to use the persistent installation key while retaining compatibility with `TLC_ENCRYPTION_KEY`.
- Added `Server/TlcBackup.java` for manual SQLite + encryption-key backup/restore using a `.tlcb` file.
- Restore refuses to overwrite an existing database/key and validates the backup format/key.
- Updated the chat UI so it no longer claims messages disappear on server restart; it now describes persistent encrypted storage.
- Documented the planned installer/APK backup gate.

**Verification status:** Changes were committed to GitHub. No Android build, Java `javac` build, browser test, or installer test was run as part of these updates.
