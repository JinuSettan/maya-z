
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const filesystem = require('../filesystem');
const { buildPdf } = require('./pdf');
const { redactSecrets } = require('../security');

const REPORT_DIR = 'reports';
const OUTBOX_FILE = '.outbox/outbox.json';

function slug(text) {
  return String(text || 'report')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 40) || 'report';
}

function classifyRequest(text) {
  const t = String(text || '').toLowerCase();
  const rules = [
    ['project_report', /(project|seminar|thesis|dissertation).{0,30}(report|submit|submission)|report.{0,20}(submit|send|ready)/],
    ['assignment', /assignment|homework|worksheet|lab record|record book/],
    ['question', /\?|doubt|explain|what is|how to|why /],
    ['deadline', /deadline|due (date|on|by)|before \d|by (tomorrow|today|monday|friday)/]
  ];
  const type = (rules.find(([, re]) => re.test(t)) || ['other'])[0];
  const urgent = /urgent|asap|immediately|today|tomorrow|last date/.test(t);
  return { type, urgent };
}

function filterAllowedChats(messages, allowList) {
  if (!Array.isArray(messages) || !Array.isArray(allowList)) return [];

  const allowed = new Set(
    allowList
      .filter(n => typeof n === 'string')
      .map(n => n.trim().toLowerCase())
      .filter(Boolean)
  );

  if (!allowed.size) return [];

  return messages.filter(m =>
    m &&
    typeof m === 'object' &&
    !Array.isArray(m) &&
    typeof m.chat === 'string' &&
    allowed.has(m.chat.trim().toLowerCase())
  );
}

async function createReport({ title, summary, sections = [], sources = [] }) {
  if (typeof title !== 'string' || !title.trim()) {
    throw new Error('title is required');
  }

  if (
    !Array.isArray(sections) ||
    sections.length > 50 ||
    sections.some(s =>
      !s ||
      typeof s !== 'object' ||
      Array.isArray(s) ||
      (s.heading !== undefined &&
        (typeof s.heading !== 'string' || s.heading.length > 300)) ||
      (s.body !== undefined &&
        (typeof s.body !== 'string' || s.body.length > 20000))
    )
  ) {
    throw new Error(
      'sections must be an array of at most 50 valid section objects; headings max 300 and bodies max 20000 characters'
    );
  }

  if (
    summary !== undefined &&
    (typeof summary !== 'string' || summary.length > 50000)
  ) {
    throw new Error('summary must be a string of at most 50000 characters');
  }

  if (
    !Array.isArray(sources) ||
    sources.length > 100 ||
    sources.some(x => typeof x !== 'string' || x.length > 2000)
  ) {
    throw new Error('sources must be an array of at most 100 strings');
  }

  const blocks = [{ text: title, size: 20, bold: true }];
  blocks.push({
    text: `Prepared on ${new Date().toISOString().slice(0, 10)}`,
    size: 9
  });

  if (summary) blocks.push({ text: summary, size: 11 });

  for (const s of sections) {
    if (s.heading) {
      blocks.push({ text: String(s.heading), size: 14, bold: true });
    }
    if (s.body) blocks.push({ text: String(s.body), size: 11 });
  }

  if (sources.length) {
    blocks.push({ text: 'Sources', size: 12, bold: true });
    blocks.push({
      text: sources.map(x => `- ${x}`).join('\n'),
      size: 9
    });
  }

  for (const b of blocks) {
    b.text = redactSecrets(b.text);
  }

  const { buffer, engine, unicode } = await buildPdf(blocks);
  const rel = `${REPORT_DIR}/${slug(title)}-${Date.now()}.pdf`;
  const full = filesystem.resolvePath(rel, { allowMissing: true });

  const root = fs.realpathSync(filesystem.DEFAULT_WORKSPACE);
  const dir = path.join(root, REPORT_DIR);

  if (!fs.existsSync(dir)) fs.mkdirSync(dir);

  fs.writeFileSync(full, buffer, { flag: 'wx' });

  return {
    path: rel,
    bytes: buffer.length,
    engine,
    warning: unicode
      ? undefined
      : 'Non-Latin text (for example Malayalam) cannot be rendered. Install pdfkit and set TESSA_PDF_FONT to a Unicode font.'
  };
}

function loadOutbox() {
  const full = filesystem.resolvePath(OUTBOX_FILE, { allowMissing: true });

  if (!fs.existsSync(full)) return [];

  const parsed = JSON.parse(fs.readFileSync(full, 'utf8'));

  if (!Array.isArray(parsed)) {
    throw new Error('Outbox data is invalid; refusing to overwrite it');
  }

  return parsed;
}

function saveOutbox(items) {
  const root = fs.realpathSync(filesystem.DEFAULT_WORKSPACE);
  const dir = path.join(root, '.outbox');

  if (!fs.existsSync(dir)) fs.mkdirSync(dir);

  fs.writeFileSync(
    filesystem.resolvePath(OUTBOX_FILE, { allowMissing: true }),
    JSON.stringify(items, null, 2)
  );
}

// This queues an item only. It does NOT send any message.
function queueSend({ reportPath, to, channel, note }) {
  if (
    typeof reportPath !== 'string' ||
    !reportPath.startsWith(`${REPORT_DIR}/`)
  ) {
    throw new Error('reportPath must be inside reports/');
  }

  filesystem.resolvePath(reportPath);

  if (
    typeof to !== 'string' ||
    !to.trim() ||
    to.length > 200 ||
    /[\r\n\0]/.test(to)
  ) {
    throw new Error('recipient is invalid');
  }

  if (!['email', 'telegram', 'whatsapp'].includes(channel)) {
    throw new Error('channel must be email, telegram or whatsapp');
  }

  const items = loadOutbox();

  if (items.length >= 1000) {
    throw new Error('Outbox is full; archive or clear old items first');
  }

  const item = {
    id: crypto.randomBytes(6).toString('hex'),
    reportPath,
    to: to.trim(),
    channel,
    note: String(note || '').slice(0, 500),
    status: 'pending',
    createdAt: new Date().toISOString()
  };

  items.push(item);
  saveOutbox(items);
  return item;
}

function listOutbox(status) {
  return loadOutbox().filter(i => !status || i.status === status);
}

function decide(id, decision) {
  if (!['approved', 'denied'].includes(decision)) {
    throw new Error('decision must be approved or denied');
  }

  const items = loadOutbox();
  const item = items.find(i => i.id === id);

  if (!item) throw new Error('Outbox item not found');
  if (item.status !== 'pending') {
    throw new Error(`Item already ${item.status}`);
  }

  item.status = decision;
  item.decidedAt = new Date().toISOString();
  saveOutbox(items);
  return item;
}

module.exports = {
  classifyRequest,
  filterAllowedChats,
  createReport,
  queueSend,
  listOutbox,
  decide
};
