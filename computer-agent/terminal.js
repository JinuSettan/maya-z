const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const { redactSecrets } = require('./security');
const { DEFAULT_WORKSPACE, resolvePath } = require('./filesystem');

const { validateArgument } = require('./security');

// Interpreters (python, node, npx) are deliberately NOT allowed: they can run
// any code and read any file, which defeats the workspace restriction.
// Approved automation (e.g. a WhatsApp reader) runs through runSkillScript()
// from a fixed, user-owned directory instead.
//
// Note: build tools (npm/pnpm/gradle/make/cargo/...) execute build scripts that
// live in the workspace. Only point this agent at workspaces you trust.
const SUBCOMMANDS = (...names) => args => {
  const sub = args.find(a => !a.startsWith('-'));
  return sub && names.includes(sub) ? null : `subcommand must be one of: ${names.join(', ')}`;
};

const POLICIES = {
  git: args => {
    const blocked = ['-c', '--config-env', '--exec-path', '--upload-pack', '--receive-pack', '--ext-diff', '--paginate'];
    if (args.some(a => blocked.includes(a) || a.startsWith('--upload-pack=') || a.startsWith('--exec-path='))) {
      return 'git option is not allowed';
    }
    return SUBCOMMANDS('status', 'log', 'diff', 'add', 'commit', 'branch', 'checkout', 'switch', 'show', 'init')(args);
  },
  pnpm: args => (args[0] === 'run'
    ? (['build', 'test', 'lint', 'typecheck'].includes(args[1]) ? null : 'only run build/test/lint/typecheck')
    : SUBCOMMANDS('install', 'i', 'test', 'build')(args)),
  npm: args => (args[0] === 'run'
    ? (['build', 'test', 'lint', 'typecheck'].includes(args[1]) ? null : 'only run build/test/lint/typecheck')
    : SUBCOMMANDS('install', 'ci', 'test')(args)),
  yarn: args => (args[0] === 'run'
    ? (['build', 'test', 'lint', 'typecheck'].includes(args[1]) ? null : 'only run build/test/lint/typecheck')
    : SUBCOMMANDS('install', 'test', 'build')(args)),
  gradle: args => (args.every(a => a.startsWith('-') || /^(assemble\w*|build|test|clean|tasks|check)$/.test(a)) ? null : 'unsupported gradle task'),
  cargo: SUBCOMMANDS('build', 'test', 'check'),
  go: SUBCOMMANDS('build', 'test', 'vet', 'mod'),
  dotnet: SUBCOMMANDS('build', 'test', 'restore'),
  cmake: () => null,
  make: () => null,
  msbuild: () => null,
  pytest: () => null,
  rustc: () => null,
  ls: () => null,
  cat: () => null,
  head: () => null,
  tail: () => null,
  diff: () => null,
  mkdir: () => null,
  echo: () => null
};

const ALLOWED_BINARIES = new Set(Object.keys(POLICIES));
// echo never opens files, so its arguments are free text.
const NO_PATH_CHECK = new Set(['echo']);

function checkPolicy(binary, args) {
  const policy = POLICIES[binary];
  if (!policy) return `'${binary}' is not permitted.`;

  if (!NO_PATH_CHECK.has(binary)) {
    for (const arg of args) {
      const check = validateArgument(arg);
      if (!check.ok) return check.error;
    }
  }

  return policy(args);
}

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

    const policyError = checkPolicy(binary, args);

    if (policyError) {
      return resolve({
        success: false,
        output: `COMMAND_NOT_ALLOWED: ${policyError}`,
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

const SCRIPTS_DIR = path.resolve(
  process.env.TESSA_SCRIPTS_DIR || path.join(__dirname, 'approved-scripts')
);

// Runs a script from SCRIPTS_DIR by name. The directory is owned by the user
// and is outside the workspace, so the agent cannot create or edit scripts.
function runSkillScript(name, args = [], timeoutSec = 120) {
  if (typeof name !== 'string' || !/^[a-z0-9_-]{1,50}$/.test(name)) {
    return Promise.resolve({ success: false, output: 'Invalid script name', exitCode: 2 });
  }
  if (!Array.isArray(args) || args.length > 20 || args.some(a => typeof a !== 'string' || validateArgument(a).ok === false || a.length > 500)) {
    return Promise.resolve({ success: false, output: 'Invalid script arguments', exitCode: 2 });
  }

  const scriptPath = path.join(SCRIPTS_DIR, `${name}.js`);
  if (!fs.existsSync(scriptPath)) {
    return Promise.resolve({ success: false, output: `Approved script not found: ${name}`, exitCode: 2 });
  }

  return new Promise(resolve => {
    let output = '';
    const child = spawn(process.execPath, [scriptPath, ...args], {
      cwd: DEFAULT_WORKSPACE,
      shell: false,
      env: { PATH: process.env.PATH || '', HOME: process.env.HOME || '', TESSA_WORKSPACE: DEFAULT_WORKSPACE },
      stdio: ['ignore', 'pipe', 'pipe']
    });
    const append = c => { if (output.length < 64000) output += c.toString('utf8'); };
    child.stdout.on('data', append);
    child.stderr.on('data', append);
    const timer = setTimeout(() => child.kill(), Math.min(Math.max(Number(timeoutSec) || 120, 1), 600) * 1000);
    child.on('error', e => { clearTimeout(timer); resolve({ success: false, output: e.message, exitCode: 1 }); });
    child.on('close', code => {
      clearTimeout(timer);
      resolve({ success: code === 0, output: redactSecrets(output).slice(0, 64000), exitCode: code ?? 1 });
    });
  });
}

module.exports = {
  runTerminal,
  runSkillScript,
  checkPolicy,
  tokenize,
  ALLOWED_BINARIES
};
     
