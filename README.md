# TLC — Translocal Communication

A local-first communication project built with Java and a lightweight browser client. TLC is an evolving prototype; it is not yet a production-ready messenger.

## Design goals

- Local-network communication without mandatory cloud services.
- A browser-based client served by the Java server.
- Account-first access: new accounts remain pending until an owner approves them.
- Explicit configuration rather than secrets embedded in source code.
- Internet access remains optional. Do not expose the server directly to the public internet.

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

- `Client/index.html` — server health, sign-in/registration, chat send/read, and links to other pages.
- `Client/account.html` — registration, login, session check, and logout.
- `Client/admin.html` — owner-only approval interface; the owner secret is entered at runtime and is not embedded in the page.
- `Client/profile.html` — checks the signed-in session. Profile editing and server-side profile storage are not implemented yet.

## Requirements

- Java 11 or newer (JDK, including `javac` and `java`).
- SQLite JDBC driver on the classpath.
- A modern browser.

## Configuration

Set these environment variables on the device running the server:

- `TLC_PORT` — HTTP port; defaults to `8080`.
- `TLC_DATABASE_URL` — JDBC URL; defaults to `jdbc:sqlite:tlc.db`.
- `TLC_CLIENT_DIR` — client directory; defaults to `Client` relative to the process working directory.
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
- Have a trusted adult or experienced developer review deployment and account/security changes before real users depend on TLC.

## Development priorities

1. Replace ad-hoc JSON parsing with a maintained JSON library and consistent error handling.
2. Add automated tests for registration, pending/approved/rejected login, and access revocation.
3. Replace owner-secret-per-request access with a protected owner session and rate limiting.
4. Add secure, persistent session management and HTTPS deployment guidance.
5. Implement server-side profiles and privacy controls, then friends/groups and notifications.
6. Harden registration so account creation and pending approval are atomic.

## Update log

> New development updates are appended here. This log records changes made through the connected GitHub workflow; it does not imply that browser or end-to-end tests were run.

### 2026-09-22 — Frontend navigation and page polish

- **Chat page** — responsive desktop sidebar and mobile bottom navigation, active Chat indicator, responsive layout improvements, clearer chat status, and some authentication busy-state handling.
  - Commit: [`1328b9c`](https://github.com/kewin-coder/TLC/commit/1328b9cd5838150549ee0e4aa46e7d24fe3a65b8)
- **Account page** — matching responsive navigation and dark styling, improved focus states, busy handling for session actions, tab switching disabled while busy, password clearing, and clearer status feedback.
  - Commit: [`0396a78`](https://github.com/kewin-coder/TLC/commit/0396a789e7c8b216314fce489f2f713b8bf9ba96)
- **Profile page** — responsive navigation, improved session/sign-out handling, and clearer errors. The page explicitly notes that profile editing and server-side profile storage are not implemented.
  - Commit: [`345b9e7`](https://github.com/kewin-coder/TLC/commit/345b9e78370b326e8c4d2a1eb05e60ef216a2379)
- **Admin page** — matching responsive navigation and improved focus/busy states; prevents overlapping approval operations while keeping the existing API routes.
  - Commit: [`c2ac63c`](https://github.com/kewin-coder/TLC/commit/c2ac63c793bedec0a65f309787e59946fc101f64)
- **README** — added this update log and linked the frontend commits.

**Verification status:** Changes were committed to GitHub. No browser or end-to-end test was run as part of these updates.