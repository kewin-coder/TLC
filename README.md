# TLC — Translocal Communication

A local-first communication project being developed with Java and a lightweight web client.

## Project goals

- Run on a local network without requiring cloud services.
- Provide a browser-based homepage and client.
- Start with a Java HTTP status endpoint, then build chat, accounts, persistence, and notifications in deliberate stages.
- Keep internet connectivity optional; do not expose the server to the public internet by default.

## Current starter

The first milestone is a small Java HTTP server with a `/api/status` endpoint and a responsive homepage that checks it. The starter does **not** yet implement authentication, message delivery, or production security.

## Requirements

- Java 11 or newer (JDK, for `javac` and `java`).
- A browser.

## Run the server

From the `Server` directory:

```sh
javac TlcServer.java
java TlcServer
```

Then open `Client/index.html` in a browser. For a device on the same Wi-Fi/LAN, use the host device's LAN IP and port 8080 in the server URL field. Keep the server on a trusted network.

## Planned milestones

1. Homepage + health/status endpoint.
2. Client/server message exchange.
3. SQLite persistence and account flow.
4. Group chat and notifications.
5. Validation, access controls, tests, and Android packaging.

This is an early development scaffold, not a secure production messenger.