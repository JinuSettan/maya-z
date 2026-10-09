const { exec } = require('child_process');
const { redactSecrets } = require('./security');
const { DEFAULT_WORKSPACE } = require('./filesystem');

const ALLOWED_BINARIES = new Set([
  'git', 'cargo', 'gradle', 'cmake', 'msbuild', 'dotnet',
  'npm', 'pnpm', 'yarn', 'python3', 'python', 'node', 'npx',
  'unreal', 'make', 'pytest', 'go', 'rustc',
  'dir', 'ls', 'cat', 'head', 'tail', 'echo', 'mkdir', 'diff'
]);

function runTerminal(command, cwd = DEFAULT_WORKSPACE, timeoutSec = 60) {
  return new Promise((resolve) => {
    const binary = command.trim().split(/\s+/)[0].toLowerCase().replace(/\\/g, '/').split('/').pop().replace('.exe', '');
    if (!ALLOWED_BINARIES.has(binary)) {
      return resolve({
        success: false,
        output: `COMMAND_NOT_ALLOWLISTED: Binary '${binary}' is not allowed in safe terminal.`
      });
    }

    const child = exec(command, {
      cwd,
      timeout: timeoutSec * 1000,
      maxBuffer: 64 * 1024
    }, (error, stdout, stderr) => {
      const combined = (stdout || '') + (stderr ? '\n' + stderr : '');
      const sanitized = redactSecrets(combined);
      if (error) {
        return resolve({
          success: false,
          output: sanitized || error.message,
          exitCode: error.code || 1
        });
      }
      resolve({
        success: true,
        output: sanitized,
        exitCode: 0
      });
    });
  });
}

module.exports = {
  runTerminal
};
