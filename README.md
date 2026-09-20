# TLC — Translocal Communication

A local-first communication project built with Java and a lightweight browser client. TLC is an evolving prototype; it is not yet a production-ready messenger.

## Design goals

- Local-network communication without mandatory cloud services.
- A browser-based client and a Java server.
- Account-first access: new accounts remain quarantined until an owner approves them.
- Explicit, documented configuration rather than secrets embedded in source code.
- Internet access remains optional. Do not expose the server directly to the public internet.

## Current server routes

| Route | Method | Purpose |
|---|---|---|
| `/api/status` | `GET` | Basic server health/version (public) |
| `/api/register` | `POST` | Create an account in pending state |
| `/api/login` | `POST` | Verify credentials; approved users receive a session |
| `/api/logout` | `POST` | Clear the current session |
| `/api/account/me` | `GET` | Return the current approved account |
| `/api/admin/pending` | `GET` | Owner-only pending account list |
| `/api/admin/status` | `POST` | Owner-only account status change |
| `/api/messages` | `GET`, `POST` | Read/send messages; requires an approved session |

A pending login returns the machine-readable status `AWAITING_SYSADMIN_APPROVAL` and does not create a chat session. Message reads and writes are gated by the account approval check. Account status changes away from approved are intended to revoke that account's active sessions.

## Requirements

- Java 11 or newer (JDK, including `javac` and `java`).
- SQLite JDBC driver on the classpath (the account storage uses SQLite).
- A modern browser.

## Configuration

Set these environment variables on the device running the server:

- `TLC_PORT` — HTTP port; defaults to `8080`.
- `TLC_DATABASE_URL` — JDBC URL; defaults to `jdbc:sqlite:tlc.db`.
- `TLC_ALLOWED_ORIGIN` — exact trusted browser origin when hosting the client separately. Leave unset for same-origin use.
- `TLC_ADMIN_SECRET` — private owner secret. Use a long, randomly generated value (at least 24 characters). Never commit it, paste it into client-side code, or share it in screenshots. If it was ever exposed, replace it.

The owner routes send the secret in `X-TLC-Admin-Secret`; use only from a trusted owner environment. Do not build a public admin page that embeds this secret.

## Run locally

From the `Server` directory, compile with the SQLite JDBC driver available on the classpath. Exact commands depend on where the JAR is stored and on the Android Java environment being used. Then start `TlcServer` with the same classpath and configured environment variables.

For a LAN client, use the host device's LAN IP and configured port. Keep the server on a trusted Wi-Fi network and do not configure router port forwarding.

## Security status — read before use

This is a development prototype, not a hardened public service. In particular:

- Sessions are currently held in memory and are lost on restart.
- Use HTTPS before sending credentials across any network; the built-in HTTP server does not provide TLS by itself.
- Owner-secret authentication needs stronger operational protection and rate limiting before public use.
- The lightweight request-field parser is not a full JSON parser and must be replaced before relying on adversarial input.
- CORS is not authentication. Keep the service on a trusted LAN and avoid public exposure.
- Have a trusted adult or experienced developer review deployment and account/security changes before real users depend on TLC.

## Development priorities

1. Replace ad-hoc JSON parsing with a maintained JSON library and consistent error handling.
2. Add automated tests for registration, pending/approved/rejected login, and access revocation.
3. Replace owner-secret-per-request access with a protected owner session and rate limiting.
4. Add secure, persistent session management and HTTPS deployment guidance.
5. Integrate the browser client with registration, login, logout, and pending approval states.
6. Continue with profiles, privacy controls, friends/groups, and notifications.
