# MAYA Production Remote Build Server (Hardened)

A hardened, isolated remote build microservice that compiles Android Gradle and Web projects safely.

## Hardened Security Architecture

1. **Non-Root Execution**: Runs as an unprivileged `builder` user (UID 1001). Never runs builds as root.
2. **Environment Sanitization**: Complete host environment variables (API keys, cloud tokens) are stripped. The child process receives only a minimal whitelist (`JAVA_HOME`, `ANDROID_HOME`, `PATH`, `GRADLE_USER_HOME`).
3. **No Shell Interpreter**: Spawns processes directly with `shell: false`. Arbitrary shell commands, subshells, metacharacters, and command chaining are blocked.
4. **Symlink & Path Traversal Guards**: Rejects any path with `..`, absolute paths, null bytes, or symbolic links breaking out of the workspace.
5. **Gradle Wrapper Integrity**: Android builds strictly verify the existence of `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle-wrapper.properties`.
6. **Artifact Cryptographic Verification**: Verifies physical file existence, verifies regular file status (`!isSymbolicLink()`), enforces allowed extensions, and calculates SHA-256 checksums.

## Recommended Container Resource Limits

When launching in Docker or Cloud Run, apply these flags:
```bash
docker run -d \
  --name maya-build-server \
  -p 8080:8080 \
  --memory=4g \
  --cpus=2 \
  --pids-limit=100 \
  --tmpfs /tmp:rw,size=2g \
  maya-remote-build-server
```

- **Memory**: 4 GB recommended (Gradle daemon + compiler overhead)
- **CPUs**: 2 vCPUs
- **Process limit**: 100 pids
- **Max Project Payload**: 50 MB
- **Execution Timeout**: 300s (Android), 120s (Web)
- **Output Buffer Cap**: 200 KB per stream (prevents memory exhaustion)

## API Endpoints

- `POST /build`: Compiles project files in an isolated temporary container/workspace.
- `GET /artifacts/:buildId/:fileName`: Downloads the verified binary artifact.
- `GET /health`: Health status check.
