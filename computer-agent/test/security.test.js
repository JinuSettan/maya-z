'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { redactSecrets, validateSandboxedPath } = require('../security');

test('redacts common provider tokens and bearer credentials', () => {
  const values = [
    'token ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ123456',
    'token github_pat_ABCDEFGHIJKLMNOPQRSTUVWXYZ123456',
    'key AIza123456789012345678901234567890',
    'Authorization: Bearer abcdefghijklmnop123456',
    'password=my-secret-value'
  ];
  for (const value of values) {
    const redacted = redactSecrets(value);
    assert.match(redacted, /REDACTED_SECRET/);
    assert.notEqual(redacted, value);
  }
});

test('rejects NUL and line breaks in paths', () => {
  assert.equal(validateSandboxedPath('safe\0name').ok, false);
  assert.equal(validateSandboxedPath('safe\nname').ok, false);
  assert.equal(validateSandboxedPath('safe\rname').ok, false);
});

test('normalizes Windows separators and rejects traversal', () => {
  assert.deepEqual(validateSandboxedPath('reports\\summary.txt'), {
    ok: true,
    path: 'reports/summary.txt'
  });
  assert.equal(validateSandboxedPath('reports\\..\\secret.txt').ok, false);
});
