const fs = require('fs');
const path = require('path');
const { validateSandboxedPath } = require('./security');

const DEFAULT_WORKSPACE = path.resolve(__dirname, 'workspace');
if (!fs.existsSync(DEFAULT_WORKSPACE)) {
  fs.mkdirSync(DEFAULT_WORKSPACE, { recursive: true });
}

function resolvePath(targetPath) {
  const check = validateSandboxedPath(targetPath, DEFAULT_WORKSPACE);
  if (!check.ok) throw new Error(check.error);
  return path.resolve(DEFAULT_WORKSPACE, targetPath.replace(/^\//, ''));
}

function listFiles(dirPath = '.') {
  const full = resolvePath(dirPath);
  if (!fs.existsSync(full)) return [];
  return fs.readdirSync(full, { withFileTypes: true }).map(e => ({
    name: e.name,
    isDirectory: e.isDirectory(),
    size: e.isDirectory() ? 0 : fs.statSync(path.join(full, e.name)).size
  }));
}

function readFile(filePath) {
  const full = resolvePath(filePath);
  if (!fs.existsSync(full)) throw new Error('File not found: ' + filePath);
  return fs.readFileSync(full, 'utf8');
}

function writeFile(filePath, content) {
  const full = resolvePath(filePath);
  const dir = path.dirname(full);
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(full, content, 'utf8');
  return { path: filePath, bytesWritten: Buffer.byteLength(content, 'utf8') };
}

function deleteFile(filePath) {
  const full = resolvePath(filePath);
  if (!fs.existsSync(full)) throw new Error('File not found');
  const stat = fs.statSync(full);
  if (stat.isDirectory()) {
    fs.rmdirSync(full, { recursive: true });
  } else {
    fs.unlinkSync(full);
  }
  return true;
}

module.exports = {
  DEFAULT_WORKSPACE,
  listFiles,
  readFile,
  writeFile,
  deleteFile
};
