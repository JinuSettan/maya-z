
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'tessa-ws-'));
process.env.TESSA_WORKSPACE = ws;
process.env.TESSA_PAIRING_TOKEN = 'a'.repeat(48);

const terminal = require('../terminal');
const auth = require('../auth');
const report = require('../skills/report');

test('dangerous commands are blocked', async () => {
  for (const cmd of [
    'python -c 1',
    'node -e 1',
    'npx foo',
    'cat /etc/passwd',
    'cat ../x',
    'git -c core.sshCommand=x status',
    'pnpm run evil'
  ]) {
    const r = await terminal.runTerminal(cmd);
    assert.strictEqual(r.success, false, cmd);
    assert.strictEqual(r.exitCode, 2, cmd);
  }
});

test('safe commands work inside workspace', async () => {
  fs.writeFileSync(path.join(ws, 'a.txt'), 'hello');
  const r = await terminal.runTerminal('cat a.txt');
  assert.strictEqual(r.success, true);
  assert.match(r.output, /hello/);
});

test('unsafe paths are rejected', () => {
  const { validateSandboxedPath } = require('../security');
  assert.strictEqual(validateSandboxedPath('.').ok, true);
  assert.strictEqual(validateSandboxedPath('./x').ok, false);
  assert.strictEqual(validateSandboxedPath('..').ok, false);
});

test('signed requests reject replay, stale and tampered requests', () => {
  const secret = 's'.repeat(32);
  const sign = (ts, nonce, body) =>
    crypto.createHmac('sha256', secret)
      .update(`${ts}.${nonce}.${body}`)
      .digest('hex');

  const body = '{"action":"LIST_FILES"}';
  const ts = String(Date.now());
  const nonce = 'abcdefghijklmnop';
  const good = {
    timestamp: ts,
    nonce,
    signature: sign(ts, nonce, body)
  };

  assert.strictEqual(auth.verifySignedRequest(body, secret, good), true);
  assert.strictEqual(auth.verifySignedRequest(body, secret, good), false);

  const old = String(Date.now() - 5 * 60 * 1000);
  assert.strictEqual(
    auth.verifySignedRequest(body, secret, {
      timestamp: old,
      nonce: 'qqqqqqqqqqqqqqqq',
      signature: sign(old, 'qqqqqqqqqqqqqqqq', body)
    }),
    false
  );

  assert.strictEqual(
    auth.verifySignedRequest(body + ' ', secret, {
      timestamp: ts,
      nonce: 'wwwwwwwwwwwwwwww',
      signature: sign(ts, 'wwwwwwwwwwwwwwww', body)
    }),
    false
  );
});

test('skill scripts must be approved', async () => {
  assert.strictEqual(
    (await terminal.runSkillScript('../evil')).success,
    false
  );
  assert.strictEqual(
    (await terminal.runSkillScript('missing')).success,
    false
  );
});

test('report PDF is generated and secrets are redacted', async () => {
  const r = await report.createReport({
    title: 'Seminar Report',
    summary: 'Short summary. token=abcd1234secret',
    sections: [{
      heading: 'Findings',
      body: 'Line one\nLine two'
    }],
    sources: ['gmail: teacher@college.edu']
  });

  const buf = fs.readFileSync(path.join(ws, r.path));
  assert.strictEqual(buf.subarray(0, 5).toString(), '%PDF-');
  assert.ok(!buf.toString('latin1').includes('abcd1234secret'));
  assert.ok(buf.toString('latin1').trim().endsWith('%%EOF'));
});

test('outbox requires approval and decisions happen once', async () => {
  const r = await report.createReport({
    title: 'Project',
    sections: []
  });

  const item = report.queueSend({
    reportPath: r.path,
    to: 'manager@company.com',
    channel: 'email'
  });

  assert.strictEqual(item.status, 'pending');
  assert.strictEqual(report.listOutbox('pending').length, 1);
  assert.strictEqual(
    report.decide(item.id, 'approved').status,
    'approved'
  );
  assert.throws(() => report.decide(item.id, 'denied'));
  assert.throws(() => report.queueSend({
    reportPath: '../secret.pdf',
    to: 'x',
    channel: 'email'
  }));
  assert.throws(() => report.queueSend({
    reportPath: r.path,
    to: 'x',
    channel: 'sms'
  }));
});

test('request classification and chat allow-list', () => {
  assert.strictEqual(
    report.classifyRequest(
      'Please submit the project report by Friday'
    ).type,
    'project_report'
  );

  assert.strictEqual(
    report.classifyRequest('What is polymorphism?').type,
    'question'
  );

  const msgs = [
    { chat: 'Teacher Anu', text: 'a' },
    { chat: 'Family', text: 'b' }
  ];

  assert.deepStrictEqual(
    report.filterAllowedChats(msgs, ['teacher anu'])
      .map(m => m.chat),
    ['Teacher Anu']
  );

  assert.deepStrictEqual(report.filterAllowedChats(msgs, []), []);
  assert.deepStrictEqual(
    report.filterAllowedChats(
      [null, 'bad', { chat: 'Family' }],
      ['teacher anu']
    ),
    []
  );
});
