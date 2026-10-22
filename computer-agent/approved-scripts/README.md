# Approved scripts policy (TESSA Turbo V1.2)

This folder is for scripts that a maintainer has reviewed and explicitly approved.
Do not treat a script as trusted merely because it is stored here.

## Before approving a script

1. Read the entire script and every file it imports.
2. Check for network access, file deletion/modification, credential access, and child-process execution.
3. Use test data and a disposable workspace first.
4. Record the reviewer, purpose, expected inputs, and rollback steps.
5. Keep API keys, pairing tokens, passwords, and personal data out of source control.
6. Require explicit user confirmation before any consequential action.

## Important limitations

- `terminal.js` uses `spawn` with `shell: false`, blocks common shell operators, and checks a command-name allow-list. This is **not** an operating-system sandbox. Allow-listed tools such as `node`, `npm`, `pnpm`, `python`, and `git` can still perform powerful actions.
- Do not run untrusted scripts or install unreviewed packages.
- A queued item in the report outbox is only a record with an approval status. It does not send email, WhatsApp, Telegram, or other messages.
- Never put real credentials in tests.

## Running the built-in tests

From the `computer-agent` directory, using a supported Node.js version:

```sh
node --test test/agent.test.js
```

These tests use Node's built-in test runner and do not add a new dependency. They test current exported APIs; they do not certify the whole service as secure.
