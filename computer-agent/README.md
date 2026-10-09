# TESSA Turbo V1.2 — Local Computer Companion Service

A lightweight, secure companion service that runs on your local computer/laptop to enable authorized development, Unreal Engine building, terminal tasks, and browser automation from MAYA / TESSA on Android.

## Setup Instructions

1. Ensure Node.js (v18+) is installed on your computer.
2. In this directory, install dependencies:
   ```bash
   npm install
   ```
3. Start the companion server:
   ```bash
   npm start
   ```
4. In MAYA on Android, navigate to TESSA Settings -> Pair Local Computer.
5. Enter your computer's local IP address, Port (`8990`), and Pairing Token.
6. Grant permissions inside TESSA before running sensitive terminal, build, or destructive file operations.
