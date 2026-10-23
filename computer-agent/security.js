'use strict';

const TOKEN_PATTERNS = [
  /\bgh[pousr]_[A-Za-z0-9_]{16,255}\b/g,
  /\bgithub_pat_[A-Za-z0-9_]{16,255}\b/g,
  /\bya29\.[A-Za-z0-9_-]{20,255}\b/g,
  /\bAIza[0-9A-Za-z_-]{20,100}\b/g,
  /\bsk-[A-Za-z0-9_-]{16,120}\b/g,
  /\b(Bearer\s+)[A-Za-z0-9._-]{12,}/gi,
  /\b(api[_-]?key|token|secret|password|cvv|upi[_-]?pin|otp)\s*[:=]\s*(['"]?)[^\s'"]{4,}\2/gi
];

function redactSecrets(value) {
  if (typeof value !== 'string') return '';

  let result = value;
  for (const pattern of TOKEN_PATTERNS) {
    result = result.replace(pattern, '[REDACTED_SECRET]');
  }
  return result;
}

function validateSandboxedPath(targetPath) {
  if (typeof targetPath !== 'string' || !targetPath.trim()) {
    return { ok: false, error: 'Target path required' };
  }

  if (targetPath.includes('\0') || targetPath.includes('%00') ||
      targetPath.includes('\n') || targetPath.includes('\r')) {
    return { ok: false, error: 'Invalid characters in path' };
  }

  const normalized = targetPath.replace(/\\/g, '/');

  if (normalized.startsWith('/') || /^[a-zA-Z]:/.test(normalized) ||
      normalized.startsWith('//')) {
    return { ok: false, error: 'Absolute paths are not allowed' };
  }

  if (normalized !== '.' && normalized.split('/').some(part => part === '.')) {
    return { ok: false, error: 'Use a normalized relative path' };
  }

  if (normalized.split('/').some(part => part === '..')) {
    return { ok: false, error: 'Directory traversal is blocked' };
  }

  return { ok: true, path: normalized };
}

module.exports = { redactSecrets, validateSandboxedPath };
