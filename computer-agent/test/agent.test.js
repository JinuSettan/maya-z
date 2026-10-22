'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');

const auth = require('../auth');
const { redactSecrets, validateSandboxedPath } = require('../security');
const { tokenize, ALLOWED_BINARIES } = require('../terminal');
const { classifyRequest, filterAllowedChats } = require('../skills/report');

test('auth verifies a valid HMAC signature and rejects a wrong one', () => {
  const payload = JSON.stringify({ action: 'status', requestId: 'test-1' });
  const secret = 'test-only-secret';
  const signature = crypto.createHmac('sha256', secret).update(payload).digest('hex');

  assert.equal(auth.verifySignature(payload, secret, signature), true);
  assert.equal(auth.verifySignature(payload, secret, '0'.repeat(64)), false);
  assert.equal(auth.verifySignature(payload, secret, 'not-a-signature'), false);
});

test('secret redaction masks common token-like values', () => {
  const input = 'Authorization: Bearer abcdefghijklmnop123456';
  assert.match(redactSecrets(input), /REDACTED_SECRET/);
  assert.doesNotMatch(redactSecrets(input), /abcdefghijklmnop123456/);
});

test('sandbox path validation allows workspace root and ordinary relative paths', () => {
  assert.deepEqual(validateSandboxedPath('.'), { ok: true, path: '.' });
  assert.deepEqual(validateSandboxedPath('reports/summary.pdf'), {
    ok: true,
    path: 'reports/summary.pdf'
  });
});

test('sandbox path validation rejects traversal, internal dot segments, and absolute paths', () => {
  assert.equal(validateSandboxedPath('../secret.txt').ok, false);
  assert.equal(validateSandboxedPath('reports/../../secret.txt').ok, false);
  assert.equal(validateSandboxedPath('reports/./secret.txt').ok, false);
  assert.equal(validateSandboxedPath('/etc/passwd').ok, false);
  assert.equal(validateSandboxedPath('C:\\\\Windows\\\\win.ini').ok, false);
});

test('terminal tokenizer handles quoted arguments without invoking a shell', () => {
  assert.deepEqual(tokenize('node --version'), ['node', '--version']);
  assert.deepEqual(tokenize('echo "hello world"'), ['echo', 'hello world']);
  assert.throws(() => tokenize('echo hello && whoami'), /blocked/i);
  assert.throws(() => tokenize('node $(whoami)'), /blocked/i);
  assert.throws(() => tokenize('echo "unfinished'), /Unclosed quote/i);
});

test('terminal binary allowlist contains expected tools but not arbitrary shells', () => {
  assert.equal(ALLOWED_BINARIES.has('node'), true);
  assert.equal(ALLOWED_BINARIES.has('git'), true);
  assert.equal(ALLOWED_BINARIES.has('bash'), false);
  assert.equal(ALLOWED_BINARIES.has('powershell'), false);
});

test('request classifier recognizes assignments and urgency', () => {
  assert.equal(classifyRequest('Please finish my assignment').type, 'assignment');
  assert.equal(classifyRequest('Urgent: report due today').urgent, true);
});

test('chat filtering only returns chats explicitly in the allow-list', () => {
  const messages = [
    { chat: 'Family', text: 'one' },
    { chat: 'College', text: 'two' },
    { chat: 'Unknown', text: 'three' },
    null
  ];

  assert.deepEqual(
    filterAllowedChats(messages, ['family', 'COLLEGE']),
    messages.slice(0, 2)
  );
  assert.deepEqual(filterAllowedChats(messages, []), []);
});
