const crypto = require('crypto');

const TOKEN_PATTERNS = [
  /gh[pousr]_[A-Za-z0-9_]{16,255}/g,
  /github_pat_[A-Za-z0-9_]{16,255}/g,
  /ya29\.[A-Za-z0-9_\-]{20,255}/g,
  /1\/\/[A-Za-z0-9_\-]{20,255}/g,
  /AIza[0-9A-Za-z\-_]{20,100}/g,
  /sk-[A-Za-z0-9\-_]{16,120}/g,
  /(?i)(bearer\s+)[A-Za-z0-9._\-]{12,}/gi,
  /(?i)(api[_-]?key|token|secret|password|cvv|upi[_-]?pin|otp)\s*[:=]\s*['"]?[^\s'"]{4,}['"]?/gi
];

function redactSecrets(text) {
  if (!text || typeof text !== 'string') return '';
  let sanitized = text;
  for (const pattern of TOKEN_PATTERNS) {
    sanitized = sanitized.replace(pattern, '[REDACTED_SECRET]');
  }
  return sanitized;
}

function validateSandboxedPath(targetPath, workspaceRoot) {
  if (!targetPath || typeof targetPath !== 'string') {
    return { ok: false, error: 'Target path required' };
  }
  if (targetPath.includes('\u0000') || targetPath.includes('%00')) {
    return { ok: false, error: 'Null byte detected in path' };
  }
  if (targetPath.includes('../') || targetPath.includes('..\\') || targetPath === '..') {
    return { ok: false, error: 'Directory traversal (..) blocked' };
  }
  const normalized = targetPath.replace(/\\/g, '/');
  const lower = normalized.toLowerCase();
  if (lower.startsWith('/etc') || lower.startsWith('/root') || lower.startsWith('c:/windows')) {
    return { ok: false, error: 'System path access prohibited' };
  }
  return { ok: true, path: normalized };
}

module.exports = {
  redactSecrets,
  validateSandboxedPath
};
