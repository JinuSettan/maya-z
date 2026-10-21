
'use strict';

const express = require('express');
const auth = require('./auth');
const filesystem = require('./filesystem');
const terminal = require('./terminal');
const browser = require('./browser');
const unreal = require('./unreal');
const { redactSecrets } = require('./security');

const app = express();
const PORT = Number(process.env.PORT || 8990);
const HOST = process.env.HOST || '0.0.0.0';

app.disable('x-powered-by');

app.use(express.json({
  limit: '1mb',
  strict: true,
  verify(req, res, buffer) {
    req.rawBody = buffer.toString('utf8');
  }
}));

const pairAttempts = new Map();

function allowPairAttempt(ip) {
  const now = Date.now();
  const recent = (pairAttempts.get(ip) || []).filter(
    timestamp => now - timestamp < 60 * 1000
  );

  if (recent.length >= 8) {
    pairAttempts.set(ip, recent);
    return false;
  }

  recent.push(now);
  pairAttempts.set(ip, recent);
  return true;
}

function sessionComputer(req) {
  return auth.verifySession(req.headers['x-tessa-session']);
}

function hasValidSignature(req, secretKey) {
  const signature = req.headers['x-tessa-signature'];

  return Boolean(
    req.rawBody !== undefined &&
    typeof secretKey === 'string' &&
    typeof signature === 'string' &&
    auth.verifySignature(req.rawBody, secretKey, signature)
  );
}

function unauthorized(res, message = 'Unauthorized') {
  return res.status(401).json({
    success: false,
    error: message
  });
}

app.get('/api/tessa/health', (req, res) => {
  if (!sessionComputer(req)) {
    return unauthorized(res);
  }

  return res.json({
    status: 'OK',
    timestamp: Date.now(),
    service: 'TESSA Local Computer Agent'
  });
});

app.post('/api/tessa/pair', (req, res) => {
  const ip = req.ip || req.socket.remoteAddress || 'unknown';

  if (!allowPairAttempt(ip)) {
    return res.status(429).json({
      success: false,
      error: 'Too many pairing attempts. Try again later.'
    });
  }

  const { computerId, deviceName, pairingToken } = req.body || {};

  if (
    typeof computerId !== 'string' ||
    typeof pairingToken !== 'string' ||
    !computerId ||
    !pairingToken
  ) {
    return res.status(400).json({
      success: false,
      error: 'computerId and pairingToken are required'
    });
  }

  if (!auth.isValidPairingToken(pairingToken)) {
    return unauthorized(res, 'Invalid pairing token');
  }

  try {
    const sessionToken = auth.pair(
      computerId,
      deviceName || 'Host Computer',
      pairingToken
    );

    return res.json({
      success: true,
      sessionToken
    });
  } catch (error) {
    return res.status(400).json({
      success: false,
      error: error.message
    });
  }
});

app.get('/api/tessa/capabilities', (req, res) => {
  if (!sessionComputer(req)) {
    return unauthorized(res);
  }

  return res.json({
    capabilities: [
      'FILE_READ',
      'FILE_WRITE',
      'FILE_CREATE',
      'FILE_DELETE',
      'DIRECTORY_CREATE',
      'TERMINAL',
      'BROWSER',
      'UNREAL_ENGINE',
      'BUILD',
      'GIT'
    ],
    restrictions: {
      workspaceOnly: true,
      terminalAllowlist: true,
      shellChaining: false,
      browserAutomation: false
    }
  });
});

app.post('/api/tessa/revoke', (req, res) => {
  const computer = sessionComputer(req);

  if (!computer) {
    return unauthorized(res, 'Invalid or expired session');
  }

  if (!hasValidSignature(req, computer.pairingToken)) {
    return unauthorized(res, 'Invalid request signature');
  }

  const revoked = auth.revoke(computer.sessionToken);

  if (!revoked) {
    return unauthorized(res, 'Session could not be revoked');
  }

  return res.json({
    success: true,
    outputSummary: 'Computer session revoked'
  });
});

app.post('/api/tessa/action', async (req, res) => {
  const computer = sessionComputer(req);

  if (!computer) {
    return unauthorized(res, 'Invalid or expired session');
  }

  if (!hasValidSignature(req, computer.pairingToken)) {
    return unauthorized(res, 'Invalid request signature');
  }

  const body = req.body || {};
  const { action, target, taskId } = body;
  const parameters = body.parameters || {};

  if (
    !parameters ||
    typeof parameters !== 'object' ||
    Array.isArray(parameters)
  ) {
    return res.status(400).json({
      success: false,
      outputSummary: 'parameters must be an object'
    });
  }

  try {
    switch (action) {
      case 'READ_FILE': {
        const content = filesystem.readFile(target);

        return res.json({
          success: true,
          outputSummary: redactSecrets(content)
        });
      }

      case 'WRITE_FILE': {
        if (typeof parameters.content !== 'string') {
          return res.status(400).json({
            success: false,
            outputSummary: 'parameters.content must be a string'
          });
        }

        const result = filesystem.writeFile(
          target,
          parameters.content
        );

        return res.json({
          success: true,
          outputSummary:
            `Wrote ${result.bytesWritten} bytes to workspace file ${target}`
        });
      }

      case 'DELETE_FILE': {
        filesystem.deleteFile(target);

        return res.json({
          success: true,
          outputSummary: `Deleted workspace item: ${target}`
        });
      }

      case 'LIST_FILES': {
        const files = filesystem.listFiles(target || '.');

        return res.json({
          success: true,
          outputSummary: JSON.stringify(files)
        });
      }

      case 'TERMINAL_EXEC': {
        if (typeof parameters.command !== 'string') {
          return res.status(400).json({
            success: false,
            outputSummary: 'parameters.command must be a string'
          });
        }

        const result = await terminal.runTerminal(
          parameters.command,
          parameters.cwd || '.',
          parameters.timeoutSec || 60
        );

        return res.json({
          success: result.success,
          outputSummary: result.output,
          data: {
            exitCode: result.exitCode
          }
        });
      }

      case 'OPEN_URL': {
        if (typeof target !== 'string') {
          return res.status(400).json({
            success: false,
            outputSummary: 'URL target is required'
          });
        }

        let parsed;

        try {
          parsed = new URL(target);
        } catch {
          return res.status(400).json({
            success: false,
            outputSummary: 'Invalid URL'
          });
        }

        if (!['http:', 'https:'].includes(parsed.protocol)) {
          return res.status(400).json({
            success: false,
            outputSummary: 'Only HTTP and HTTPS URLs are allowed'
          });
        }

        const page = await browser.openUrl(
          String(taskId || 'default').slice(0, 100),
          parsed.toString()
        );

        return res.json({
          success: true,
          outputSummary: page.textContent,
          data: {
            url: page.url,
            title: page.title,
            browserAutomationAvailable: false
          }
        });
      }

      case 'UNREAL_DETECT': {
        const engine = unreal.detectUnrealEngine();

        return res.json({
          success: true,
          outputSummary: engine.isInstalled
            ? `Detected Unreal Engine ${engine.engineVersion}`
            : 'Unreal Engine installation was not detected',
          data: engine
        });
      }

      case 'UNREAL_CREATE_PROJECT': {
        const projectName = parameters.projectName;

        if (
          typeof projectName !== 'string' ||
          !/^[A-Za-z][A-Za-z0-9_]{0,49}$/.test(projectName)
        ) {
          return res.status(400).json({
            success: false,
            outputSummary:
              'Invalid project name. Use letters, numbers, and underscores.'
          });
        }

        const parent = filesystem.resolvePath(
          parameters.targetFolder || '.'
        );

        const project = unreal.createUnrealProject(
          projectName,
          parent
        );

        return res.json({
          success: true,
          outputSummary:
            `Created project scaffold: ${project.projectName}`,
          data: project
        });
      }

      default:
        return res.status(404).json({
          success: false,
          outputSummary: 'Unknown or unsupported action'
        });
    }
  } catch (error) {
    console.error(
      '[TESSA action error]',
      redactSecrets(
        error && error.stack ? error.stack : String(error)
      )
    );

    return res.status(500).json({
      success: false,
      outputSummary: 'Action failed. Check the local agent log.',
      errorCode: 'EXEC_ERROR'
    });
  }
});

app.use((error, req, res, next) => {
  if (res.headersSent) {
    return next(error);
  }

  if (error && error.type === 'entity.too.large') {
    return res.status(413).json({
      success: false,
      error: 'Request body too large'
    });
  }

  if (error instanceof SyntaxError && 'body' in error) {
    return res.status(400).json({
      success: false,
      error: 'Invalid JSON request'
    });
  }

  console.error(
    '[TESSA server error]',
    redactSecrets(String(error))
  );

  return res.status(500).json({
    success: false,
    error: 'Internal server error'
  });
});

app.listen(PORT, HOST, () => {
  console.log(
    `TESSA Local Computer Agent listening on ${HOST}:${PORT}`
  );
  console.log(`Workspace: ${filesystem.DEFAULT_WORKSPACE}`);
  console.log('Keep this service on a trusted private network only.');
});
