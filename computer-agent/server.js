
'use strict';

const express = require('express');
const auth = require('./auth');
const filesystem = require('./filesystem');
const terminal = require('./terminal');
const browser = require('./browser');
const unreal = require('./unreal');
const report = require('./skills/report');
const { redactSecrets } = require('./security');

const app = express();
const PORT = Number(process.env.PORT || 8990);
const HOST = process.env.HOST || '0.0.0.0';

app.disable('x-powered-by');

// Only devices on a private network (home Wi-Fi, Tailscale) may connect.
// Set TESSA_ALLOW_PUBLIC=1 to turn this off (not recommended).
function isPrivateAddress(address) {
  const ip = String(address || '').replace(/^::ffff:/, '');
  return (
    ip === '::1' ||
    /^127\./.test(ip) ||
    /^10\./.test(ip) ||
    /^192\.168\./.test(ip) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(ip) ||
    /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(ip) || // Tailscale / CGNAT
    /^fe80:/i.test(ip) ||
    /^f[cd][0-9a-f]{2}:/i.test(ip)
  );
}

app.use((req, res, next) => {
  if (process.env.TESSA_ALLOW_PUBLIC === '1' || isPrivateAddress(req.socket.remoteAddress)) {
    return next();
  }
  return res.status(403).json({ success: false, error: 'Only private-network clients are allowed' });
});

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

  // Forget addresses that have been quiet for a minute so the map cannot grow forever.
  for (const [key, times] of pairAttempts) {
    if (!times.some(t => now - t < 60 * 1000)) pairAttempts.delete(key);
  }

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
  return Boolean(
    req.rawBody !== undefined &&
    typeof secretKey === 'string' &&
    auth.verifySignedRequest(req.rawBody, secretKey, {
      timestamp: req.headers['x-tessa-timestamp'],
      nonce: req.headers['x-tessa-nonce'],
      signature: req.headers['x-tessa-signature']
    })
  );
}

function unauthorized(res, message = 'Unauthorized') {
  return res.status(401).json({
    success: false,
    error: message
  });
}

function structuredParameter(parameters, key, fallback) {
  const value = parameters[key];
  if (value === undefined || value === null || value === '') return fallback;
  if (typeof value !== 'string') return value;
  try { return JSON.parse(value); } catch { return fallback; }
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
      'RUN_SKILL_SCRIPT',
      'REPORT',
      'OUTBOX',
      'BROWSER',
      'UNREAL_ENGINE',
      'BUILD',
      'GIT'
    ],
    restrictions: {
      workspaceOnly: true,
      terminalAllowlist: true,
      terminalInterpreters: false,
      replayProtection: true,
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

      case 'RUN_SKILL_SCRIPT': {
        const result = await terminal.runSkillScript(
          parameters.name,
          parameters.args || [],
          parameters.timeoutSec || 120
        );

        return res.json({
          success: result.success,
          outputSummary: result.output,
          data: { exitCode: result.exitCode }
        });
      }

      case 'REPORT_CLASSIFY': {
        return res.json({
          success: true,
          outputSummary: 'Classified request',
          data: report.classifyRequest(parameters.text)
        });
      }

      case 'REPORT_FILTER_CHATS': {
        const messages = structuredParameter(parameters, 'messages', []);
        const allowList = structuredParameter(parameters, 'allowList', []);
        if (!Array.isArray(messages) || !Array.isArray(allowList)) {
          return res.status(400).json({ success: false, outputSummary: 'messages and allowList must be JSON arrays' });
        }
        const allowed = report.filterAllowedChats(messages, allowList);

        return res.json({
          success: true,
          outputSummary: `${allowed.length} message(s) from allowed chats`,
          data: { messages: allowed }
        });
      }

      case 'REPORT_CREATE': {
        if (typeof parameters.title !== 'string' || !parameters.title.trim() || parameters.title.length > 300) {
          return res.status(400).json({ success: false, outputSummary: 'title is required and must be at most 300 characters' });
        }
        const sections = structuredParameter(parameters, 'sections', []);
        const sources = structuredParameter(parameters, 'sources', []);
        if (!Array.isArray(sections) || !Array.isArray(sources)) {
          return res.status(400).json({ success: false, outputSummary: 'sections and sources must be JSON arrays' });
        }
        const created = await report.createReport({
          title: parameters.title,
          summary: typeof parameters.summary === 'string' ? parameters.summary.slice(0, 50000) : '',
          sections,
          sources
        });

        return res.json({
          success: true,
          outputSummary: `Report saved to ${created.path}`,
          data: created
        });
      }

      case 'OUTBOX_QUEUE': {
        const item = report.queueSend({
          reportPath: parameters.reportPath,
          to: parameters.to,
          channel: parameters.channel,
          note: parameters.note
        });

        return res.json({
          success: true,
          outputSummary: `Queued for approval (id ${item.id}). Nothing has been sent.`,
          data: item
        });
      }

      case 'OUTBOX_LIST': {
        const status = parameters.status;
        if (status !== undefined && !['pending', 'approved', 'denied'].includes(status)) {
          return res.status(400).json({ success: false, outputSummary: 'Invalid outbox status' });
        }
        return res.json({
          success: true,
          outputSummary: 'Outbox',
          data: { items: report.listOutbox(status) }
        });
      }

      case 'OUTBOX_DECIDE': {
        if (typeof parameters.id !== 'string' || parameters.id.length > 100) {
          return res.status(400).json({ success: false, outputSummary: 'Invalid outbox item id' });
        }
        const item = report.decide(parameters.id, parameters.decision);

        return res.json({
          success: true,
          outputSummary: `Item ${item.id} ${item.status}`,
          data: item
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
          outputSummary: 'URL validated, but browser automation is not installed; the page was not fetched or inspected.',
          data: {
            url: page.url,
            title: page.title,
            browserAutomationAvailable: false,
            pageInspected: false
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
