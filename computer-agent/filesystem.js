const fs = require('fs');
const path = require('path');
const { validateSandboxedPath } = require('./security');

const DEFAULT_WORKSPACE = path.resolve(
  process.env.TESSA_WORKSPACE || path.join(__dirname, 'workspace')
);

fs.mkdirSync(DEFAULT_WORKSPACE, { recursive: true });

function isInsideWorkspace(candidate, root) {
  const relative = path.relative(root, candidate);

  return (
    relative === '' ||
    (!relative.startsWith('..' + path.sep) &&
      relative !== '..' &&
      !path.isAbsolute(relative))
  );
}

function resolvePath(targetPath, options = {}) {
  const check = validateSandboxedPath(targetPath);

  if (!check.ok) throw new Error(check.error);

  const root = fs.realpathSync(DEFAULT_WORKSPACE);
  const fullPath = path.resolve(root, check.path);

  if (!isInsideWorkspace(fullPath, root)) {
    throw new Error('Workspace boundary violation');
  }

  // Reject symlinks in every existing path component.
  let current = root;
  const relative = path.relative(root, fullPath);
  const parts = relative ? relative.split(path.sep) : [];

  for (let i = 0; i < parts.length; i++) {
    current = path.join(current, parts[i]);

    if (!fs.existsSync(current)) {
      if (!options.allowMissing) {
        throw new Error('Path does not exist');
      }
      continue;
    }

    const stat = fs.lstatSync(current);

    if (stat.isSymbolicLink()) {
      throw new Error('Symbolic links are not allowed');
    }

    if (i < parts.length - 1 && !stat.isDirectory()) {
      throw new Error('A parent path component is not a directory');
    }
  }

  if (!options.allowMissing && !fs.existsSync(fullPath)) {
    throw new Error('Path does not exist');
  }

  return fullPath;
}

function listFiles(dirPath = '.') {
  const fullPath = resolvePath(dirPath);

  if (!fs.statSync(fullPath).isDirectory()) {
    throw new Error('Target is not a directory');
  }

  return fs.readdirSync(fullPath, { withFileTypes: true }).map(entry => {
    const entryPath = path.join(fullPath, entry.name);
    const stat = fs.lstatSync(entryPath);

    return {
      name: entry.name,
      isDirectory: stat.isDirectory(),
      isSymbolicLink: stat.isSymbolicLink(),
      size: stat.isFile() ? stat.size : 0
    };
  });
}

function readFile(filePath) {
  const fullPath = resolvePath(filePath);

  if (!fs.lstatSync(fullPath).isFile()) {
    throw new Error('Target is not a regular file');
  }

  const stat = fs.statSync(fullPath);

  if (stat.size > 5 * 1024 * 1024) {
    throw new Error('File exceeds 5 MB read limit');
  }

  return fs.readFileSync(fullPath, 'utf8');
}

function writeFile(filePath, content) {
  if (typeof content !== 'string') {
    throw new Error('File content must be a string');
  }

  if (Buffer.byteLength(content, 'utf8') > 5 * 1024 * 1024) {
    throw new Error('File exceeds 5 MB write limit');
  }

  const fullPath = resolvePath(filePath, { allowMissing: true });
  const parentPath = path.dirname(fullPath);

  // Create missing parent directories one at a time.
  const root = fs.realpathSync(DEFAULT_WORKSPACE);
  const relativeParent = path.relative(root, parentPath);
  let current = root;

  for (const part of relativeParent ? relativeParent.split(path.sep) : []) {
    current = path.join(current, part);

    if (!fs.existsSync(current)) {
      fs.mkdirSync(current);
    }

    const stat = fs.lstatSync(current);

    if (stat.isSymbolicLink() || !stat.isDirectory()) {
      throw new Error('Unsafe parent directory');
    }
  }

  // Re-check after creating the parent directories.
  const safeTarget = resolvePath(filePath, { allowMissing: true });

  if (fs.existsSync(safeTarget)) {
    const stat = fs.lstatSync(safeTarget);

    if (stat.isSymbolicLink() || !stat.isFile()) {
      throw new Error('Can only overwrite a regular file');
    }
  }

  fs.writeFileSync(safeTarget, content, {
    encoding: 'utf8',
    flag: 'w'
  });

  return {
    path: filePath,
    bytesWritten: Buffer.byteLength(content, 'utf8')
  };
}

function deleteFile(filePath) {
  if (filePath === '.' || filePath === '') {
    throw new Error('Deleting the workspace root is prohibited');
  }

  const fullPath = resolvePath(filePath);
  const stat = fs.lstatSync(fullPath);

  if (stat.isSymbolicLink()) {
    throw new Error('Symbolic link deletion is prohibited');
  }

  if (stat.isDirectory()) {
    fs.rmSync(fullPath, { recursive: true, force: false });
  } else if (stat.isFile()) {
    fs.unlinkSync(fullPath);
  } else {
    throw new Error('Unsupported filesystem object');
  }

  return true;
}

module.exports = {
  DEFAULT_WORKSPACE,
  resolvePath,
  listFiles,
  readFile,
  writeFile,
  deleteFile
};
