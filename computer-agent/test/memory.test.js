'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const memory = require('../memory');

function temporaryStore(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tessa-memory-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return path.join(dir, 'memory.json');
}

test('memory requires explicit consent and stores new lessons as candidates', t => {
  const file = temporaryStore(t);
  assert.throws(() => memory.createCandidate({ scope: 'user-a', kind: 'lesson', content: 'A useful lesson', source: 'feedback' }, file), /consent/i);
  const item = memory.createCandidate({ scope: 'user-a', kind: 'lesson', content: 'A useful lesson', source: 'feedback', consent: true }, file);
  assert.equal(item.status, 'candidate');
  assert.deepEqual(memory.listMemories('user-a', { status: 'approved' }, file), []);
  assert.equal(memory.listMemories('user-a', { status: 'candidate' }, file).length, 1);
});

test('only reviewed memories are returned as approved', t => {
  const file = temporaryStore(t);
  const item = memory.createCandidate({ scope: 'user-a', kind: 'preference', content: 'Prefer concise answers', source: 'explicit feedback', consent: true }, file);
  memory.reviewCandidate(item.id, 'approved', file);
  assert.equal(memory.listMemories('user-a', {}, file)[0].content, 'Prefer concise answers');
  assert.throws(() => memory.reviewCandidate(item.id, 'rejected', file), /Only candidate/i);
});

test('memory scopes are isolated and deletion requires matching scope', t => {
  const file = temporaryStore(t);
  const item = memory.createCandidate({ scope: 'workspace-a', kind: 'fact', content: 'Project uses Node.js', source: 'README', consent: true }, file);
  memory.reviewCandidate(item.id, 'approved', file);
  assert.deepEqual(memory.listMemories('workspace-b', {}, file), []);
  assert.equal(memory.deleteMemory(item.id, 'workspace-b', file), false);
  assert.equal(memory.deleteMemory(item.id, 'workspace-a', file), true);
  assert.deepEqual(memory.listMemories('workspace-a', {}, file), []);
});

test('memory redacts secrets and rejects unsupported kinds', t => {
  const file = temporaryStore(t);
  const item = memory.createCandidate({ scope: 'user-a', kind: 'lesson', content: 'Authorization: Bearer abcdefghijklmnop123456', source: 'feedback', consent: true }, file);
  assert.match(item.content, /REDACTED_SECRET/);
  assert.doesNotMatch(item.content, /abcdefghijklmnop123456/);
  assert.throws(() => memory.createCandidate({ scope: 'user-a', kind: 'anything', content: 'x', source: 'test', consent: true }, file), /Unsupported memory kind/);
});