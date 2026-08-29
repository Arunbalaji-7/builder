# Raw XML Data Puller Docs

This folder contains operational and technical documentation for the desktop app and its supporting mail API integration.

## Build Quick Start

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

## Documentation Index

- [build.md](build.md): Build commands, prerequisites, packaging output.
- [technical.md](technical.md): Components, configuration, service boundaries.
- [workflow.md](workflow.md): Runtime and operational flows.
- [architecture-diagram.svg](architecture-diagram.svg): System architecture diagram.
- [workflow-diagram.svg](workflow-diagram.svg): End-to-end credential mail workflow diagram.

## What Changed

Credential delivery is now API-driven:

- The desktop app no longer sends email through direct SMTP sockets.
- The app calls the mail API endpoint using `application/x-www-form-urlencoded`.
- The endpoint then relays email using the server-side `eRx-mail-server` WAR.

## Mail Configuration

Set these keys in `src/main/resources/application.properties` (or DB-backed config overrides):

- `mail.server.api=http://tla-w01rxm0101/erx-mail-server/api/send-mail`
- `mail.from=rawxmldatapuller@walgreens.com`

Request contract used by desktop app:

- `mail_from` (required)
- `mail_to` (required)
- `subject` (required)
- `body` (required)

The API provides defaults for relay host/port (`corpsmtprelay.walgreens.com:25`), so the desktop app does not send `smtp_host` or `smtp_port`.
