'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { redactSecrets } = require('./security');

const DEFAULT_STORE = path.join(__dirname, 'data', 'tessa-memory.json');
const MAX_RECORDS = 5000;
const MAX_CONTENT_LENGTH = 4000;
const VALID_KINDS = new Set(['preference', 'fact', 'lesson', 'task_outcome']);
const VALID_STATUSES = new Set(['candidate', 'approved', 'rejected']);

function safeText(value, maxLength) {
  if (typeof value !== 'string') throw new TypeError('Expected text');
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > maxLength) throw new Error('Text is empty or too long');
  return redactSecrets(trimmed);
}

function normalizeScope(scope) {
  return safeText(scope, 120).toLowerCase();
}

function readStore(filePath = DEFAULT_STORE) {
  try {
    const parsed = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    if (!parsed || parsed.version !== 1 || !Array.isArray(parsed.records)) {
      throw new Error('Invalid memory store format');
    }
    return parsed;
  } catch (error) {
    if (error.code === 'ENOENT') return { version: 1, records: [] };
    throw error;
  }
}

function writeStore(store, filePath = DEFAULT_STORE) {
  const dir = path.dirname(filePath);
  fs.mkdirSync(dir, { recursive: true });
  const temporary = `${filePath}.${process.pid}.${crypto.randomBytes(6).toString('hex')}.tmp`;
  try {
    fs.writeFileSync(temporary, `${JSON.stringify(store, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
    fs.renameSync(temporary, filePath);
  } finally {
    try { fs.unlinkSync(temporary); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
}

function createCandidate(input, filePath = DEFAULT_STORE) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new TypeError('Memory input must be an object');
  const scope = normalizeScope(input.scope);
  const kind = input.kind;
  if (!VALID_KINDS.has(kind)) throw new Error('Unsupported memory kind');
  const content = safeText(input.content, MAX_CONTENT_LENGTH);
  const source = safeText(input.source || 'user_feedback', 300);
  const consent = input.consent === true;
  if (!consent) throw new Error('Explicit consent is required to save a memory candidate');
  const store = readStore(filePath);
  if (store.records.length >= MAX_RECORDS) throw new Error('Memory store is full');
  const record = {
    id: crypto.randomUUID(),
    scope,
    kind,
    content,
    source,
    createdAt: new Date().toISOString(),
    confidence: Number.isFinite(input.confidence) ? Math.max(0, Math.min(1, input.confidence)) : 0.5,
    consent: true,
    status: 'candidate',
    expiresAt: input.expiresAt == null ? null : new Date(input.expiresAt).toISOString()
  };
  store.records.push(record);
  writeStore(store, filePath);
  return { ...record };
}

function reviewCandidate(id, decision, filePath = DEFAULT_STORE) {
  if (!VALID_STATUSES.has(decision) || decision === 'candidate') throw new Error('Decision must be approved or rejected');
  const store = readStore(filePath);
  const record = store.records.find(item => item.id === id);
  if (!record) throw new Error('Memory candidate not found');
  if (record.status !== 'candidate') throw new Error('Only candidate memories can be reviewed');
  record.status = decision;
  record.reviewedAt = new Date().toISOString();
  writeStore(store, filePath);
  return { ...record };
}

function listMemories(scope, options = {}, filePath = DEFAULT_STORE) {
  const normalizedScope = normalizeScope(scope);
  const status = options.status || 'approved';
  if (!VALID_STATUSES.has(status)) throw new Error('Invalid memory status');
  const now = Date.now();
  return readStore(filePath).records
    .filter(item => item.scope === normalizedScope && item.status === status)
    .filter(item => !item.expiresAt || Date.parse(item.expiresAt) > now)
    .map(item => ({ ...item }));
}

function deleteMemory(id, scope, filePath = DEFAULT_STORE) {
  const normalizedScope = normalizeScope(scope);
  const store = readStore(filePath);
  const index = store.records.findIndex(item => item.id === id && item.scope === normalizedScope);
  if (index < 0) return false;
  store.records.splice(index, 1);
  writeStore(store, filePath);
  return true;
}

module.exports = { DEFAULT_STORE, createCandidate, reviewCandidate, listMemories, deleteMemory };