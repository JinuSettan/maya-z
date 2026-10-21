# Approved skill scripts

Only place scripts here after reviewing their complete source code. These scripts run as the local computer user's account and can potentially access that user's files. The terminal allowlist does not sandbox a skill script.

- Use a fixed script name containing only lowercase letters, digits, `_` or `-`.
- Validate every input and never accept arbitrary command strings.
- Prefer read-only behavior; require explicit user approval before external messages, purchases, file deletion, or other consequential actions.
- Do not store credentials in scripts or print secrets to logs.
- Do not add unofficial WhatsApp automation without understanding account-ban and privacy risks.
