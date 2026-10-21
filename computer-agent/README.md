# TESSA Local Computer Agent

A local Node.js companion service for authorized development tasks from MAYA/TESSA.

## Requirements

- Node.js 18 or newer
- npm
- A trusted private network for Android-to-computer access

## Install

From this directory:

    npm install

## Start

    npm start

On first startup, the terminal prints a pairing token.
Enter that token in the MAYA/TESSA Pair Local Computer screen.

The token is private. Do not post it in GitHub, screenshots,
chat messages, or public logs.

To use a persistent token, set TESSA_PAIRING_TOKEN in the
computer's environment before starting the service.

Optional environment variables:

- PORT: service port (default 8990)
- HOST: network interface (default 0.0.0.0)
- TESSA_WORKSPACE: local workspace directory

## Workspace and command restrictions

File operations are limited to the configured workspace.
Terminal execution uses an executable allowlist and does not
invoke a shell for command strings.

Only run this service on a trusted private network. The current
Android connector uses HTTP on the local network, not encrypted
HTTPS. Do not expose port 8990 to the public internet.

Destructive operations should require explicit user approval in
the Android app. This server-side service does not replace that
approval layer.

## Current limitations

- Browser integration is a placeholder; it does not perform real
  browser automation or inspect live page contents.
- The service does not yet have a persistent task queue, autonomous
  task planner, offline resume engine, or automatic repair loop.
- Offline operation is limited to tools already installed locally.
- Unreal project creation creates a basic project scaffold; it does
  not guarantee that the project can compile or launch.
- The current service does not provide HTTPS transport for the
  Android connector.

Test on a disposable workspace before using important project files.

## v1.3 security changes

- **No interpreters.** `python`, `node` and `npx` are no longer allowed in the
  terminal. Every argument that looks like a path must stay inside the
  workspace, and each tool only accepts a short list of subcommands.
  Approved automation goes in `approved-scripts/` and is started with
  the `RUN_SKILL_SCRIPT` action.
- **Replay protection.** Signed requests must send `X-Tessa-Timestamp` and
  `X-Tessa-Nonce`; the signature covers `timestamp.nonce.body`. Requests older
  than 60 seconds or reusing a nonce are rejected. Sessions expire after 12 h
  (`TESSA_SESSION_TTL_HOURS`).
- **Private network only.** Clients outside home Wi-Fi / Tailscale ranges are
  refused (`TESSA_ALLOW_PUBLIC=1` disables this).
- Build tools still run scripts from the workspace. Only use workspaces you
  trust.
- Traffic is still plain HTTP. Use it over Tailscale/WireGuard, not open Wi-Fi.
  Do not expose port 8990 to the public internet.
- Build/package-manager commands can execute project scripts. Only use a
  workspace containing code you trust; command allowlisting is not a full
  operating-system sandbox.

## Report skill

Actions: `REPORT_CLASSIFY`, `REPORT_FILTER_CHATS`, `REPORT_CREATE` (PDF into
`reports/`), `OUTBOX_QUEUE`, `OUTBOX_LIST`, `OUTBOX_DECIDE`.

Nothing is ever sent from the computer. Queued items stay `pending` until you
approve them in the app, which then sends them with its own connector.
Install `pdfkit` and set `TESSA_PDF_FONT` to a compatible Unicode font file
(for example Noto Sans Malayalam) for Malayalam output. The font path must exist.
The fallback PDF writer only supports basic Latin text.

Run tests: `npm test` (uses Node.js built-in test runner; no extra test framework required).
