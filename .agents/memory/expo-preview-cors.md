---
name: Expo preview API access
description: Cross-origin behavior when the Expo mobile preview calls a shared Replit API.
---

The Expo Go/web preview for a mobile artifact is served from a separate `*.replit.dev` origin than the API service. A backend that only assumes same-origin proxy access will fail browser preflight requests from the phone preview.

**Why:** The mobile preview loaded correctly but could not list conversations until the API returned CORS headers for the preview origin.

**How to apply:** When a mobile artifact calls a shared API, allow only trusted Replit preview origins (and local development) with the backend's CORS middleware; verify an `OPTIONS` request with the mobile origin before presenting the app.