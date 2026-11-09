
'use strict';

const fs = require('fs');

function tryPdfKit() {
  try {
    return require('pdfkit');
  } catch {
    return null;
  }
}

function wrapLine(line, width) {
  const words = line.split(/\s+/);
  const out = [];
  let cur = '';

  for (const w of words) {
    if ((cur + ' ' + w).trim().length > width) {
      if (cur) out.push(cur);
      cur = w;
    } else {
      cur = (cur + ' ' + w).trim();
    }
  }

  out.push(cur);
  return out;
}

function escapePdf(text) {
  return text
    .replace(/[^\x20-\x7E]/g, '?')
    .replace(/\\/g, '\\\\')
    .replace(/\(/g, '\\(')
    .replace(/\)/g, '\\)');
}

function buildSimplePdf(blocks) {
  const lines = [];

  for (const b of blocks) {
    const size = b.size || 11;
    const width = Math.floor(95 * 11 / size);

    for (const paragraph of String(b.text).split('\n')) {
      for (const l of wrapLine(paragraph, width)) {
        lines.push({ text: l, size, bold: !!b.bold });
      }
    }

    lines.push({ text: '', size: 6, bold: false });
  }

  const pages = [];
  let page = [];
  let y = 800;

  for (const l of lines) {
    const lead = l.size + 4;

    if (y - lead < 50) {
      pages.push(page);
      page = [];
      y = 800;
    }

    y -= lead;

    if (l.text) {
      page.push(
        `BT /${l.bold ? 'F2' : 'F1'} ${l.size} Tf 50 ${y} Td (${escapePdf(l.text)}) Tj ET`
      );
    }
  }

  pages.push(page);

  const objects = [];
  const add = body => {
    objects.push(body);
    return objects.length;
  };

  const catalog = add('');
  const pagesObj = add('');
  const f1 = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  const f2 = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>');
  const pageIds = [];

  for (const p of pages) {
    const stream = p.join('\n');
    const content = add(
      `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`
    );

    pageIds.push(add(
      `<< /Type /Page /Parent ${pagesObj} 0 R /MediaBox [0 0 595 842] ` +
      `/Resources << /Font << /F1 ${f1} 0 R /F2 ${f2} 0 R >> >> ` +
      `/Contents ${content} 0 R >>`
    ));
  }

  objects[catalog - 1] =
    `<< /Type /Catalog /Pages ${pagesObj} 0 R >>`;

  objects[pagesObj - 1] =
    `<< /Type /Pages /Kids [${pageIds.map(i => `${i} 0 R`).join(' ')}] ` +
    `/Count ${pageIds.length} >>`;

  let out = '%PDF-1.4\n';
  const offsets = [];

  objects.forEach((body, i) => {
    offsets.push(Buffer.byteLength(out));
    out += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });

  const xref = Buffer.byteLength(out);
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;

  for (const o of offsets) {
    out += `${String(o).padStart(10, '0')} 00000 n \n`;
  }

  out +=
    `trailer\n<< /Size ${objects.length + 1} /Root ${catalog} 0 R >>\n` +
    `startxref\n${xref}\n%%EOF\n`;

  return Buffer.from(out, 'latin1');
}

function buildPdf(blocks) {
  const PDFDocument = tryPdfKit();

  if (!PDFDocument) {
    return Promise.resolve({
      buffer: buildSimplePdf(blocks),
      engine: 'simple',
      unicode: false
    });
  }

  return new Promise((resolve, reject) => {
    const fontPath = process.env.TESSA_PDF_FONT;

    if (fontPath && !fs.existsSync(fontPath)) {
      reject(new Error('TESSA_PDF_FONT points to a file that does not exist'));
      return;
    }

    const doc = new PDFDocument({ margin: 50 });
    const chunks = [];

    doc.on('data', c => chunks.push(c));
    doc.on('end', () => resolve({
      buffer: Buffer.concat(chunks),
      engine: 'pdfkit',
      unicode: !!fontPath
    }));
    doc.on('error', reject);

    if (fontPath) doc.font(fontPath);

    for (const b of blocks) {
      doc.fontSize(b.size || 11).text(String(b.text)).moveDown(0.5);
    }

    doc.end();
  });
}

module.exports = { buildPdf, buildSimplePdf };
