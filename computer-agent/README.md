# TESSA Local Computer Agent

A Node.js companion service for authorized development tasks from MAYA/TESSA.

## Requirements

- Node.js 20 or newer
- npm

## Install

From this directory:

    npm install

## Start

    npm start

On first startup, the service prints a temporary pairing token if
`TESSA_PAIRING_TOKEN` is not configured. Treat it like a password:
never commit it or share it in screenshots, chat messages, or public logs.
For repeatable local setup, create a long random token locally and set
`TESSA_PAIRING_TOKEN` in the environment. See `.env.example`; this app
does not automatically load a .env file.

## Configuration

- `PORT`: service port (default 8990)
- `HOST`: bind interface (default 127.0.0.1)
- `TESSA_WORKSPACE`: workspace directory (defaults to computer-agent/workspace)

The default bind is loopback for safety. To connect from another device on a
trusted LAN, explicitly configure a private LAN interface and firewall rules.
Do not expose this service directly to the public internet: the Android
connector currently does not provide HTTPS transport.

## Security boundaries

File operations are intended to stay within the configured workspace.
Terminal commands use an executable allowlist and `shell: false`, but this is
not an operating-system sandbox; allowlisted programs can still access powerful
functionality. Use a disposable workspace and an unprivileged OS account.
Destructive operations should require explicit user approval in the client.

## Memory actions

The signed `/api/tessa/action` endpoint supports:
- `MEMORY_CREATE_CANDIDATE`: requires `parameters.consent: true`, a supported kind, and content. New entries remain candidates until reviewed.
- `MEMORY_LIST`: lists approved entries by default; pass `parameters.status: "candidate"` to review pending entries.
- `MEMORY_REVIEW`: approves or rejects a candidate by ID.

Memory is currently stored in the local agent's JSON file, scoped to the paired computer ID. It is not yet synchronized to Supabase and is not durable across ephemeral cloud deployments. Keep the agent on a trusted private machine; do not expose it publicly.

## Current limitations (not yet implemented)

- No production cloud deployment or persistent remote runtime.
- No Supabase client integration in the Node server yet; the SQL migration is
  only the database foundation and must be applied to the dedicated project.
- Browser module is a placeholder and does not inspect live pages or automate
  clicks, forms, or logins.
- No persistent task queue, autonomous planner, resume engine, or repair loop.
- No Gmail, WhatsApp, Amazon, or Flipkart API integration; outbox entries do
  not send messages.
- Unreal project creation writes a basic scaffold only; it does not install or
  launch Unreal Engine.
- The service does not provide HTTPS transport for the Android connector.

## Tests

    npm test

GitHub Actions runs the test suite on Node.js 20 and 22 for the TESSA
development branch. A passing CI run must be observed before calling the
branch verified.
