const express = require('express');
const auth = require('./auth');
const filesystem = require('./filesystem');
const terminal = require('./terminal');
const browser = require('./browser');
const unreal = require('./unreal');
const { redactSecrets } = require('./security');

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 8990;

// 1. Pairing endpoint
app.post('/api/tessa/pair', (req, res) => {
  const { computerId, deviceName, pairingToken } = req.body;
  if (!computerId || !pairingToken) {
    return res.status(400).json({ error: 'computerId and pairingToken required' });
  }
  const sessionToken = auth.pair(computerId, deviceName || 'Host Computer', pairingToken);
  res.json({ success: true, sessionToken });
});

// 2. Health check
app.get('/api/tessa/health', (req, res) => {
  const session = req.headers['x-tessa-session'];
  if (!auth.verifySession(session)) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  res.json({ status: 'OK', timestamp: Date.now() });
});

// 3. Capabilities discovery
app.get('/api/tessa/capabilities', (req, res) => {
  const session = req.headers['x-tessa-session'];
  if (!auth.verifySession(session)) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  res.json({
    capabilities: [
      'FILE_READ', 'FILE_WRITE', 'FILE_CREATE', 'FILE_DELETE',
      'DIRECTORY_CREATE', 'TERMINAL', 'BROWSER', 'UNREAL_ENGINE',
      'BUILD', 'GIT'
    ]
  });
});

// 4. Action dispatcher
app.post('/api/tessa/action', async (req, res) => {
  const session = req.headers['x-tessa-session'];
  const computer = auth.verifySession(session);
  if (!computer) {
    return res.status(401).json({ error: 'Unauthorized session' });
  }

  const { action, target, parameters, taskId } = req.body;

  try {
    switch (action) {
      case 'READ_FILE': {
        const content = filesystem.readFile(target);
        return res.json({ success: true, outputSummary: content });
      }
      case 'WRITE_FILE': {
        const resWrite = filesystem.writeFile(target, parameters.content || '');
        return res.json({ success: true, outputSummary: `Wrote ${resWrite.bytesWritten} bytes to ${target}` });
      }
      case 'DELETE_FILE': {
        filesystem.deleteFile(target);
        return res.json({ success: true, outputSummary: `Deleted ${target}` });
      }
      case 'LIST_FILES': {
        const files = filesystem.listFiles(target || '.');
        return res.json({ success: true, outputSummary: JSON.stringify(files) });
      }
      case 'TERMINAL_EXEC': {
        const cmd = parameters.command;
        const result = await terminal.runTerminal(cmd, parameters.cwd, parseInt(parameters.timeoutSec || '60', 10));
        return res.json({ success: result.success, outputSummary: result.output });
      }
      case 'OPEN_URL': {
        const page = await browser.openUrl(taskId, target);
        return res.json({ success: true, outputSummary: page.textContent, data: { url: page.url, title: page.title } });
      }
      case 'UNREAL_DETECT': {
        const ue = unreal.detectUnrealEngine();
        return res.json({ success: true, outputSummary: `Unreal Engine ${ue.engineVersion}`, data: ue });
      }
      case 'UNREAL_CREATE_PROJECT': {
        const proj = unreal.createUnrealProject(parameters.projectName, parameters.targetFolder);
        return res.json({ success: true, outputSummary: `Created ${proj.projectName}`, data: proj });
      }
      default:
        return res.status(404).json({ success: false, outputSummary: `Unknown action: ${action}` });
    }
  } catch (err) {
    res.status(500).json({ success: false, outputSummary: err.message, errorCode: 'EXEC_ERROR' });
  }
});

app.listen(PORT, () => {
  console.log(`⚡ TESSA Computer Companion Service running on port ${PORT}`);
});
