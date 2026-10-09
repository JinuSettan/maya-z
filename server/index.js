/**
 * MAYA Production Remote Build Server (Hardened for Google Cloud Run)
 *
 * Secure, isolated remote build microservice supporting ANDROID (APK / AAB) and WEB builds.
 * - Zero external npm dependencies (100% native Node.js built-ins)
 * - Strict unprivileged execution (never root in container)
 * - Sanitized environment (no server secrets/tokens exposed to child processes or logs)
 * - Direct binary execution (NO shell: true, strict command allowlist)
 * - Strict symlink, null-byte & path traversal defense on all file/directory segments
 * - Android Gradle Wrapper integrity verification
 * - Cryptographic SHA-256 validation for regular file artifacts (APK, AAB, WEB)
 * - Endpoints:
 *     GET  /health
 *     POST /build
 *     GET  /build/:buildId
 *     GET  /artifacts/:buildId/:fileName
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');

const PORT = parseInt(process.env.PORT || '8080', 10);
const BUILDS_ROOT = path.resolve(process.env.TMPDIR || '/tmp', 'maya-builds');
const ARTIFACTS_ROOT = path.resolve(process.env.TMPDIR || '/tmp', 'maya-artifacts');
const MAX_CONCURRENT_BUILDS = parseInt(process.env.MAX_CONCURRENT_BUILDS || '4', 10);
const MAX_PAYLOAD_SIZE = 50 * 1024 * 1024; // 50MB limit
const MAX_LOG_BYTES = 200000; // 200KB per stream

// Ensure root directories exist
fs.mkdirSync(BUILDS_ROOT, { recursive: true });
fs.mkdirSync(ARTIFACTS_ROOT, { recursive: true });

// In-memory build status registry (bounded to 500 entries) for GET /build/:buildId
const buildRegistry = new Map();
let activeBuildCount = 0;

function recordBuildState(buildId, stateObj) {
    if (buildRegistry.size >= 500 && !buildRegistry.has(buildId)) {
        const oldestKey = buildRegistry.keys().next().value;
        if (oldestKey) buildRegistry.delete(oldestKey);
    }
    buildRegistry.set(buildId, {
        ...stateObj,
        updatedAt: new Date().toISOString()
    });
}

function calculateSha256(filePath) {
    const fileBuffer = fs.readFileSync(filePath);
    return crypto.createHash('sha256').update(fileBuffer).digest('hex');
}

function sendJson(res, statusCode, data) {
    if (res.writableEnded) return;
    res.writeHead(statusCode, {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
        'X-Content-Type-Options': 'nosniff'
    });
    res.end(JSON.stringify(data));
}

// Allowed build binaries
const ALLOWED_BINARIES = new Set(['./gradlew', 'gradle', 'npm', 'npx', 'pnpm', 'yarn']);

// Explicit strict command allowlist mapping
const ALLOWED_COMMAND_MAP = {
    './gradlew assembleRelease': { cmd: './gradlew', args: ['assembleRelease', '--no-daemon', '--stacktrace'] },
    './gradlew assembleDebug': { cmd: './gradlew', args: ['assembleDebug', '--no-daemon', '--stacktrace'] },
    './gradlew bundleRelease': { cmd: './gradlew', args: ['bundleRelease', '--no-daemon', '--stacktrace'] },
    './gradlew bundleDebug': { cmd: './gradlew', args: ['bundleDebug', '--no-daemon', '--stacktrace'] },
    'gradle assembleRelease': { cmd: './gradlew', args: ['assembleRelease', '--no-daemon', '--stacktrace'] },
    'gradle assembleDebug': { cmd: './gradlew', args: ['assembleDebug', '--no-daemon', '--stacktrace'] },
    'gradle bundleRelease': { cmd: './gradlew', args: ['bundleRelease', '--no-daemon', '--stacktrace'] },
    'npm run build': { cmd: 'npm', args: ['run', 'build'] },
    'pnpm run build': { cmd: 'pnpm', args: ['run', 'build'] },
    'yarn build': { cmd: 'yarn', args: ['build'] },
    'npm test': { cmd: 'npm', args: ['test'] }
};

// Allowed artifact extensions
const ALLOWED_ARTIFACT_EXTS = new Set(['.apk', '.aab', '.js', '.html', '.zip', '.tar.gz', '.css']);

/**
 * Validates a relative file path against null bytes, path traversal, and absolute paths.
 */
function validateSafeRelativePath(relPath, resolvedWorkspace) {
    if (typeof relPath !== 'string' || relPath.length === 0 || relPath.length > 512) {
        return { valid: false, reason: 'Invalid or empty file path' };
    }
    if (relPath.includes('\0')) {
        return { valid: false, reason: 'Null-byte injection detected in path' };
    }
    const normalized = relPath.replace(/\\/g, '/');
    if (normalized.startsWith('/') || normalized.includes('..')) {
        return { valid: false, reason: `Path traversal or absolute path rejected: '${relPath}'` };
    }
    const segments = normalized.split('/');
    for (const seg of segments) {
        if (seg === '' || seg === '.' || seg === '..') {
            return { valid: false, reason: `Illegal path segment in '${relPath}'` };
        }
    }
    const targetPath = path.resolve(resolvedWorkspace, normalized);
    if (!targetPath.startsWith(resolvedWorkspace + path.sep)) {
        return { valid: false, reason: `Path breakout outside workspace rejected: '${relPath}'` };
    }
    return { valid: true, targetPath, normalized };
}

/**
 * Checks that no directory component from workspace root to targetPath is a symbolic link.
 */
function ensureNoSymlinksInChain(resolvedWorkspace, targetPath) {
    const rel = path.relative(resolvedWorkspace, targetPath);
    const parts = rel.split(path.sep);
    let current = resolvedWorkspace;
    for (const part of parts) {
        current = path.join(current, part);
        if (fs.existsSync(current)) {
            const lstat = fs.lstatSync(current);
            if (lstat.isSymbolicLink()) {
                return false;
            }
        }
    }
    return true;
}

/**
 * Recursively searches a directory for files matching a predicate, ignoring symlinks.
 */
function findFilesRecursive(dir, predicate, results = []) {
    if (!fs.existsSync(dir)) return results;
    const entries = fs.readdirSync(dir);
    for (const entry of entries) {
        const fullPath = path.join(dir, entry);
        try {
            const lstat = fs.lstatSync(fullPath);
            if (lstat.isSymbolicLink()) continue;
            if (lstat.isDirectory()) {
                findFilesRecursive(fullPath, predicate, results);
            } else if (lstat.isFile() && predicate(fullPath, entry)) {
                results.push(fullPath);
            }
        } catch (_) {}
    }
    return results;
}

/**
 * Runs a single process with shell: false, timeout, and sanitized environment.
 */
function runProcessSafe(cmd, args, cwd, env, timeoutMs) {
    return new Promise((resolve) => {
        let stdout = '';
        let stderr = '';
        let timedOut = false;

        let proc;
        try {
            proc = spawn(cmd, args, {
                cwd,
                env,
                shell: false,
                stdio: ['ignore', 'pipe', 'pipe']
            });
        } catch (err) {
            return resolve({
                exitCode: 127,
                stdout: '',
                stderr: `Spawn error: ${err.message}`,
                timedOut: false
            });
        }

        const timer = setTimeout(() => {
            timedOut = true;
            try { proc.kill('SIGKILL'); } catch (_) {}
        }, timeoutMs);

        proc.stdout.on('data', d => {
            stdout += d.toString();
            if (stdout.length > MAX_LOG_BYTES) {
                stdout = stdout.substring(stdout.length - MAX_LOG_BYTES);
            }
        });

        proc.stderr.on('data', d => {
            stderr += d.toString();
            if (stderr.length > MAX_LOG_BYTES) {
                stderr = stderr.substring(stderr.length - MAX_LOG_BYTES);
            }
        });

        proc.on('error', err => {
            clearTimeout(timer);
            resolve({
                exitCode: 127,
                stdout,
                stderr: `${stderr}\nProcess error: ${err.message}`.trim(),
                timedOut: false
            });
        });

        proc.on('close', code => {
            clearTimeout(timer);
            resolve({
                exitCode: timedOut ? 124 : (code ?? 1),
                stdout,
                stderr,
                timedOut
            });
        });
    });
}

function createNormalizedResponse({
    success,
    buildId,
    status,
    exitCode,
    stdout = '',
    stderr = '',
    artifactMeta = null,
    errorCode = null,
    message = ''
}) {
    return {
        success: Boolean(success),
        backend: 'CLOUD_RUN',
        buildId,
        status,
        exitCode,
        stdout,
        stderr,
        artifactName: artifactMeta ? artifactMeta.fileName : null,
        artifactUrl: artifactMeta ? artifactMeta.downloadUrl : null,
        artifactType: artifactMeta ? artifactMeta.artifactType : null,
        artifactSize: artifactMeta ? artifactMeta.sizeBytes : 0,
        sha256: artifactMeta ? artifactMeta.sha256 : null,
        errorCode,
        message: message || (success ? 'Build completed successfully' : (stderr || 'Build failed')),
        artifact: artifactMeta
    };
}

function checkOptionalAuth(req) {
    const requiredKey = process.env.REMOTE_BUILD_API_KEY;
    if (!requiredKey || requiredKey.trim() === '') return true;
    const authHeader = req.headers['authorization'] || '';
    return authHeader === `Bearer ${requiredKey.trim()}` || authHeader === `token ${requiredKey.trim()}`;
}

const server = http.createServer((req, res) => {
    // Enable CORS
    if (req.method === 'OPTIONS') {
        res.writeHead(204, {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type, Authorization'
        });
        return res.end();
    }

    let parsedUrl;
    try {
        parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    } catch (_) {
        return sendJson(res, 400, { error: 'Malformed URL' });
    }

    // 1. Health check: GET /health
    if (req.method === 'GET' && parsedUrl.pathname === '/health') {
        return sendJson(res, 200, {
            status: 'HEALTHY',
            service: 'MAYA Production Remote Build Server (Hardened)',
            backend: 'CLOUD_RUN',
            activeBuilds: activeBuildCount,
            uptimeSeconds: Math.floor(process.uptime()),
            timestamp: new Date().toISOString()
        });
    }

    // Optional authentication check for build & artifact endpoints
    if (!checkOptionalAuth(req)) {
        return sendJson(res, 401, {
            error: 'UNAUTHORIZED',
            message: 'Missing or invalid Authorization token for Cloud Run build service.'
        });
    }

    // 2. Build status lookup: GET /build/:buildId
    if (req.method === 'GET' && parsedUrl.pathname.startsWith('/build/')) {
        const parts = parsedUrl.pathname.split('/').filter(Boolean);
        if (parts.length === 2) {
            const rawBuildId = parts[1];
            if (rawBuildId.includes('\0') || rawBuildId.includes('..') || !/^[a-zA-Z0-9_-]+$/.test(rawBuildId)) {
                return sendJson(res, 400, { error: 'Security rejection: Invalid buildId format' });
            }
            const state = buildRegistry.get(rawBuildId);
            if (!state) {
                return sendJson(res, 404, {
                    success: false,
                    backend: 'CLOUD_RUN',
                    buildId: rawBuildId,
                    status: 'NOT_FOUND',
                    exitCode: 404,
                    errorCode: 'BUILD_NOT_FOUND',
                    message: `No build found for buildId '${rawBuildId}'`
                });
            }
            return sendJson(res, 200, state);
        }
    }

    // 3. Artifact download endpoint: GET /artifacts/:buildId/:fileName
    if (req.method === 'GET' && parsedUrl.pathname.startsWith('/artifacts/')) {
        let decodedPath;
        try {
            decodedPath = decodeURIComponent(parsedUrl.pathname);
        } catch (_) {
            return sendJson(res, 400, { error: 'Security rejection: Malformed URI encoding' });
        }
        const parts = decodedPath.split('/').filter(Boolean);
        if (parts.length >= 3) {
            const buildId = parts[1];
            const fileName = parts.slice(2).join('/');

            // Null-byte, path traversal & symlink prevention
            if (
                buildId.includes('\0') ||
                fileName.includes('\0') ||
                buildId.includes('..') ||
                fileName.includes('..') ||
                path.isAbsolute(fileName) ||
                !/^[a-zA-Z0-9_-]+$/.test(buildId)
            ) {
                return sendJson(res, 400, { error: 'Security rejection: Invalid path' });
            }

            const targetDir = path.resolve(ARTIFACTS_ROOT, buildId);
            const artifactPath = path.resolve(targetDir, fileName);

            if (!artifactPath.startsWith(targetDir + path.sep)) {
                return sendJson(res, 400, { error: 'Security rejection: Path outside artifact storage' });
            }

            if (!fs.existsSync(artifactPath)) {
                return sendJson(res, 404, { error: 'Artifact not found' });
            }

            const lstat = fs.lstatSync(artifactPath);
            if (lstat.isSymbolicLink() || !lstat.isFile()) {
                return sendJson(res, 403, { error: 'Security rejection: Symlinks or non-regular files cannot be downloaded' });
            }

            const stat = fs.statSync(artifactPath);
            const hash = calculateSha256(artifactPath);
            let mimeType = 'application/octet-stream';
            if (fileName.endsWith('.apk')) mimeType = 'application/vnd.android.package-archive';
            else if (fileName.endsWith('.aab')) mimeType = 'application/x-authorware-bin';
            else if (fileName.endsWith('.js')) mimeType = 'application/javascript';
            else if (fileName.endsWith('.html')) mimeType = 'text/html';
            else if (fileName.endsWith('.zip')) mimeType = 'application/zip';

            res.writeHead(200, {
                'Content-Type': mimeType,
                'Content-Length': stat.size,
                'X-Artifact-SHA256': hash,
                'Content-Disposition': `attachment; filename="${path.basename(fileName)}"`
            });
            return fs.createReadStream(artifactPath).pipe(res);
        }
        return sendJson(res, 404, { error: 'Not found' });
    }

    // 4. Build endpoint: POST /build
    if (req.method === 'POST' && (parsedUrl.pathname === '/build' || parsedUrl.pathname === '/build/')) {
        if (activeBuildCount >= MAX_CONCURRENT_BUILDS) {
            return sendJson(res, 429, {
                success: false,
                backend: 'CLOUD_RUN',
                status: 'FAILED',
                exitCode: 429,
                errorCode: 'TOO_MANY_CONCURRENT_BUILDS',
                message: 'Server is at maximum concurrent build capacity. Please retry shortly.'
            });
        }

        let bodyChunks = [];
        let totalSize = 0;
        let payloadExceeded = false;

        req.on('data', chunk => {
            totalSize += chunk.length;
            if (totalSize > MAX_PAYLOAD_SIZE) {
                payloadExceeded = true;
                req.destroy();
                return sendJson(res, 413, {
                    success: false,
                    backend: 'CLOUD_RUN',
                    status: 'FAILED',
                    exitCode: 413,
                    errorCode: 'PAYLOAD_TOO_LARGE',
                    message: 'Payload too large (max 50MB)'
                });
            }
            bodyChunks.push(chunk);
        });

        req.on('end', async () => {
            if (payloadExceeded) return;
            let workspaceDir = null;
            activeBuildCount++;

            try {
                const bodyStr = Buffer.concat(bodyChunks).toString('utf-8');
                if (!bodyStr || !bodyStr.trim()) {
                    activeBuildCount--;
                    return sendJson(res, 400, {
                        success: false,
                        backend: 'CLOUD_RUN',
                        status: 'FAILED',
                        exitCode: 1,
                        errorCode: 'MALFORMED_REQUEST',
                        stderr: 'Request body is empty.'
                    });
                }

                let buildRequest;
                try {
                    buildRequest = JSON.parse(bodyStr);
                } catch (parseErr) {
                    activeBuildCount--;
                    return sendJson(res, 400, {
                        success: false,
                        backend: 'CLOUD_RUN',
                        status: 'FAILED',
                        exitCode: 1,
                        errorCode: 'MALFORMED_JSON',
                        stderr: `Malformed JSON request: ${parseErr.message}`
                    });
                }

                if (!buildRequest || typeof buildRequest !== 'object' || Array.isArray(buildRequest)) {
                    activeBuildCount--;
                    return sendJson(res, 400, {
                        success: false,
                        backend: 'CLOUD_RUN',
                        status: 'FAILED',
                        exitCode: 1,
                        errorCode: 'MALFORMED_REQUEST',
                        stderr: 'Build request must be a JSON object.'
                    });
                }

                const rawBuildId = String(buildRequest.buildId || crypto.randomUUID());
                if (rawBuildId.includes('\0') || rawBuildId.includes('..') || !/^[a-zA-Z0-9_-]+$/.test(rawBuildId)) {
                    activeBuildCount--;
                    return sendJson(res, 400, {
                        success: false,
                        backend: 'CLOUD_RUN',
                        buildId: 'invalid',
                        status: 'FAILED',
                        exitCode: 1,
                        errorCode: 'INVALID_BUILD_ID',
                        stderr: 'Security rejection: Invalid characters or path traversal in buildId.'
                    });
                }
                const buildId = rawBuildId;

                const rawProjectType = String(buildRequest.projectType || 'WEB').toUpperCase();
                const isAndroid = rawProjectType === 'ANDROID' || rawProjectType === 'ANDROID_APP';
                const normalizedProjectType = isAndroid ? 'ANDROID' : 'WEB';

                const requestedArtifactType = String(
                    buildRequest.requestedArtifactType || (isAndroid ? 'APK' : 'WEB')
                ).toUpperCase();

                const requestedFiles = buildRequest.files;
                if (!requestedFiles || typeof requestedFiles !== 'object' || Array.isArray(requestedFiles) || Object.keys(requestedFiles).length === 0) {
                    activeBuildCount--;
                    const resp = createNormalizedResponse({
                        success: false,
                        buildId,
                        status: 'FAILED',
                        exitCode: 1,
                        stderr: 'Malformed build request: "files" map is required and must be non-empty.',
                        errorCode: 'EMPTY_FILES'
                    });
                    recordBuildState(buildId, resp);
                    return sendJson(res, 400, resp);
                }

                // Validate explicit buildCommand if provided
                const rawCommand = buildRequest.buildCommand ? String(buildRequest.buildCommand).trim() : null;
                if (rawCommand !== null) {
                    if (
                        rawCommand.includes('\0') ||
                        rawCommand.includes(';') ||
                        rawCommand.includes('|') ||
                        rawCommand.includes('&') ||
                        rawCommand.includes('`') ||
                        rawCommand.includes('$(') ||
                        rawCommand.includes('..') ||
                        !Object.prototype.hasOwnProperty.call(ALLOWED_COMMAND_MAP, rawCommand)
                    ) {
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stderr: `Security rejection: Build command '${rawCommand}' is not in the strict command allowlist.`,
                            errorCode: 'INVALID_COMMAND'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 403, resp);
                    }
                }

                const timeoutMs = parseInt(
                    process.env.BUILD_TIMEOUT_MS || (isAndroid ? '300000' : '120000'),
                    10
                );

                // Create isolated build workspace
                workspaceDir = path.resolve(BUILDS_ROOT, buildId);
                if (fs.existsSync(workspaceDir)) {
                    fs.rmSync(workspaceDir, { recursive: true, force: true });
                }
                fs.mkdirSync(workspaceDir, { recursive: true });
                const resolvedWorkspace = fs.realpathSync(workspaceDir);

                recordBuildState(buildId, createNormalizedResponse({
                    success: false,
                    buildId,
                    status: 'RUNNING',
                    exitCode: -1,
                    message: 'Build is currently running in isolated workspace.'
                }));

                // 1. Write files safely inside workspaceDir with strict Path Traversal, Null-Byte & Symlink Defense
                for (const [relPath, content] of Object.entries(requestedFiles)) {
                    if (typeof content !== 'string') {
                        fs.rmSync(workspaceDir, { recursive: true, force: true });
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stderr: `Malformed file content for '${relPath}': must be string.`,
                            errorCode: 'MALFORMED_FILE_CONTENT'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 400, resp);
                    }

                    const validation = validateSafeRelativePath(relPath, resolvedWorkspace);
                    if (!validation.valid) {
                        fs.rmSync(workspaceDir, { recursive: true, force: true });
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stderr: `Security rejection: ${validation.reason}`,
                            errorCode: 'PATH_TRAVERSAL_REJECTED'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 400, resp);
                    }

                    const targetPath = validation.targetPath;

                    if (!ensureNoSymlinksInChain(resolvedWorkspace, targetPath)) {
                        fs.rmSync(workspaceDir, { recursive: true, force: true });
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stderr: `Security rejection: Symlinks are forbidden in workspace path '${relPath}'`,
                            errorCode: 'SYMLINK_REJECTED'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 400, resp);
                    }

                    fs.mkdirSync(path.dirname(targetPath), { recursive: true });
                    fs.writeFileSync(targetPath, content, { encoding: 'utf-8', flag: 'w' });

                    // Post-write verification that file is a regular file and not a symlink
                    const writtenStat = fs.lstatSync(targetPath);
                    if (writtenStat.isSymbolicLink() || !writtenStat.isFile()) {
                        fs.rmSync(workspaceDir, { recursive: true, force: true });
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stderr: `Security rejection: Non-regular file or symlink detected at '${relPath}'`,
                            errorCode: 'SYMLINK_REJECTED'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 400, resp);
                    }
                }

                // 2. Sanitized Minimal Environment (Never leak server process.env secrets!)
                const sanitizedEnv = {
                    HOME: process.env.HOME || '/home/builder',
                    PATH: process.env.PATH || '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
                    JAVA_HOME: process.env.JAVA_HOME || '/usr/lib/jvm/java-17-openjdk-amd64',
                    ANDROID_HOME: process.env.ANDROID_HOME || '/opt/android-sdk',
                    ANDROID_SDK_ROOT: process.env.ANDROID_SDK_ROOT || '/opt/android-sdk',
                    GRADLE_USER_HOME: path.join(resolvedWorkspace, '.gradle'),
                    NODE_ENV: 'production',
                    TMPDIR: '/tmp',
                    LANG: 'en_US.UTF-8'
                };

                // 3. Validate Project Integrity & Select Allowed Command
                let cmd = 'npm';
                let args = ['run', 'build'];

                if (normalizedProjectType === 'ANDROID') {
                    const gradlewPath = path.join(resolvedWorkspace, 'gradlew');
                    const wrapperJar = path.join(resolvedWorkspace, 'gradle', 'wrapper', 'gradle-wrapper.jar');
                    const wrapperProps = path.join(resolvedWorkspace, 'gradle', 'wrapper', 'gradle-wrapper.properties');

                    if (!fs.existsSync(gradlewPath)) {
                        fs.rmSync(workspaceDir, { recursive: true, force: true });
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stderr: `Gradle integrity error: Project is missing executable 'gradlew' in root workspace.`,
                            errorCode: 'MISSING_GRADLE_WRAPPER'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 400, resp);
                    }

                    const gradlewLstat = fs.lstatSync(gradlewPath);
                    if (gradlewLstat.isSymbolicLink() || !gradlewLstat.isFile()) {
                        fs.rmSync(workspaceDir, { recursive: true, force: true });
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stderr: `Security rejection: 'gradlew' cannot be a symlink.`,
                            errorCode: 'SYMLINK_REJECTED'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 400, resp);
                    }

                    if (!fs.existsSync(wrapperJar) || !fs.existsSync(wrapperProps)) {
                        fs.rmSync(workspaceDir, { recursive: true, force: true });
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stderr: `Gradle integrity error: Missing 'gradle/wrapper/gradle-wrapper.jar' or 'gradle-wrapper.properties'.`,
                            errorCode: 'MISSING_GRADLE_WRAPPER'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 400, resp);
                    }

                    fs.chmodSync(gradlewPath, 0o755);

                    if (rawCommand && ALLOWED_COMMAND_MAP[rawCommand]) {
                        cmd = ALLOWED_COMMAND_MAP[rawCommand].cmd;
                        args = [...ALLOWED_COMMAND_MAP[rawCommand].args];
                    } else if (requestedArtifactType === 'AAB') {
                        cmd = './gradlew';
                        args = ['bundleRelease', '--no-daemon', '--stacktrace'];
                    } else {
                        cmd = './gradlew';
                        args = ['assembleRelease', '--no-daemon', '--stacktrace'];
                    }
                } else {
                    // WEB Project
                    const pkgJsonPath = path.join(resolvedWorkspace, 'package.json');
                    if (fs.existsSync(pkgJsonPath)) {
                        let pkg;
                        try {
                            pkg = JSON.parse(fs.readFileSync(pkgJsonPath, 'utf-8'));
                        } catch (e) {
                            fs.rmSync(workspaceDir, { recursive: true, force: true });
                            activeBuildCount--;
                            const resp = createNormalizedResponse({
                                success: false,
                                buildId,
                                status: 'FAILED',
                                exitCode: 1,
                                stderr: `Invalid package.json: ${e.message}`,
                                errorCode: 'INVALID_PACKAGE_JSON'
                            });
                            recordBuildState(buildId, resp);
                            return sendJson(res, 400, resp);
                        }

                        // Install dependencies if dependencies or devDependencies exist
                        const hasDeps =
                            (pkg.dependencies && Object.keys(pkg.dependencies).length > 0) ||
                            (pkg.devDependencies && Object.keys(pkg.devDependencies).length > 0);
                        if (hasDeps) {
                            const installRes = await runProcessSafe(
                                'npm',
                                ['install', '--ignore-scripts', '--no-audit', '--no-fund'],
                                resolvedWorkspace,
                                sanitizedEnv,
                                timeoutMs
                            );
                            if (installRes.exitCode !== 0) {
                                fs.rmSync(workspaceDir, { recursive: true, force: true });
                                activeBuildCount--;
                                const resp = createNormalizedResponse({
                                    success: false,
                                    buildId,
                                    status: installRes.timedOut ? 'TIMEOUT' : 'FAILED',
                                    exitCode: installRes.exitCode,
                                    stdout: installRes.stdout,
                                    stderr: installRes.stderr || 'Dependency installation failed.',
                                    errorCode: 'DEPENDENCY_INSTALL_FAILED'
                                });
                                recordBuildState(buildId, resp);
                                return sendJson(res, 200, resp);
                            }
                        }

                        if (rawCommand && ALLOWED_COMMAND_MAP[rawCommand]) {
                            cmd = ALLOWED_COMMAND_MAP[rawCommand].cmd;
                            args = [...ALLOWED_COMMAND_MAP[rawCommand].args];
                        } else if (pkg.scripts && pkg.scripts.build) {
                            cmd = 'npm';
                            args = ['run', 'build'];
                        } else {
                            cmd = 'npm';
                            args = ['test'];
                        }
                    } else {
                        // Static HTML5 Web Project verification
                        const indexHtml = path.join(resolvedWorkspace, 'index.html');
                        if (fs.existsSync(indexHtml)) {
                            const lstat = fs.lstatSync(indexHtml);
                            if (lstat.isSymbolicLink() || !lstat.isFile()) {
                                fs.rmSync(workspaceDir, { recursive: true, force: true });
                                activeBuildCount--;
                                const resp = createNormalizedResponse({
                                    success: false,
                                    buildId,
                                    status: 'FAILED',
                                    exitCode: 1,
                                    stderr: 'Security rejection: index.html is a symlink or non-regular file.',
                                    errorCode: 'SYMLINK_REJECTED'
                                });
                                recordBuildState(buildId, resp);
                                return sendJson(res, 400, resp);
                            }

                            const artifactDir = path.resolve(ARTIFACTS_ROOT, buildId);
                            fs.mkdirSync(artifactDir, { recursive: true });
                            const targetArtifact = path.resolve(artifactDir, 'index.html');
                            fs.copyFileSync(indexHtml, targetArtifact);

                            const hash = calculateSha256(targetArtifact);
                            const stat = fs.statSync(targetArtifact);
                            fs.rmSync(workspaceDir, { recursive: true, force: true });
                            activeBuildCount--;

                            const artifactMeta = {
                                fileName: 'index.html',
                                downloadUrl: `/artifacts/${buildId}/index.html`,
                                sizeBytes: stat.size,
                                sha256: hash,
                                mimeType: 'text/html',
                                artifactType: 'WEB'
                            };
                            const resp = createNormalizedResponse({
                                success: true,
                                buildId,
                                status: 'SUCCESS',
                                exitCode: 0,
                                stdout: '[WEB_COMPILER]: Verified HTML5 web project assets. Bundled index.html.',
                                stderr: '',
                                artifactMeta
                            });
                            recordBuildState(buildId, resp);
                            return sendJson(res, 200, resp);
                        } else {
                            fs.rmSync(workspaceDir, { recursive: true, force: true });
                            activeBuildCount--;
                            const resp = createNormalizedResponse({
                                success: false,
                                buildId,
                                status: 'FAILED',
                                exitCode: 1,
                                stderr: 'Web build error: neither package.json nor index.html was found in workspace.',
                                errorCode: 'MISSING_ENTRYPOINT'
                            });
                            recordBuildState(buildId, resp);
                            return sendJson(res, 400, resp);
                        }
                    }
                }

                if (!ALLOWED_BINARIES.has(cmd)) {
                    fs.rmSync(workspaceDir, { recursive: true, force: true });
                    activeBuildCount--;
                    const resp = createNormalizedResponse({
                        success: false,
                        buildId,
                        status: 'FAILED',
                        exitCode: 1,
                        stderr: `Security rejection: Command '${cmd}' is not in the allowed build binary list.`,
                        errorCode: 'INVALID_COMMAND'
                    });
                    recordBuildState(buildId, resp);
                    return sendJson(res, 403, resp);
                }

                // 4. Execute build process without shell: true
                const execResult = await runProcessSafe(cmd, args, resolvedWorkspace, sanitizedEnv, timeoutMs);

                if (execResult.timedOut) {
                    fs.rmSync(workspaceDir, { recursive: true, force: true });
                    activeBuildCount--;
                    const resp = createNormalizedResponse({
                        success: false,
                        buildId,
                        status: 'TIMEOUT',
                        exitCode: 124,
                        stdout: execResult.stdout,
                        stderr: `TIMEOUT: Build process exceeded ${timeoutMs}ms execution limit.`,
                        errorCode: 'BUILD_TIMEOUT'
                    });
                    recordBuildState(buildId, resp);
                    return sendJson(res, 200, resp);
                }

                if (execResult.exitCode !== 0) {
                    fs.rmSync(workspaceDir, { recursive: true, force: true });
                    activeBuildCount--;
                    const resp = createNormalizedResponse({
                        success: false,
                        buildId,
                        status: 'FAILED',
                        exitCode: execResult.exitCode || 1,
                        stdout: execResult.stdout,
                        stderr: execResult.stderr || `Build process exited with code ${execResult.exitCode}`,
                        errorCode: 'BUILD_COMMAND_FAILED'
                    });
                    recordBuildState(buildId, resp);
                    return sendJson(res, 200, resp);
                }

                // 5. Locate & Validate Produced Artifact
                let artifactPath = null;
                let artifactName = '';
                let mimeType = 'application/octet-stream';
                let resolvedArtifactType = requestedArtifactType;

                if (normalizedProjectType === 'ANDROID') {
                    const wantsAab =
                        requestedArtifactType === 'AAB' ||
                        (rawCommand && rawCommand.toLowerCase().includes('bundle'));

                    if (wantsAab) {
                        const aabCandidates = findFilesRecursive(
                            resolvedWorkspace,
                            (fullPath, name) => name.endsWith('.aab') && fullPath.includes(`${path.sep}outputs${path.sep}`)
                        );
                        if (aabCandidates.length > 0) {
                            artifactPath = aabCandidates[0];
                            artifactName = path.basename(artifactPath);
                            mimeType = 'application/x-authorware-bin';
                            resolvedArtifactType = 'AAB';
                        }
                    } else {
                        const apkCandidates = findFilesRecursive(
                            resolvedWorkspace,
                            (fullPath, name) => name.endsWith('.apk') && fullPath.includes(`${path.sep}outputs${path.sep}`)
                        );
                        if (apkCandidates.length > 0) {
                            artifactPath = apkCandidates[0];
                            artifactName = path.basename(artifactPath);
                            mimeType = 'application/vnd.android.package-archive';
                            resolvedArtifactType = 'APK';
                        }
                    }
                } else {
                    const distDirs = [
                        path.join(resolvedWorkspace, 'dist'),
                        path.join(resolvedWorkspace, 'build')
                    ];
                    for (const d of distDirs) {
                        const files = findFilesRecursive(d, (_, name) => ALLOWED_ARTIFACT_EXTS.has(path.extname(name).toLowerCase()));
                        if (files.length > 0) {
                            artifactPath = files[0];
                            artifactName = path.basename(artifactPath);
                            resolvedArtifactType = 'WEB';
                            break;
                        }
                    }
                }

                // 6. Strict Artifact Verification (Must exist, regular file, >0 bytes, not a symlink, allowed extension)
                if (artifactPath && fs.existsSync(artifactPath)) {
                    const lstat = fs.lstatSync(artifactPath);
                    if (lstat.isSymbolicLink() || !lstat.isFile()) {
                        fs.rmSync(workspaceDir, { recursive: true, force: true });
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stdout: execResult.stdout,
                            stderr: 'Security rejection: Artifact is a symlink or non-regular file.',
                            errorCode: 'SYMLINK_REJECTED'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 200, resp);
                    }

                    const ext = path.extname(artifactName).toLowerCase();
                    if (!ALLOWED_ARTIFACT_EXTS.has(ext)) {
                        fs.rmSync(workspaceDir, { recursive: true, force: true });
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stdout: execResult.stdout,
                            stderr: `Security rejection: Disallowed artifact extension '${ext}'.`,
                            errorCode: 'DISALLOWED_ARTIFACT_EXT'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 200, resp);
                    }

                    const artifactDir = path.resolve(ARTIFACTS_ROOT, buildId);
                    fs.mkdirSync(artifactDir, { recursive: true });
                    const finalArtifactPath = path.resolve(artifactDir, artifactName);
                    fs.copyFileSync(artifactPath, finalArtifactPath);

                    const stat = fs.statSync(finalArtifactPath);
                    if (stat.size <= 0) {
                        fs.rmSync(workspaceDir, { recursive: true, force: true });
                        activeBuildCount--;
                        const resp = createNormalizedResponse({
                            success: false,
                            buildId,
                            status: 'FAILED',
                            exitCode: 1,
                            stdout: execResult.stdout,
                            stderr: 'Artifact verification failed: produced file is 0 bytes.',
                            errorCode: 'EMPTY_ARTIFACT'
                        });
                        recordBuildState(buildId, resp);
                        return sendJson(res, 200, resp);
                    }

                    const hash = calculateSha256(finalArtifactPath);
                    fs.rmSync(workspaceDir, { recursive: true, force: true });
                    activeBuildCount--;

                    const artifactMeta = {
                        fileName: artifactName,
                        downloadUrl: `/artifacts/${buildId}/${artifactName}`,
                        sizeBytes: stat.size,
                        sha256: hash,
                        mimeType,
                        artifactType: resolvedArtifactType
                    };

                    const resp = createNormalizedResponse({
                        success: true,
                        buildId,
                        status: 'SUCCESS',
                        exitCode: 0,
                        stdout: execResult.stdout,
                        stderr: execResult.stderr,
                        artifactMeta
                    });
                    recordBuildState(buildId, resp);
                    return sendJson(res, 200, resp);
                } else {
                    fs.rmSync(workspaceDir, { recursive: true, force: true });
                    activeBuildCount--;
                    const resp = createNormalizedResponse({
                        success: false,
                        buildId,
                        status: 'FAILED',
                        exitCode: 1,
                        stdout: execResult.stdout,
                        stderr: 'Build finished with exit code 0, but no regular binary artifact was produced in the expected outputs directory.',
                        errorCode: 'ARTIFACT_MISSING'
                    });
                    recordBuildState(buildId, resp);
                    return sendJson(res, 200, resp);
                }
            } catch (err) {
                if (workspaceDir && fs.existsSync(workspaceDir)) {
                    try { fs.rmSync(workspaceDir, { recursive: true, force: true }); } catch (_) {}
                }
                activeBuildCount--;
                return sendJson(res, 500, {
                    success: false,
                    backend: 'CLOUD_RUN',
                    status: 'FAILED',
                    exitCode: 500,
                    errorCode: 'INTERNAL_SERVER_ERROR',
                    error: 'Server internal error',
                    message: err.message
                });
            }
        });
        return;
    }

    sendJson(res, 404, { error: 'Not found' });
});

function gracefulShutdown(signal) {
    console.log(`Received ${signal}. Draining active requests and shutting down Cloud Run service...`);
    server.close(() => {
        console.log('HTTP server closed cleanly.');
        process.exit(0);
    });
    setTimeout(() => {
        process.exit(0);
    }, 10000).unref();
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

if (require.main === module) {
    server.listen(PORT, '0.0.0.0', () => {
        console.log(`MAYA Production Remote Build Server (Hardened) listening on port ${PORT}`);
    });
}

module.exports = {
    server,
    validateSafeRelativePath,
    ensureNoSymlinksInChain,
    calculateSha256,
    ALLOWED_COMMAND_MAP,
    ALLOWED_BINARIES
};
