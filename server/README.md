# MAYA Production Remote Build Server (Google Cloud Run)

A hardened, isolated remote build microservice designed for **Google Cloud Run** that compiles Android Gradle (`APK` / `AAB`) and Web (`WEB`) projects safely.

## Hardened Security Architecture

1. **Non-Root Execution**: Runs as an unprivileged `builder` user (`UID 1001`, `/usr/sbin/nologin`). Never executes builds as root.
2. **Environment Sanitization**: Host environment variables (such as `REMOTE_BUILD_API_KEY` or cloud service credentials) are stripped before spawning child build processes. Only a minimal allowlist (`JAVA_HOME`, `ANDROID_HOME`, `PATH`, `GRADLE_USER_HOME`, `TMPDIR`) is passed.
3. **No Shell Interpreter (`shell: false`)**: Spawns binaries directly with `shell: false` and an explicit command allowlist (`ALLOWED_COMMAND_MAP`). Arbitrary shell commands, subshells, metacharacters (`;`, `|`, `&`, `` ` ``, `$()`), and command chaining are rejected with HTTP 403 (`INVALID_COMMAND`).
4. **Symlink, Null-Byte & Path Traversal Guards**: Rejects any path containing `..`, `\0`, absolute paths, or symbolic links anywhere in the directory chain.
5. **Workspace Isolation**: Each `buildId` executes in an isolated directory (`/tmp/maya-builds/<buildId>`) with its own `GRADLE_USER_HOME` and is deleted immediately after artifact extraction.
6. **Gradle Wrapper Integrity**: Android builds verify the physical presence and regular-file status of `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties`.
7. **Artifact Cryptographic Verification**: Verifies physical file existence, non-zero byte length, regular file status (`!isSymbolicLink()`), allowed extension (`.apk`, `.aab`, `.js`, `.html`, `.zip`, `.tar.gz`, `.css`), and computes a SHA-256 digest returned in both JSON metadata and the `X-Artifact-SHA256` response header.

## API Endpoints

- `GET /health`: Health probe returning service status, active builds, and uptime.
- `POST /build`: Executes an isolated Android (`APK` / `AAB`) or `WEB` build and returns a normalized `BuildResult`.
- `GET /build/:buildId`: Retrieves the current or completed normalized `BuildResult` for `:buildId`.
- `GET /artifacts/:buildId/:fileName`: Streams the verified binary artifact with `X-Artifact-SHA256` header.

### Example `POST /build` Payload

```json
{
  "buildId": "eb90112c-84d2-4c11-9b2a-1029384756ab",
  "projectType": "ANDROID",
  "buildCommand": "./gradlew assembleRelease",
  "requestedArtifactType": "APK",
  "files": {
    "settings.gradle.kts": "rootProject.name = \"MayaApp\"\ninclude(\":app\")",
    "build.gradle.kts": "...",
    "gradlew": "...",
    "gradle/wrapper/gradle-wrapper.jar": "...",
    "gradle/wrapper/gradle-wrapper.properties": "...",
    "app/build.gradle.kts": "..."
  }
}
```

## Google Cloud Run Deployment Configuration

### Required Google Cloud Setup
1. **Google Cloud Project** with Billing enabled.
2. **APIs Enabled**:
   - Cloud Run Admin API (`run.googleapis.com`)
   - Cloud Build API (`cloudbuild.googleapis.com`)
   - Artifact Registry API (`artifactregistry.googleapis.com`)
3. **Authentication**:
   - Authenticate via `gcloud auth login` and set project via `gcloud config set project <PROJECT_ID>`, or provide a `GOOGLE_APPLICATION_CREDENTIALS` service account key with Cloud Run Admin & Cloud Build Editor roles.

### Deploy Command

```bash
gcloud run deploy maya-remote-builder \
  --source ./server \
  --region asia-south1 \
  --platform managed \
  --memory 4Gi \
  --cpu 2 \
  --timeout 300 \
  --concurrency 4 \
  --max-instances 5 \
  --set-env-vars="MAX_CONCURRENT_BUILDS=4,BUILD_TIMEOUT_MS=300000,REMOTE_BUILD_API_KEY=your-secret-api-key" \
  --allow-unauthenticated
```

### Local Docker Resource Limits

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
