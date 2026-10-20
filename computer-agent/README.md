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
