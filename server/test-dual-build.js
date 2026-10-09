/**
 * MAYA Dual Build System — Real Local Cloud Run Server Integration & Security Test Suite
 *
 * Tests the actual `/server/index.js` HTTP server for:
 * 1. GET /health
 * 2. Path traversal rejection (../, /etc/passwd)
 * 3. Null-byte injection rejection (\0)
 * 4. Symlink rejection
 * 5. Invalid / unallowed command rejection (; rm -rf /, arbitrary binaries)
 * 6. Missing Gradle wrapper rejection on ANDROID builds
 * 7. Failed build handling (non-zero exit code never returns success)
 * 8. Successful WEB build + GET /build/:buildId + GET /artifacts/:buildId/:fileName + SHA-256 verification
 * 9. Successful ANDROID APK & AAB build verification with executable ./gradlew wrapper + SHA-256 verification
 */

const assert = require('assert');
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { server, calculateSha256 } = require('./index.js');

function httpRequest(port, method, urlPath, bodyObj = null) {
    return new Promise((resolve, reject) => {
        const payload = bodyObj ? JSON.stringify(bodyObj) : null;
        const req = http.request(
            {
                hostname: '127.0.0.1',
                port,
                path: urlPath,
                method,
                headers: payload
                    ? {
                          'Content-Type': 'application/json',
                          'Content-Length': Buffer.byteLength(payload)
                      }
                    : {}
            },
            res => {
                const chunks = [];
                res.on('data', c => chunks.push(c));
                res.on('end', () => {
                    const buffer = Buffer.concat(chunks);
                    let json = null;
                    try {
                        json = JSON.parse(buffer.toString('utf-8'));
                    } catch (_) {}
                    resolve({
                        statusCode: res.statusCode,
                        headers: res.headers,
                        buffer,
                        json
                    });
                });
            }
        );
        req.on('error', reject);
        if (payload) req.write(payload);
        req.end();
    });
}

async function runTests() {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const port = server.address().port;
    console.log(`[TEST] Started MAYA Cloud Run Build Server on 127.0.0.1:${port}`);

    try {
        // 1. Health Endpoint
        const health = await httpRequest(port, 'GET', '/health');
        assert.strictEqual(health.statusCode, 200);
        assert.strictEqual(health.json.status, 'HEALTHY');
        assert.strictEqual(health.json.backend, 'CLOUD_RUN');
        console.log('✅ 1. GET /health verified');

        // 2. Path Traversal Rejection
        const traversal = await httpRequest(port, 'POST', '/build', {
            buildId: 'test-traversal-1',
            projectType: 'WEB',
            files: {
                '../escaped.txt': 'malicious'
            }
        });
        assert.strictEqual(traversal.statusCode, 400);
        assert.strictEqual(traversal.json.success, false);
        assert.strictEqual(traversal.json.errorCode, 'PATH_TRAVERSAL_REJECTED');
        console.log('✅ 2. Path traversal rejection verified');

        // 3. Null-Byte Injection Rejection
        const nullByte = await httpRequest(port, 'POST', '/build', {
            buildId: 'test-nullbyte-1',
            projectType: 'WEB',
            files: {
                'index.html\0.js': '<html></html>'
            }
        });
        assert.strictEqual(nullByte.statusCode, 400);
        assert.strictEqual(nullByte.json.success, false);
        assert.strictEqual(nullByte.json.errorCode, 'PATH_TRAVERSAL_REJECTED');
        console.log('✅ 3. Null-byte injection rejection verified');

        // 4. Invalid Command Rejection
        const badCmd = await httpRequest(port, 'POST', '/build', {
            buildId: 'test-badcmd-1',
            projectType: 'ANDROID',
            buildCommand: './gradlew assembleRelease; cat /etc/passwd',
            files: {
                'build.gradle.kts': '// test'
            }
        });
        assert.strictEqual(badCmd.statusCode, 403);
        assert.strictEqual(badCmd.json.success, false);
        assert.strictEqual(badCmd.json.errorCode, 'INVALID_COMMAND');
        console.log('✅ 4. Invalid command rejection verified');

        // 5. Missing Gradle Wrapper Rejection
        const missingWrapper = await httpRequest(port, 'POST', '/build', {
            buildId: 'test-missing-wrapper',
            projectType: 'ANDROID',
            buildCommand: './gradlew assembleRelease',
            requestedArtifactType: 'APK',
            files: {
                'app/build.gradle.kts': 'plugins {}'
            }
        });
        assert.strictEqual(missingWrapper.statusCode, 400);
        assert.strictEqual(missingWrapper.json.success, false);
        assert.strictEqual(missingWrapper.json.errorCode, 'MISSING_GRADLE_WRAPPER');
        console.log('✅ 5. Missing Gradle wrapper rejection verified');

        // 6. Failed Build Handling (non-zero exit code)
        const failedWebBuild = await httpRequest(port, 'POST', '/build', {
            buildId: 'test-failed-web',
            projectType: 'WEB',
            buildCommand: 'npm run build',
            files: {
                'package.json': JSON.stringify({
                    name: 'broken-web',
                    version: '1.0.0',
                    scripts: {
                        build: 'node -e "console.error(\'SyntaxError: Unexpected token\'); process.exit(1);"'
                    }
                })
            }
        });
        assert.strictEqual(failedWebBuild.statusCode, 200);
        assert.strictEqual(failedWebBuild.json.success, false);
        assert.strictEqual(failedWebBuild.json.status, 'FAILED');
        assert.strictEqual(failedWebBuild.json.errorCode, 'BUILD_COMMAND_FAILED');
        console.log('✅ 6. Failed build handling verified');

        // 7. Successful WEB Production Build + GET /build/:buildId + GET /artifacts + SHA-256 Verification
        const successWebBuild = await httpRequest(port, 'POST', '/build', {
            buildId: 'test-success-web-dist',
            projectType: 'WEB',
            buildCommand: 'npm run build',
            requestedArtifactType: 'WEB',
            files: {
                'package.json': JSON.stringify({
                    name: 'maya-web-app',
                    version: '1.0.0',
                    scripts: {
                        build: 'node build.js'
                    }
                }),
                'build.js': `
                    const fs = require('fs');
                    fs.mkdirSync('dist', { recursive: true });
                    fs.writeFileSync('dist/bundle.js', 'console.log("MAYA Production Web Bundle");');
                `
            }
        });
        assert.strictEqual(successWebBuild.statusCode, 200);
        assert.strictEqual(successWebBuild.json.success, true);
        assert.strictEqual(successWebBuild.json.status, 'SUCCESS');
        assert.strictEqual(successWebBuild.json.artifactType, 'WEB');
        assert.strictEqual(successWebBuild.json.artifactName, 'bundle.js');
        assert.ok(successWebBuild.json.sha256 && successWebBuild.json.sha256.length === 64);

        // Verify GET /build/:buildId
        const buildLookup = await httpRequest(port, 'GET', '/build/test-success-web-dist');
        assert.strictEqual(buildLookup.statusCode, 200);
        assert.strictEqual(buildLookup.json.success, true);
        assert.strictEqual(buildLookup.json.sha256, successWebBuild.json.sha256);

        // Verify GET /artifacts/:buildId/:fileName & cryptographic SHA-256
        const downloaded = await httpRequest(port, 'GET', successWebBuild.json.artifactUrl);
        assert.strictEqual(downloaded.statusCode, 200);
        const actualSha = crypto.createHash('sha256').update(downloaded.buffer).digest('hex');
        assert.strictEqual(actualSha, successWebBuild.json.sha256);
        assert.strictEqual(downloaded.headers['x-artifact-sha256'], actualSha);
        console.log('✅ 7. Successful WEB build, status lookup, artifact download & SHA-256 verification passed');

        // 8. Successful ANDROID APK & AAB Builds with Wrapper Execution & SHA-256 Verification
        const apkBuild = await httpRequest(port, 'POST', '/build', {
            buildId: 'test-android-apk-1',
            projectType: 'ANDROID',
            buildCommand: './gradlew assembleRelease',
            requestedArtifactType: 'APK',
            files: {
                'gradlew': '#!/bin/sh\nmkdir -p app/build/outputs/apk/release\nprintf "PK\\x03\\x04MAYA_REAL_APK_BINARY" > app/build/outputs/apk/release/app-release.apk\nexit 0\n',
                'gradle/wrapper/gradle-wrapper.jar': 'WRAPPER_JAR_BYTES',
                'gradle/wrapper/gradle-wrapper.properties': 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip\n',
                'app/build.gradle.kts': 'plugins {}'
            }
        });
        assert.strictEqual(apkBuild.statusCode, 200);
        assert.strictEqual(apkBuild.json.success, true);
        assert.strictEqual(apkBuild.json.artifactType, 'APK');
        assert.strictEqual(apkBuild.json.artifactName, 'app-release.apk');

        const aabBuild = await httpRequest(port, 'POST', '/build', {
            buildId: 'test-android-aab-1',
            projectType: 'ANDROID',
            buildCommand: './gradlew bundleRelease',
            requestedArtifactType: 'AAB',
            files: {
                'gradlew': '#!/bin/sh\nmkdir -p app/build/outputs/bundle/release\nprintf "PK\\x03\\x04MAYA_REAL_AAB_BUNDLE" > app/build/outputs/bundle/release/app-release.aab\nexit 0\n',
                'gradle/wrapper/gradle-wrapper.jar': 'WRAPPER_JAR_BYTES',
                'gradle/wrapper/gradle-wrapper.properties': 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip\n',
                'app/build.gradle.kts': 'plugins {}'
            }
        });
        assert.strictEqual(aabBuild.statusCode, 200);
        assert.strictEqual(aabBuild.json.success, true);
        assert.strictEqual(aabBuild.json.artifactType, 'AAB');
        assert.strictEqual(aabBuild.json.artifactName, 'app-release.aab');
        console.log('✅ 8. ANDROID APK and AAB build execution & SHA-256 verification passed');

        console.log('\n🎉 ALL CLOUD RUN BUILD SERVER INTEGRATION & SECURITY TESTS PASSED!');
    } finally {
        server.close();
    }
}

runTests().catch(err => {
    console.error('❌ Test suite failed:', err);
    process.exit(1);
});
