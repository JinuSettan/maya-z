const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const { redactSecrets } = require('./security');
const { DEFAULT_WORKSPACE, resolvePath } = require('./filesystem');

const ALLOWED_BINARIES = new Set([
  'git', 'cargo', 'gradle', 'cmake', 'msbuild', 'dotnet',
  'npm', 'pnpm', 'yarn', 'python3', 'python', 'node',
  'npx', 'make', 'pytest', 'go', 'rustc',
  'ls', 'cat', 'head', 'tail', 'echo', 'mkdir', 'diff'
]);

const BLOCKED_ARGUMENTS = /[;&|<>`\n\r]/;

function tokenize(command) {
  if (typeof command !== 'string' || !command.trim()) {
    throw new Error('Command is required');
  }

  if (command.length > 4000) {
    throw new Error('Command is too long');
  }

  if (BLOCKED_ARGUMENTS.test(command) || command.includes('$(')) {
    throw new Error('Shell operators and command substitution are blocked');
  }

  const args = [];
  let current = '';
  let quote = null;
  let escaped = false;

  for (const char of command) {
    if (escaped) {
      current += char;
      escaped = false;
      continue;
    }

    if (char === '\\' && quote !== "'") {
      escaped = true;
      continue;
    }

    if (quote) {
      if (char === quote) {
        quote = null;
      } else {
        current += char;
      }
      continue;
    }

    if (char === '"' || char === "'") {
      quote = char;
      continue;
    }

    if (/\s/.test(char)) {
      if (current.length) {
        args.push(current);
        current = '';
      }
      continue;
    }

    current += char;
  }

  if (escaped || quote) {
    throw new Error('Unclosed quote or escape in command');
  }

  if (current.length) args.push(current);

  if (!args.length) throw new Error('Command is required');

  return args;
}

function runTerminal(command, cwd = '.', timeoutSec = 60) {
  return new Promise(resolve => {
    let args;

    try {
      args = tokenize(command);
    } catch (error) {
      return resolve({
        success: false,
        output: error.message,
        exitCode: 2
      });
    }

    const requestedBinary = args.shift();
    const binary = path.basename(requestedBinary)
      .toLowerCase()
      .replace(/\.exe$/i, '');

    if (
      requestedBinary.includes('/') ||
      requestedBinary.includes('\\') ||
      !ALLOWED_BINARIES.has(binary)
    ) {
      return resolve({
        success: false,
        output: `COMMAND_NOT_ALLOWLISTED: '${requestedBinary}' is not permitted.`,
        exitCode: 2
      });
    }

    let workingDirectory;

    try {
      workingDirectory = resolvePath(cwd || '.', { allowMissing: false });

      if (!fs.statSync(workingDirectory).isDirectory()) {
        throw new Error('Working directory must be a directory');
      }
    } catch (error) {
      return resolve({
        success: false,
        output: `INVALID_WORKING_DIRECTORY: ${error.message}`,
        exitCode: 2
      });
    }

    const seconds = Number(timeoutSec);
    const timeoutMs = Math.max(
      1000,
      Math.min(Number.isFinite(seconds) ? seconds : 60, 300) * 1000
    );

    let output = '';
    let settled = false;
    let timedOut = false;

    const finish = result => {
      if (settled) return;
      settled = true;

      resolve({
        ...result,
        output: redactSecrets(output).slice(0, 64000)
      });
    };

    let child;

    try {
      child = spawn(binary, args, {
        cwd: workingDirectory,
        shell: false,
        windowsHide: true,
        env: {
          PATH: process.env.PATH || '',
          HOME: process.env.HOME || '',
          USERPROFILE: process.env.USERPROFILE || '',
          SYSTEMROOT: process.env.SYSTEMROOT || '',
          TEMP: process.env.TEMP || '',
          TMP: process.env.TMP || '',
          CI: '1'
        },
        stdio: ['ignore', 'pipe', 'pipe']
      });
    } catch (error) {
      output = error.message;
      return finish({ success: false, exitCode: 1 });
    }

    const append = chunk => {
      if (output.length < 64000) {
        output += chunk.toString('utf8').slice(0, 64000 - output.length);
      }
    };

    child.stdout.on('data', append);
    child.stderr.on('data', append);

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill();
    }, timeoutMs);

    child.on('error', error => {
      clearTimeout(timer);
      output += `\nUnable to start command: ${error.message}`;
      finish({ success: false, exitCode: 1 });
    });

    child.on('close', code => {
      clearTimeout(timer);

      if (timedOut) {
        output += '\nCommand timed out and was stopped.';
      }

      finish({
        success: !timedOut && code === 0,
        exitCode: timedOut ? 124 : (code ?? 1)
      });
    });
  });
}

module.exports = {
  runTerminal,
  tokenize,
  ALLOWED_BINARIES
};
