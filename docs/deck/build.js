/**
 * VeriTransit — "AI verification for B2B commerce" investor/judge deck.
 *   node build.js
 * Output: VeriTransit-Worker-Copilot.pptx (16:9, native PPT text + native editable charts).
 */

const PptxGenJS = require('pptxgenjs');
const fs = require('fs');
const path = require('path');
const { C, FONT, MONO, G, frame, card, kpi, chip, source, takeaway, node, arrow } = require('./lib/theme');

const D = JSON.parse(fs.readFileSync(path.join(__dirname, 'data/charts.json'), 'utf8'));
const OUT = path.join(__dirname, 'VeriTransit-Worker-Copilot.pptx');
const MOTION = path.join(__dirname, 'motion/verification-flow');

const pptx = new PptxGenJS();
pptx.layout = 'LAYOUT_WIDE'; // 13.333 x 7.5in — LAYOUT_16x9 is only 10 x 5.625in
pptx.author = 'VeriTransit';
pptx.company = 'VeriTransit';
pptx.title = 'VeriTransit — AI verification for B2B commerce';
pptx.subject = 'Worker copilot for physical-to-digital shipment verification';

const TOTAL = 19;
let n = 1; // the title slide is 1; frame() numbers from 2
const next = () => ++n;

const chartBase = {
  chartColors: [C.brand],
  showLegend: false,
  showValue: true,
  dataLabelFontFace: FONT,
  dataLabelFontSize: 12,
  dataLabelColor: C.ink,
  catAxisLabelFontFace: FONT,
  catAxisLabelFontSize: 11,
  catAxisLabelColor: C.slate,
  valAxisLabelFontFace: FONT,
  valAxisLabelFontSize: 10,
  valAxisLabelColor: C.muted,
  catAxisLineShow: false,
  valAxisLineShow: false,
  valGridLine: { color: C.hair, style: 'solid', size: 0.75 },
  catGridLine: { style: 'none' },
  border: { pt: 0, color: 'FFFFFF' },
  fill: 'FFFFFF',
};

/* ═══════════════ 1 · Title ═══════════════ */
{
  const s = pptx.addSlide();
  s.background = { color: C.brandDeep };

  // quiet geometric field, right side
  for (let i = 0; i < 9; i++) {
    s.addShape('roundRect', {
      x: 8.55 + (i % 3) * 1.42, y: 1.95 + Math.floor(i / 3) * 1.42,
      w: 1.14, h: 1.14, rectRadius: 0.1,
      fill: { color: i === 8 ? 'D97706' : '7E1530', transparency: i === 8 ? 55 : 68 },
      line: { color: i === 8 ? 'D97706' : 'A8465F', width: i === 8 ? 1.6 : 1 },
    });
  }
  s.addText('?', {
    x: 11.39, y: 4.79, w: 1.14, h: 1.14,
    fontFace: FONT, fontSize: 34, bold: true, color: 'F0B562', align: 'center', valign: 'middle',
  });

  s.addText('VERITRANSIT', {
    x: G.mx, y: 1.28, w: 7, h: 0.3,
    fontFace: FONT, fontSize: 13, bold: true, color: 'E7C3CC', charSpacing: 4,
  });
  s.addText('AI-powered physical verification\nfor B2B commerce', {
    x: G.mx, y: 1.80, w: 8.1, h: 1.5,
    fontFace: FONT, fontSize: 34, bold: true, color: 'FFFFFF', lineSpacingMultiple: 1.08, valign: 'top',
  });
  s.addShape('rect', { x: G.mx, y: 3.34, w: 0.9, h: 0.045, fill: { color: 'D97706' } });
  s.addText('A worker copilot that checks what physically arrived against the digital order — and writes the record itself.', {
    x: G.mx, y: 3.62, w: 7.5, h: 0.9,
    fontFace: FONT, fontSize: 16.5, color: 'E2D5D9', lineSpacingMultiple: 1.18, valign: 'top',
  });

  s.addShape('line', { x: G.mx, y: 5.72, w: 7.3, h: 0, line: { color: '8A4257', width: 0.75 } });
  [
    ['Android + on-device AI', 'camera, NPU, works offline'],
    ['Kotlin backend', 'reconciliation, audit chain'],
    ['Ops agent', 'records, reports, alerts'],
  ].forEach(([t, sub], i) => {
    s.addText(t, {
      x: G.mx + i * 2.5, y: 5.9, w: 2.4, h: 0.26,
      fontFace: FONT, fontSize: 12.5, bold: true, color: 'FFFFFF',
    });
    s.addText(sub, {
      x: G.mx + i * 2.5, y: 6.16, w: 2.4, h: 0.26,
      fontFace: FONT, fontSize: 10.5, color: 'C89AA6',
    });
  });
  s.addText('Not a compliance tool. A faster pair of hands on the dock.', {
    x: G.mx, y: 6.82, w: 9, h: 0.3,
    fontFace: FONT, fontSize: 11.5, italic: true, color: 'C89AA6',
  });
}

/* ═══════════════ 2 · Thesis ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The premise',
    title: 'Every B2B transaction has two realities',
    sub: 'One is structured, auditable and trusted by finance. The other is on a pallet, and gets confirmed by eye.',
    index: next(), total: TOTAL,
  });

  const y = 2.06, h = 3.32, w = 4.86;
  // digital
  card(s, { x: G.mx, y, w, h });
  s.addShape('rect', { x: G.mx, y, w, h: 0.055, fill: { color: C.azure } });
  s.addText('THE DIGITAL ORDER', { x: G.mx + 0.3, y: y + 0.3, w: w - 0.6, h: 0.26, fontFace: FONT, fontSize: 11, bold: true, color: C.azure, charSpacing: 1.6 });
  s.addText('Exact, structured, already in the system', { x: G.mx + 0.3, y: y + 0.64, w: w - 0.6, h: 0.4, fontFace: FONT, fontSize: 17, bold: true, color: C.ink });
  ['Purchase order — 10 units, SKU, rate, PO line', 'Tax invoice — value, tax, payment terms', 'Manifest / delivery note — what was declared'].forEach((t, i) => {
    s.addText(t, { x: G.mx + 0.3, y: y + 1.18 + i * 0.42, w: w - 0.6, h: 0.36, fontFace: FONT, fontSize: 12.5, color: C.slate, bullet: { code: '2022' }, indentLevel: 0 });
  });
  s.addShape('roundRect', { x: G.mx + 0.3, y: y + 2.52, w: w - 0.6, h: 0.58, fill: { color: C.azureBg }, line: { color: 'BAE6FD', width: 1 }, rectRadius: 0.05 });
  s.addText('Machine-readable. Finance acts on it.', { x: G.mx + 0.3, y: y + 2.52, w: w - 0.6, h: 0.58, fontFace: FONT, fontSize: 12, bold: true, color: '075985', align: 'center', valign: 'middle' });

  // gap
  const gx = G.mx + w + 0.28;
  s.addShape('roundRect', { x: gx, y: y + 1.02, w: 1.44, h: 1.3, fill: { color: C.amberBg }, line: { color: 'FDE68A', width: 1.4 }, rectRadius: 0.08 });
  s.addText('THE\nGAP', { x: gx, y: y + 1.02, w: 1.44, h: 1.3, fontFace: FONT, fontSize: 15, bold: true, color: C.amber, align: 'center', valign: 'middle', charSpacing: 1, lineSpacingMultiple: 1.0 });
  arrow(s, { x: G.mx + w + 0.04, y: y + 1.67, w: 0.2, h: 0, color: C.border });
  arrow(s, { x: gx + 1.44, y: y + 1.67, w: 0.2, h: 0, color: C.border });

  // physical
  const px = gx + 1.72;
  card(s, { x: px, y, w, h });
  s.addShape('rect', { x: px, y, w, h: 0.055, fill: { color: C.brand } });
  s.addText('THE PHYSICAL SHIPMENT', { x: px + 0.3, y: y + 0.3, w: w - 0.6, h: 0.26, fontFace: FONT, fontSize: 11, bold: true, color: C.brand, charSpacing: 1.6 });
  s.addText('Counted by eye, written on paper', { x: px + 0.3, y: y + 0.64, w: w - 0.6, h: 0.4, fontFace: FONT, fontSize: 17, bold: true, color: C.ink });
  ['Cartons that may be short, extra or swapped', 'Labels that are missing, torn or unreadable', 'Damage nobody logs until the customer calls'].forEach((t, i) => {
    s.addText(t, { x: px + 0.3, y: y + 1.18 + i * 0.42, w: w - 0.6, h: 0.36, fontFace: FONT, fontSize: 12.5, color: C.slate, bullet: { code: '2022' } });
  });
  s.addShape('roundRect', { x: px + 0.3, y: y + 2.52, w: w - 0.6, h: 0.58, fill: { color: C.brandTint }, line: { color: 'E7C3CC', width: 1 }, rectRadius: 0.05 });
  s.addText('Opaque to every system downstream.', { x: px + 0.3, y: y + 2.52, w: w - 0.6, h: 0.58, fontFace: FONT, fontSize: 12, bold: true, color: C.brand, align: 'center', valign: 'middle' });

  takeaway(s, { x: G.mx, y: 5.72, w: 12.09, text: 'The mismatch between the two is discovered weeks later, in an invoice dispute — by people who never saw the cargo.' });
}

/* ═══════════════ 3 · Problem, with proof ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The problem · 1 of 2',
    title: 'The frontline is already telling us this work should be automated',
    sub: 'Warehouse associates and the leaders who staff them agree on the diagnosis.',
    index: next(), total: TOTAL,
  });

  card(s, { x: G.mx, y: 1.92, w: 7.35, h: 4.06 });
  s.addText('What warehouse workers and leaders report', {
    x: G.mx + 0.3, y: 2.08, w: 6.8, h: 0.3, fontFace: FONT, fontSize: 13, bold: true, color: C.ink,
  });
  s.addChart(pptx.ChartType.bar, [{
    name: 'Share of respondents (%)',
    labels: ['Too much time on tasks\nthat could be automated', 'Not enough qualified\nstaff to cover the work', 'More frontline tech would\nhit productivity goals'],
    values: D.frontline.values,
  }], {
    ...chartBase,
    x: G.mx + 0.12, y: 2.42, w: 7.1, h: 3.4,
    barDir: 'bar',
    barGapWidthPct: 55,
    chartColors: [C.brand],
    valAxisMaxVal: 100, valAxisMinVal: 0,
    valAxisMajorUnit: 25,
    dataLabelFormatCode: '0"%"',
    dataLabelPosition: 'outEnd',
    dataLabelFontSize: 14,
    dataLabelFontBold: true,
    catAxisLabelFontSize: 10.5,
  });

  const rx = 8.27, rw = 4.44;
  card(s, { x: rx, y: 1.92, w: rw, h: 1.9, fill: C.brandTint, line: 'E7C3CC' });
  s.addText('74%', { x: rx + 0.28, y: 2.06, w: rw - 0.5, h: 0.72, fontFace: FONT, fontSize: 40, bold: true, color: C.brand, valign: 'middle' });
  s.addText('of workers say they spend too much time on tasks a machine could do — while 69% of operations cannot find enough qualified staff to do them.', {
    x: rx + 0.28, y: 2.82, w: rw - 0.56, h: 0.9, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.1,
  });

  card(s, { x: rx, y: 3.99, w: rw, h: 1.99 });
  s.addText('Where that time actually goes', { x: rx + 0.28, y: 4.14, w: rw - 0.5, h: 0.3, fontFace: FONT, fontSize: 13, bold: true, color: C.ink });
  [
    ['Counting and checking cargo', 'by eye, carton by carton'],
    ['Writing it on a tally sheet', 'then keying it into the ERP'],
    ['Chasing what does not add up', 'days after the truck has gone'],
  ].forEach(([t, sub], i) => {
    const yy = 4.5 + i * 0.46;
    s.addShape('ellipse', { x: rx + 0.3, y: yy + 0.09, w: 0.1, h: 0.1, fill: { color: C.brand } });
    s.addText(t, { x: rx + 0.5, y: yy, w: rw - 0.8, h: 0.24, fontFace: FONT, fontSize: 12, bold: true, color: C.ink });
    s.addText(sub, { x: rx + 0.5, y: yy + 0.21, w: rw - 0.8, h: 0.24, fontFace: FONT, fontSize: 10.5, color: C.muted });
  });

  takeaway(s, { x: G.mx, y: 6.08, w: 9.4, text: 'The demand is not for another system to update. It is for the checking itself to stop being manual.' });
  source(s, 'Source: Zebra Technologies, Warehousing Vision Study 2025 (1,700+ warehouse associates and decision-makers).', 6.64);
}

/* ═══════════════ 4 · The cost lands in finance ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The problem · 2 of 2',
    title: 'Manual receiving creates the errors that finance pays for later',
    sub: 'Two hand-offs, two chances to be wrong — and the bill arrives in accounts payable.',
    index: next(), total: TOTAL,
  });

  // the error chain
  const cy = 2.02, ch = 1.26;
  const steps = [
    { t: 'Goods arrive', s: 'checked by eye', fill: C.surface, line: C.hair, col: C.ink },
    { t: 'Written on paper', s: 'error opportunity 1', fill: C.amberBg, line: 'FDE68A', col: C.amber },
    { t: 'Keyed into the ERP', s: 'error opportunity 2', fill: C.amberBg, line: 'FDE68A', col: C.amber },
    { t: 'Invoice arrives', s: 'matched against that record', fill: C.surface, line: C.hair, col: C.ink },
    { t: 'Exception', s: 'AP chases the supplier', fill: C.crimsonBg, line: 'FECACA', col: C.crimson },
  ];
  const sw = 2.18, gap = 0.28;
  steps.forEach((st, i) => {
    const x = G.mx + i * (sw + gap);
    s.addShape('roundRect', { x, y: cy, w: sw, h: ch, fill: { color: st.fill }, line: { color: st.line, width: 1.2 }, rectRadius: 0.06 });
    s.addText(st.t, { x: x + 0.14, y: cy + 0.24, w: sw - 0.28, h: 0.34, fontFace: FONT, fontSize: 13, bold: true, color: st.col, align: 'center' });
    s.addText(st.s, { x: x + 0.12, y: cy + 0.6, w: sw - 0.24, h: 0.5, fontFace: FONT, fontSize: 10.5, color: C.muted, align: 'center', valign: 'top' });
    if (i < steps.length - 1) arrow(s, { x: x + sw + 0.04, y: cy + ch / 2, w: gap - 0.08, h: 0, color: C.border });
  });

  // doughnut
  card(s, { x: G.mx, y: 3.6, w: 5.3, h: 2.4 });
  s.addText('Invoices that hit an exception', { x: G.mx + 0.3, y: 3.76, w: 4.7, h: 0.28, fontFace: FONT, fontSize: 13, bold: true, color: C.ink });
  s.addChart(pptx.ChartType.doughnut, [{
    name: 'Invoice outcomes',
    labels: ['Exception', 'Straight-through'],
    values: D.exceptions.values,
  }], {
    x: G.mx + 0.14, y: 3.98, w: 2.3, h: 1.9,
    chartColors: [C.crimson, C.inset],
    holeSize: 62,
    showLegend: false,
    showValue: false,
    dataBorder: { pt: 2, color: 'FFFFFF' },
    fill: 'FFFFFF',
    border: { pt: 0, color: 'FFFFFF' },
  });
  s.addText('18.4%', { x: G.mx + 0.14, y: 4.72, w: 2.3, h: 0.42, fontFace: FONT, fontSize: 19, bold: true, color: C.crimson, align: 'center' });
  s.addText('Nearly one invoice in five needs a human to reconcile it against what was actually received.', {
    x: G.mx + 2.62, y: 4.12, w: 2.5, h: 1.3, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.15,
  });

  kpi(s, { x: 6.22, y: 3.6, w: 3.1, h: 2.4, value: '2', label: 'hand-offs between the dock and the ledger', note: 'The goods are recorded twice by hand before any system compares them to the order.', color: C.amber, valueSize: 44 });
  kpi(s, { x: 9.61, y: 3.6, w: 3.1, h: 2.4, value: 'Weeks', label: 'until a short shipment surfaces', note: 'Typically at invoice matching or a payment dispute — long after the evidence is gone.', color: C.crimson, valueSize: 36 });

  source(s, 'Sources: Ardent Partners, Accounts Payable Metrics that Matter 2025 (average invoice exception rate, 18.4%); Oracle, on paper-then-ERP receiving creating two separate opportunities for human error.', 6.2);
}

/* ═══════════════ 5 · Why now ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'Why now',
    title: 'Three things just became true — and make this buildable',
    sub: 'The demand, the hardware and the digital counterpart all arrived at the same time.',
    index: next(), total: TOTAL,
  });

  const items = [
    {
      no: '01', head: 'The market is buying frontline tech',
      body: '82% of warehouse decision-makers say more technology in workers\' hands is how they hit productivity targets. The budget line exists — it is labelled augmentation, not compliance.',
      stat: '82%', statLabel: 'leaders backing frontline tech',
    },
    {
      no: '02', head: 'The phone can now see',
      body: 'A mid-range handset reads several codes at once and runs a vision model on its own NPU. No rugged scanner to buy, no bandwidth at the dock, no photo leaving the site.',
      stat: '0', statLabel: 'new hardware per worker',
    },
    {
      no: '03', head: 'The digital side is machine-readable',
      body: 'POs, e-invoices and e-way bills are structured data. Once the physical side becomes structured too, comparing them is arithmetic — not paperwork.',
      stat: '1', statLabel: 'missing half of the equation',
    },
  ];
  const w = 3.85, gap = 0.27;
  items.forEach((it, i) => {
    const x = G.mx + i * (w + gap);
    card(s, { x, y: 2.0, w, h: 4.0 });
    s.addShape('rect', { x, y: 2.0, w, h: 0.055, fill: { color: C.brand } });
    s.addText(it.no, { x: x + 0.28, y: 2.22, w: 1, h: 0.34, fontFace: MONO, fontSize: 14, bold: true, color: C.brand, charSpacing: 1 });
    s.addText(it.head, { x: x + 0.28, y: 2.62, w: w - 0.56, h: 0.76, fontFace: FONT, fontSize: 17, bold: true, color: C.ink, valign: 'top', lineSpacingMultiple: 1.02 });
    s.addText(it.body, { x: x + 0.28, y: 3.46, w: w - 0.56, h: 1.55, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.16 });
    s.addShape('line', { x: x + 0.28, y: 5.06, w: w - 0.56, h: 0, line: { color: C.hair, width: 0.75 } });
    s.addText(it.stat, { x: x + 0.28, y: 5.2, w: 1.4, h: 0.56, fontFace: FONT, fontSize: 28, bold: true, color: C.brand, valign: 'middle' });
    s.addText(it.statLabel, { x: x + 1.44, y: 5.24, w: w - 1.72, h: 0.5, fontFace: FONT, fontSize: 10.5, color: C.muted, valign: 'middle', lineSpacingMultiple: 1.0 });
  });

  source(s, 'Source: Zebra Technologies, Warehousing Vision Study 2025. Structural context: NITI Aayog on fragmented logistics, duplicate processes, manual handling and non-uniform documentation as efficiency constraints.', 6.18);
}

/* ═══════════════ 6 · The gap in today's stack ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The gap',
    title: 'Today\'s stack checks documents. Nothing checks the goods.',
    sub: 'Each layer proves something real — and each one assumes the layer below it was honest.',
    index: next(), total: TOTAL,
  });

  const rows = [
    { sys: 'WMS / ERP', proves: 'What should arrive', misses: 'Has no idea what did', c: C.slate },
    { sys: 'Barcode scanner', proves: 'That a code was scanned', misses: 'Not that the code matches the contents', c: C.slate },
    { sys: 'AP 3-way match', proves: 'Paper against paper', misses: 'Trusts a goods receipt typed by hand', c: C.slate },
  ];
  const hy = 2.06, rh = 0.80;
  ['System in place today', 'What it proves', 'What it cannot see'].forEach((h, i) => {
    const x = [G.mx + 0.26, G.mx + 3.5, G.mx + 7.3][i];
    s.addText(h.toUpperCase(), { x, y: hy, w: 3.4, h: 0.26, fontFace: FONT, fontSize: 10, bold: true, color: C.faint, charSpacing: 1.4 });
  });
  rows.forEach((r, i) => {
    const y = hy + 0.38 + i * rh;
    card(s, { x: G.mx, y, w: 12.09, h: rh - 0.13, shadow: false, line: C.hair });
    s.addText(r.sys, { x: G.mx + 0.26, y, w: 3.1, h: rh - 0.13, fontFace: FONT, fontSize: 14.5, bold: true, color: C.ink, valign: 'middle' });
    s.addText(r.proves, { x: G.mx + 3.5, y, w: 3.7, h: rh - 0.13, fontFace: FONT, fontSize: 13, color: C.slate, valign: 'middle' });
    s.addShape('line', { x: G.mx + 7.06, y: y + 0.16, w: 0, h: rh - 0.45, line: { color: C.hair, width: 1 } });
    s.addText(r.misses, { x: G.mx + 7.3, y, w: 4.8, h: rh - 0.13, fontFace: FONT, fontSize: 13, color: C.amber, valign: 'middle' });
  });

  const vy = hy + 0.38 + rows.length * rh + 0.14;
  card(s, { x: G.mx, y: vy, w: 12.09, h: 1.08, fill: C.brandTint, line: C.brand, lineWidth: 1.6 });
  s.addShape('rect', { x: G.mx, y: vy, w: 0.055, h: 1.08, fill: { color: C.brand } });
  s.addText('VeriTransit', { x: G.mx + 0.26, y: vy, w: 3.1, h: 1.08, fontFace: FONT, fontSize: 15.5, bold: true, color: C.brand, valign: 'middle' });
  s.addText('What is physically there', { x: G.mx + 3.5, y: vy, w: 3.7, h: 1.08, fontFace: FONT, fontSize: 13.5, bold: true, color: C.ink, valign: 'middle' });
  s.addText('Turns the cargo itself into structured data, at the moment a human is already standing in front of it.', {
    x: G.mx + 7.3, y: vy + 0.06, w: 4.6, h: 0.96, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'middle', lineSpacingMultiple: 1.12,
  });

  takeaway(s, { x: G.mx, y: 6.22, w: 12.09, text: 'We are not replacing the WMS, the scanner or the ledger. We are supplying the one input all three currently guess at.' });
}

/* ═══════════════ 7 · The solution, from the worker's side ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The solution',
    title: 'Open the shipment. Point the phone. Done.',
    sub: 'The worker\'s entire interaction. Everything else happens behind that.',
    index: next(), total: TOTAL,
  });

  const cards = [
    { no: '1', t: 'Open the shipment', b: 'The phone already knows the PO, the invoice and every carton expected. Nothing to type.', icon: '▤' },
    { no: '2', t: 'Point the phone', b: 'One camera pass over the pallet. Codes, labels, counts and condition are read together.', icon: '◎' },
    { no: '3', t: 'Done', b: 'A verdict in seconds, and only the exceptions come back to the worker.', icon: '✓' },
  ];
  const w = 3.85, gap = 0.27;
  cards.forEach((c, i) => {
    const x = G.mx + i * (w + gap);
    card(s, { x, y: 1.98, w, h: 2.22 });
    s.addShape('roundRect', { x: x + 0.28, y: 2.22, w: 0.52, h: 0.52, fill: { color: C.brand }, rectRadius: 0.5, line: { color: C.brand, width: 0 } });
    s.addText(c.no, { x: x + 0.28, y: 2.22, w: 0.52, h: 0.52, fontFace: FONT, fontSize: 17, bold: true, color: 'FFFFFF', align: 'center', valign: 'middle' });
    s.addText(c.t, { x: x + 0.96, y: 2.26, w: w - 1.24, h: 0.46, fontFace: FONT, fontSize: 18, bold: true, color: C.ink, valign: 'middle' });
    s.addText(c.b, { x: x + 0.28, y: 2.94, w: w - 0.56, h: 1.3, fontFace: FONT, fontSize: 13, color: C.slate, valign: 'top', lineSpacingMultiple: 1.18 });
    if (i < 2) arrow(s, { x: x + w + 0.02, y: 3.09, w: 0.23, h: 0, color: C.border, width: 1.5 });
  });

  card(s, { x: G.mx, y: 4.46, w: 12.09, h: 1.32, fill: C.inset, line: C.border, shadow: false });
  s.addText('The worker should not be filling in forms while the AI watches.', {
    x: G.mx + 0.34, y: 4.62, w: 11.4, h: 0.38, fontFace: FONT, fontSize: 17, bold: true, color: C.ink, valign: 'middle',
  });
  s.addText('Every screen we add is a screen someone has to tap through at 6am on a loading dock. The AI exists to remove the boring work — the counting, the tallying, the typing — not to supervise a human who is still doing it.', {
    x: G.mx + 0.34, y: 5.02, w: 11.4, h: 0.66, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.15,
  });

  takeaway(s, { x: G.mx, y: 6.14, w: 12.09, text: 'We do not replace the worker. We make the worker dramatically faster.' });
}

/* ═══════════════ 8 · What happens in that one pass ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'Under the hood',
    title: 'Four things happen in that single camera pass',
    sub: 'Three independent readings of the same pallet, then one comparison against the order.',
    index: next(), total: TOTAL,
  });

  const items = [
    { tag: 'READ', c: C.azure, bg: C.azureBg, t: 'Scan every code at once', b: ['Multiple QR and barcodes in one frame', 'Identifies the packages that already carry codes', 'No one-carton-at-a-time trigger pulling'] },
    { tag: 'READ', c: C.slate, bg: C.inset, t: 'Read the digital order', b: ['PO, invoice, manifest or delivery note', 'Photographed and parsed on the device', 'Gives the pass something to be right about'] },
    { tag: 'SEE', c: C.brand, bg: C.brandTint, t: 'Look at the cargo', b: ['Counts cartons and identifies products', 'Reads printed labels; flags damage and re-taping', 'Catches packages whose code is missing or unreadable'] },
    { tag: 'DECIDE', c: C.emerald, bg: C.emeraldBg, t: 'Reconcile the two sides', b: ['Physical set vs declared set, line by line', 'Every unit attributed to a source of truth', 'Anything unattributed becomes an exception'] },
  ];
  const w = 2.87, gap = 0.21;
  items.forEach((it, i) => {
    const x = G.mx + i * (w + gap);
    card(s, { x, y: 1.98, w, h: 3.72 });
    chip(s, { x: x + 0.24, y: 2.2, w: 0.92, h: 0.3, text: it.tag, fill: it.bg, color: it.c, size: 9.5 });
    s.addText(it.t, { x: x + 0.24, y: 2.62, w: w - 0.48, h: 0.72, fontFace: FONT, fontSize: 15.5, bold: true, color: C.ink, valign: 'top', lineSpacingMultiple: 1.02 });
    it.b.forEach((b, j) => {
      s.addText(b, { x: x + 0.24, y: 3.42 + j * 0.72, w: w - 0.46, h: 0.68, fontFace: FONT, fontSize: 11.5, color: C.slate, valign: 'top', bullet: { code: '2022' }, lineSpacingMultiple: 1.1 });
    });
    if (i < 3) s.addText('+', { x: x + w + 0.02, y: 3.6, w: 0.17, h: 0.3, fontFace: FONT, fontSize: 15, bold: true, color: C.border, align: 'center' });
  });

  card(s, { x: G.mx, y: 5.88, w: 12.09, h: 0.62, fill: C.brandTint, line: 'E7C3CC', shadow: false });
  s.addText('All four run on the device. A dock with no signal still gets an answer — and no photo of a customer\'s cargo leaves the site.', {
    x: G.mx + 0.3, y: 5.88, w: 11.5, h: 0.62, fontFace: FONT, fontSize: 13, bold: true, color: C.brand, valign: 'middle',
  });
}

/* ═══════════════ 9 · The reconciliation math ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The core mechanic',
    title: 'Every unit is attributed — or it becomes an exception',
    sub: 'A worked example: ten units on the PO, and what the pass resolves them to.',
    index: next(), total: TOTAL,
  });

  card(s, { x: G.mx, y: 1.94, w: 6.6, h: 4.1 });
  s.addChart(pptx.ChartType.bar, D.reconciliation.series.map((sr, i) => ({
    name: sr.name,
    labels: D.reconciliation.categories,
    values: sr.values,
  })), {
    ...chartBase,
    x: G.mx + 0.12, y: 2.08, w: 6.36, h: 3.8,
    barDir: 'col',
    barGrouping: 'stacked',
    barGapWidthPct: 130,
    chartColors: [C.azure, C.brand, C.amberDot, C.muted],
    showLegend: true,
    legendPos: 'b',
    legendFontFace: FONT,
    legendFontSize: 10,
    legendColor: C.slate,
    showValue: true,
    dataLabelFontSize: 12,
    dataLabelFontBold: true,
    dataLabelColor: 'FFFFFF',
    dataLabelFormatCode: '0;;;', // blank out the structural zeros in each stack
    valAxisMaxVal: 10, valAxisMinVal: 0, valAxisMajorUnit: 2,
    catAxisLabelFontSize: 11.5,
  });

  const rx = 7.56, rw = 5.15;
  card(s, { x: rx, y: 1.94, w: rw, h: 2.5 });
  s.addText('WHAT THE WORKER\'S PHONE COMPUTES', { x: rx + 0.28, y: 2.12, w: rw - 0.56, h: 0.26, fontFace: FONT, fontSize: 10, bold: true, color: C.faint, charSpacing: 1.3 });
  [
    ['Expected', '10', C.ink],
    ['QR identified', '7', C.azure],
    ['Vision identified', '2', C.brand],
    ['Unknown', '1', C.amberDot],
  ].forEach(([k, v, col], i) => {
    const y = 2.48 + i * 0.46;
    s.addText(k, { x: rx + 0.28, y, w: 3, h: 0.36, fontFace: MONO, fontSize: 13, color: C.slate, valign: 'middle' });
    s.addText(v, { x: rx + rw - 1.2, y, w: 0.9, h: 0.36, fontFace: MONO, fontSize: 17, bold: true, color: col, align: 'right', valign: 'middle' });
    if (i < 3) s.addShape('line', { x: rx + 0.28, y: y + 0.38, w: rw - 0.56, h: 0, line: { color: C.hair, width: 0.75 } });
  });

  card(s, { x: rx, y: 4.62, w: rw, h: 1.42, fill: C.amberBg, line: 'FDE68A' });
  s.addText('1 package needs a look', { x: rx + 0.3, y: 4.78, w: rw - 0.6, h: 0.34, fontFace: FONT, fontSize: 16, bold: true, color: C.amber });
  s.addText('Nine units are closed out automatically. The worker is handed one carton and a reason — not a list of ten to re-check.', {
    x: rx + 0.3, y: 5.14, w: rw - 0.6, h: 0.78, fontFace: FONT, fontSize: 12, color: C.slate, valign: 'top', lineSpacingMultiple: 1.14,
  });

  takeaway(s, { x: G.mx, y: 6.12, w: 12.09, text: 'The output is not a score or a probability. It is an attribution for every unit, which is what a goods receipt legally needs.' });
  source(s, 'Worked example on PO ZD-4471; the same arithmetic runs on any line count.', 6.68);
}

/* ═══════════════ 10 · Only the exception reaches the worker ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The interface',
    title: 'The worker sees three outcomes, and acts on one of them',
    sub: 'Attention is the scarce resource on a dock. We spend it only where a human is actually needed.',
    index: next(), total: TOTAL,
  });

  const verdicts = [
    { icon: '✓', label: 'MATCH', c: C.emerald, bg: C.emeraldBg, line: 'A7F3D0', t: 'Nothing to do', b: 'Physical set equals the declared set. The goods receipt is already written by the time the worker looks up.', share: 'the overwhelming majority of cartons' },
    { icon: '!', label: 'CHECK THIS ONE', c: C.amber, bg: C.amberBg, line: 'FDE68A', t: 'One carton, one question', b: 'A code would not read, a count is ambiguous, packaging looks disturbed. The phone says which carton and why.', share: 'the only thing the worker handles' },
    { icon: '✕', label: 'MISMATCH', c: C.crimson, bg: C.crimsonBg, line: 'FECACA', t: 'Stop and record', b: 'Wrong product, short count, or a package that does not belong to this shipment. Evidence is captured on the spot.', share: 'escalated with photos attached' },
  ];
  const w = 3.85, gap = 0.27;
  verdicts.forEach((v, i) => {
    const x = G.mx + i * (w + gap);
    card(s, { x, y: 1.98, w, h: 3.52, line: v.line, lineWidth: 1.4 });
    s.addShape('rect', { x, y: 1.98, w, h: 0.055, fill: { color: v.c } });
    s.addShape('roundRect', { x: x + 0.28, y: 2.24, w: 0.56, h: 0.56, fill: { color: v.bg }, line: { color: v.c, width: 1.4 }, rectRadius: 0.5 });
    s.addText(v.icon, { x: x + 0.28, y: 2.24, w: 0.56, h: 0.56, fontFace: FONT, fontSize: 20, bold: true, color: v.c, align: 'center', valign: 'middle' });
    s.addText(v.label, { x: x + 0.98, y: 2.3, w: w - 1.2, h: 0.44, fontFace: FONT, fontSize: 13, bold: true, color: v.c, charSpacing: 1, valign: 'middle' });
    s.addText(v.t, { x: x + 0.28, y: 3.0, w: w - 0.56, h: 0.4, fontFace: FONT, fontSize: 17, bold: true, color: C.ink });
    s.addText(v.b, { x: x + 0.28, y: 3.46, w: w - 0.56, h: 1.3, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.18 });
    s.addShape('line', { x: x + 0.28, y: 4.86, w: w - 0.56, h: 0, line: { color: C.hair, width: 0.75 } });
    s.addText(v.share, { x: x + 0.28, y: 4.96, w: w - 0.56, h: 0.46, fontFace: FONT, fontSize: 10.5, italic: true, color: C.muted, valign: 'top' });
  });

  card(s, { x: G.mx, y: 5.68, w: 12.09, h: 0.8, fill: C.inset, line: C.border, shadow: false });
  s.addText('No dashboards, no scores, no confidence percentages. A worker on a dock needs a decision, and the reason behind it in one line.', {
    x: G.mx + 0.34, y: 5.68, w: 11.4, h: 0.8, fontFace: FONT, fontSize: 13.5, bold: true, color: C.ink, valign: 'middle',
  });
}

/* ═══════════════ 11 · The record writes itself ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The output',
    title: 'The result becomes the record — nobody types it in again',
    sub: 'This is the step that removes the second error opportunity, and the one finance actually cares about.',
    index: next(), total: TOTAL,
  });

  const outs = [
    { t: 'Goods receipt (GRN)', b: 'Line by line, with the attribution behind every unit', c: C.emerald },
    { t: 'Evidence photos', b: 'The frames the decision was actually made from', c: C.brand },
    { t: 'Timestamp + location', b: 'When and where the check happened, from the device', c: C.azure },
    { t: 'Worker and site', b: 'Who stood in front of the cargo and signed it off', c: C.slate },
    { t: 'Discrepancy report', b: 'Generated only when there is something to report', c: C.amber },
    { t: 'Audit trail entry', b: 'Append-only, so a flagged scan cannot quietly disappear', c: C.crimson },
  ];
  const w = 3.85, h = 1.32, gapX = 0.27, gapY = 0.24;
  outs.forEach((o, i) => {
    const x = G.mx + (i % 3) * (w + gapX);
    const y = 1.98 + Math.floor(i / 3) * (h + gapY);
    card(s, { x, y, w, h });
    s.addShape('rect', { x, y: y + 0.14, w: 0.05, h: h - 0.28, fill: { color: o.c } });
    s.addText(o.t, { x: x + 0.28, y: y + 0.22, w: w - 0.5, h: 0.34, fontFace: FONT, fontSize: 14.5, bold: true, color: C.ink });
    s.addText(o.b, { x: x + 0.28, y: y + 0.6, w: w - 0.52, h: 0.6, fontFace: FONT, fontSize: 11.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.12 });
  });

  card(s, { x: G.mx, y: 5.14, w: 12.09, h: 1.24, fill: C.brandTint, line: 'E7C3CC' });
  s.addText('Offline is the normal case, not the failure case.', {
    x: G.mx + 0.34, y: 5.28, w: 11.4, h: 0.34, fontFace: FONT, fontSize: 15, bold: true, color: C.brand,
  });
  s.addText('Docks, basements and check posts have bad signal. Everything above is produced on the device and queued; when the connection returns it syncs and the server re-verifies it independently. The worker never waits for a network.', {
    x: G.mx + 0.34, y: 5.66, w: 11.4, h: 0.62, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.14,
  });
}

/* ═══════════════ 12 · Where FinTech comes in ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The commerce layer',
    title: 'We make the physical side of a B2B transaction machine-readable',
    sub: 'Not a payment system. The missing input to the 3-way match that every AP team already runs.',
    index: next(), total: TOTAL,
  });

  // the chain
  const cy = 2.04, ch = 0.92, cw = 2.44, gap = 0.30;
  const chain = [
    { t: 'Purchase order', s: 'what was agreed', c: C.slate, fill: C.surface },
    { t: 'Physical shipment', s: 'what turned up', c: C.brand, fill: C.brandTint },
    { t: 'VeriTransit check', s: 'the two, compared', c: C.brand, fill: C.brandTint },
    { t: 'Goods receipt', s: 'machine-generated', c: C.emerald, fill: C.emeraldBg },
  ];
  chain.forEach((cItem, i) => {
    const x = G.mx + i * (cw + gap);
    s.addShape('roundRect', { x, y: cy, w: cw, h: ch, fill: { color: cItem.fill }, line: { color: i === 3 ? 'A7F3D0' : i ? 'E7C3CC' : C.hair, width: 1.3 }, rectRadius: 0.06 });
    s.addText(cItem.t, { x: x + 0.12, y: cy + 0.16, w: cw - 0.24, h: 0.34, fontFace: FONT, fontSize: 13.5, bold: true, color: cItem.c, align: 'center' });
    s.addText(cItem.s, { x: x + 0.12, y: cy + 0.5, w: cw - 0.24, h: 0.3, fontFace: FONT, fontSize: 10.5, color: C.muted, align: 'center' });
    if (i < 3) arrow(s, { x: x + cw + 0.05, y: cy + ch / 2, w: gap - 0.1, h: 0, color: C.border, width: 1.5 });
  });
  const lastX = G.mx + 3 * (cw + gap);
  s.addText('→  into the invoice match', {
    x: lastX + cw + 0.1, y: cy, w: 1.33, h: ch, fontFace: FONT, fontSize: 11, color: C.muted, valign: 'middle',
  });

  // two branches
  const by = 3.34, bh = 2.4, bw = 5.9;
  card(s, { x: G.mx, y: by, w: bw, h: bh, fill: C.emeraldBg, line: 'A7F3D0', lineWidth: 1.4 });
  chip(s, { x: G.mx + 0.3, y: by + 0.26, w: 1.98, h: 0.32, text: 'EVERYTHING AGREES', fill: 'FFFFFF', color: C.emerald, size: 8.5 });
  s.addText('PO ✓   +   Goods received ✓   +   Invoice ✓', {
    x: G.mx + 0.3, y: by + 0.7, w: bw - 0.6, h: 0.42, fontFace: MONO, fontSize: 14.5, bold: true, color: '065F46', valign: 'middle',
  });
  s.addText('Straight through to normal AP processing. No AP clerk touches it, because the goods-receipt line was never a guess in the first place.', {
    x: G.mx + 0.3, y: by + 1.2, w: bw - 0.6, h: 0.96, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.16,
  });

  const bx2 = G.mx + bw + 0.29;
  card(s, { x: bx2, y: by, w: bw, h: bh, fill: C.amberBg, line: 'FDE68A', lineWidth: 1.4 });
  chip(s, { x: bx2 + 0.3, y: by + 0.26, w: 1.62, h: 0.32, text: 'THEY DISAGREE', fill: 'FFFFFF', color: C.amber, size: 8.5 });
  s.addText('PO ✓   +   Physical ✕', {
    x: bx2 + 0.3, y: by + 0.7, w: bw - 0.6, h: 0.42, fontFace: MONO, fontSize: 14.5, bold: true, color: '92400E', valign: 'middle',
  });
  s.addText('An exception is created at the dock, with the delta, the evidence photos and the carton it belongs to — hours after the truck arrives instead of weeks after the invoice.', {
    x: bx2 + 0.3, y: by + 1.2, w: bw - 0.6, h: 0.96, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.16,
  });

  card(s, { x: G.mx, y: 5.94, w: 12.09, h: 0.64, fill: C.inset, line: C.border, shadow: false });
  s.addText('We are not saying "our AI controls payments." We are saying the physical half of the transaction finally has evidence behind it.', {
    x: G.mx + 0.34, y: 5.94, w: 11.4, h: 0.64, fontFace: FONT, fontSize: 13, bold: true, color: C.ink, valign: 'middle',
  });
}

/* ═══════════════ 13 · Architecture ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'Architecture',
    title: 'Deterministic checks decide; the model explains and routes',
    sub: 'The AI agent sits after verification, never inside it — a misread can cost a supervisor a walk, never a wrong release.',
    index: next(), total: TOTAL,
  });

  const L = { x: G.mx, w: 12.09 };
  const lane = (y, h, label, tint) => {
    s.addShape('roundRect', { x: L.x, y, w: L.w, h, fill: { color: tint }, line: { color: C.hair, width: 1 }, rectRadius: 0.04 });
    s.addText(label.toUpperCase(), {
      x: L.x + 0.14, y: y + 0.08, w: 4.2, h: 0.24, fontFace: FONT, fontSize: 8.5, bold: true, color: C.faint, charSpacing: 1.2,
    });
  };

  // Lane 1 — inputs
  lane(1.92, 1.02, 'Inputs', C.surface);
  const inW = 2.62, inGap = 0.28;
  ['PO / invoice / manifest', 'Signed package labels', 'The cargo itself', 'Site + worker identity'].forEach((t, i) => {
    node(s, { x: L.x + 0.24 + i * (inW + inGap), y: 2.24, w: inW, h: 0.58, title: t, fill: C.inset, line: C.border, titleSize: 11.5 });
  });

  // Lane 2 — device
  lane(3.14, 1.5, 'On the device — offline capable', C.brandTint);
  const dW = 2.62;
  [
    ['Multi-code scanner', 'QR + barcode, many at once'],
    ['Document reader', 'on-device OCR'],
    ['Vision check', 'count, identify, damage'],
    ['Reconciler', 'physical vs declared'],
  ].forEach(([t, sub], i) => {
    node(s, { x: L.x + 0.24 + i * (dW + inGap), y: 3.46, w: dW, h: 1.04, title: t, sub, fill: C.surface, line: 'E7C3CC', accent: C.brand, titleSize: 11.5, subSize: 9.5 });
  });
  arrow(s, { x: L.x + 6.05, y: 2.94, w: 0, h: 0.14, color: C.brand, width: 1.4 });

  // Lane 3 — server
  lane(4.84, 1.5, 'Server — authoritative', C.surface);
  [
    ['Re-verification', 'cross-device, cross-shift'],
    ['Match engine', 'PO · invoice · GRN'],
    ['Audit chain', 'append-only, hash-linked'],
    ['Ops agent', 'records, reports, alerts'],
  ].forEach(([t, sub], i) => {
    const accent = i === 3 ? C.azure : C.slate;
    node(s, { x: L.x + 0.24 + i * (dW + inGap), y: 5.16, w: dW, h: 1.04, title: t, sub, fill: i === 3 ? C.azureBg : C.inset, line: i === 3 ? 'BAE6FD' : C.border, accent, titleSize: 11.5, subSize: 9.5 });
  });
  arrow(s, { x: L.x + 6.05, y: 4.64, w: 0, h: 0.14, color: C.slate, width: 1.4 });

  s.addText('Consumers:  ERP / WMS   ·   AP exception queue   ·   Telegram for supervisors   ·   dispute & insurance evidence packs', {
    x: G.mx, y: 6.42, w: 12.09, h: 0.3, fontFace: FONT, fontSize: 11, color: C.muted,
  });
}

/* ═══════════════ 14 · Animated workflow ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The workflow, end to end',
    title: 'One pass over ten cartons, from camera to goods receipt',
    index: next(), total: TOTAL,
  });

  const mp4 = path.join(MOTION, 'output.mp4');
  const poster = path.join(MOTION, 'poster.png');
  const box = { x: 2.02, y: 1.30, w: 9.30, h: 5.23 }; // 16:9, so the poster is not stretched

  if (fs.existsSync(mp4) && fs.existsSync(poster)) {
    s.addMedia({
      type: 'video', path: mp4,
      cover: 'data:image/png;base64,' + fs.readFileSync(poster).toString('base64'),
      ...box,
    });
  } else if (fs.existsSync(poster)) {
    s.addImage({ path: poster, ...box });
  }
  s.addText('15-second embedded clip — press play in slideshow. The final frame is the static diagram, so the slide reads correctly in print and PDF.', {
    x: G.mx, y: 6.6, w: 12.09, h: 0.3, fontFace: FONT, fontSize: 10, color: C.faint,
  });
}

/* ═══════════════ 15 · The agent sits after verification ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The AI operations agent',
    title: 'The agent does not decide whether the cargo matches',
    sub: 'It takes a structured, already-decided result and does the chores that follow it.',
    index: next(), total: TOTAL,
  });

  // what it must never do
  card(s, { x: G.mx, y: 1.96, w: 4.2, h: 4.06, fill: C.crimsonBg, line: 'FECACA', lineWidth: 1.4 });
  s.addText('NOT THE AGENT\'S JOB', { x: G.mx + 0.3, y: 2.14, w: 3.6, h: 0.26, fontFace: FONT, fontSize: 10, bold: true, color: C.crimson, charSpacing: 1.3 });
  s.addText('Judging the match', { x: G.mx + 0.3, y: 2.46, w: 3.6, h: 0.42, fontFace: FONT, fontSize: 18, bold: true, color: C.ink });
  s.addText('Whether ten units are present is arithmetic over scans and vision output — rule-based, explainable, reproducible, and testable. A language model has no business in that decision, and putting one there is how a demo becomes an audit finding.', {
    x: G.mx + 0.3, y: 2.98, w: 3.6, h: 1.9, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.18,
  });
  s.addShape('line', { x: G.mx + 0.3, y: 5.1, w: 3.6, h: 0, line: { color: 'FECACA', width: 1 } });
  s.addText('The model can raise a flag. It can never clear one on its own.', {
    x: G.mx + 0.3, y: 5.22, w: 3.6, h: 0.66, fontFace: FONT, fontSize: 12, bold: true, color: C.crimson, valign: 'top', lineSpacingMultiple: 1.14,
  });

  // what it does
  const ax = G.mx + 4.48, aw = 7.61;
  card(s, { x: ax, y: 1.96, w: aw, h: 4.06 });
  s.addText('WHAT IT ACTUALLY DOES, ONCE THE RESULT EXISTS', { x: ax + 0.3, y: 2.14, w: aw - 0.6, h: 0.26, fontFace: FONT, fontSize: 10, bold: true, color: C.faint, charSpacing: 1.3 });

  const tasks = [
    ['Updates the database', 'shipment, line items, goods-receipt state'],
    ['Generates the discrepancy report', 'supplier-ready, with evidence attached'],
    ['Notifies the right person', 'supervisor, supplier, AP — by role, not broadcast'],
    ['Creates the GRN record', 'in the format the ERP expects'],
    ['Answers questions in Telegram', '"status SHP-90231", "why was it held?"'],
    ['Queues everything offline', 'and replays it when connectivity returns'],
  ];
  tasks.forEach(([t, sub], i) => {
    const x = ax + 0.3 + (i % 2) * 3.6;
    const y = 2.5 + Math.floor(i / 2) * 1.14;
    s.addShape('roundRect', { x, y, w: 3.4, h: 0.98, fill: { color: C.inset }, line: { color: C.border, width: 1 }, rectRadius: 0.05 });
    s.addText(t, { x: x + 0.2, y: y + 0.12, w: 3.0, h: 0.34, fontFace: FONT, fontSize: 12.5, bold: true, color: C.ink, valign: 'middle' });
    s.addText(sub, { x: x + 0.2, y: y + 0.46, w: 3.05, h: 0.44, fontFace: FONT, fontSize: 10.5, color: C.muted, valign: 'top', lineSpacingMultiple: 1.05 });
  });

  takeaway(s, { x: G.mx, y: 6.18, w: 12.09, text: 'Deterministic engines decide. The model explains, drafts and routes — inside a policy it cannot widen.' });
}

/* ═══════════════ 16 · The demo ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'The demo',
    title: 'Thirty seconds, one mismatch, the whole story',
    sub: 'Not ten screens. One shipment that is wrong, and what the phone does about it.',
    index: next(), total: TOTAL,
  });

  const steps = [
    { t: 'The order', b: 'PO says 5 Dell monitors', c: C.slate, bg: C.surface, mono: '5 × MONITOR-DELL-24' },
    { t: 'The pass', b: 'Worker points the phone at 5 boxes', c: C.brand, bg: C.brandTint, mono: 'one camera sweep' },
    { t: 'The codes', b: '4 QR codes detected', c: C.azure, bg: C.azureBg, mono: '4 / 5 resolved' },
    { t: 'The eyes', b: 'Vision sees 4 monitors and 1 HP printer', c: C.brand, bg: C.brandTint, mono: '1 × PRINTER-HP ← not on PO' },
    { t: 'The verdict', b: 'MISMATCH — 1 item needs attention', c: C.crimson, bg: C.crimsonBg, mono: 'MISMATCH' },
    { t: 'The record', b: 'Discrepancy report generated, database updated', c: C.emerald, bg: C.emeraldBg, mono: 'auto — no typing' },
  ];
  const w = 1.87, gap = 0.16;
  steps.forEach((st, i) => {
    const x = G.mx + i * (w + gap);
    card(s, { x, y: 2.0, w, h: 3.1, fill: st.bg, line: i === 4 ? 'FECACA' : C.hair, lineWidth: i === 4 ? 1.8 : 1 });
    s.addShape('rect', { x, y: 2.0, w, h: 0.05, fill: { color: st.c } });
    s.addText(String(i + 1), { x: x + 0.16, y: 2.16, w: 0.5, h: 0.28, fontFace: MONO, fontSize: 11, bold: true, color: st.c });
    s.addText(st.t, { x: x + 0.16, y: 2.5, w: w - 0.32, h: 0.34, fontFace: FONT, fontSize: 13.5, bold: true, color: C.ink });
    s.addText(st.b, { x: x + 0.16, y: 2.88, w: w - 0.32, h: 1.3, fontFace: FONT, fontSize: 11.5, color: C.slate, valign: 'top', lineSpacingMultiple: 1.14 });
    s.addShape('line', { x: x + 0.16, y: 4.48, w: w - 0.32, h: 0, line: { color: C.hair, width: 0.75 } });
    s.addText(st.mono, { x: x + 0.14, y: 4.56, w: w - 0.28, h: 0.48, fontFace: MONO, fontSize: 8.5, color: st.c, valign: 'top', lineSpacingMultiple: 1.05 });
    if (i < 5) s.addText('›', { x: x + w - 0.04, y: 3.3, w: 0.24, h: 0.3, fontFace: FONT, fontSize: 16, bold: true, color: C.border, align: 'center' });
  });

  card(s, { x: G.mx, y: 5.32, w: 12.09, h: 1.06, fill: C.crimsonBg, line: 'FECACA', lineWidth: 1.4 });
  s.addText('The printer is the point.', { x: G.mx + 0.34, y: 5.44, w: 3.4, h: 0.36, fontFace: FONT, fontSize: 15, bold: true, color: C.crimson, valign: 'middle' });
  s.addText('A barcode scanner reports four good scans and moves on. Only something that looks at the cargo notices that the fifth box is a different product entirely — and that is exactly the error that surfaces six weeks later as an invoice dispute.', {
    x: G.mx + 3.9, y: 5.42, w: 8.1, h: 0.9, fontFace: FONT, fontSize: 12.5, color: C.slate, valign: 'middle', lineSpacingMultiple: 1.14,
  });
}

/* ═══════════════ 17 · What it changes ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'Expected impact',
    title: 'The work shrinks; errors surface while evidence exists',
    sub: 'Modelled on a 40-carton inbound. These are targets for the pilot to confirm, not measured results.',
    index: next(), total: TOTAL,
  });

  card(s, { x: G.mx, y: 1.94, w: 6.0, h: 3.62 });
  s.addText('Minutes per inbound shipment', { x: G.mx + 0.28, y: 2.08, w: 5.4, h: 0.28, fontFace: FONT, fontSize: 13, bold: true, color: C.ink });
  s.addText('ILLUSTRATIVE', { x: G.mx + 4.42, y: 2.08, w: 1.3, h: 0.24, fontFace: FONT, fontSize: 8.5, bold: true, color: C.amber, align: 'right', charSpacing: 1 });
  s.addChart(pptx.ChartType.bar, [
    { name: 'Manual today', labels: D.cycle.categories, values: D.cycle.manual },
    { name: 'With VeriTransit', labels: D.cycle.categories, values: D.cycle.veritransit },
  ], {
    ...chartBase,
    x: G.mx + 0.1, y: 2.36, w: 5.8, h: 3.1,
    barDir: 'bar',
    barGapWidthPct: 60,
    chartColors: [C.border, C.brand],
    showLegend: true, legendPos: 'b', legendFontFace: FONT, legendFontSize: 10, legendColor: C.slate,
    valAxisMaxVal: 16, valAxisMinVal: 0, valAxisMajorUnit: 4,
    dataLabelFontSize: 10,
    dataLabelPosition: 'outEnd',
    catAxisLabelFontSize: 10,
  });

  card(s, { x: 6.88, y: 1.94, w: 5.83, h: 3.62 });
  s.addText('Where the error is caught (% of errors)', { x: 7.16, y: 2.08, w: 5.2, h: 0.28, fontFace: FONT, fontSize: 13, bold: true, color: C.ink });
  s.addText('ILLUSTRATIVE', { x: 11.1, y: 2.08, w: 1.3, h: 0.24, fontFace: FONT, fontSize: 8.5, bold: true, color: C.amber, align: 'right', charSpacing: 1 });
  s.addChart(pptx.ChartType.bar, [
    { name: 'Today', labels: D.detection.labels, values: D.detection.today },
    { name: 'With VeriTransit', labels: D.detection.labels, values: D.detection.veritransit },
  ], {
    ...chartBase,
    x: 6.98, y: 2.36, w: 5.63, h: 3.1,
    barDir: 'col',
    barGapWidthPct: 55,
    chartColors: [C.border, C.brand],
    showLegend: true, legendPos: 'b', legendFontFace: FONT, legendFontSize: 10, legendColor: C.slate,
    valAxisMaxVal: 100, valAxisMinVal: 0, valAxisMajorUnit: 25,
    dataLabelFormatCode: '0"%"',
    dataLabelFontSize: 9.5,
    catAxisLabelFontSize: 9.5,
  });

  takeaway(s, { x: G.mx, y: 5.72, w: 12.09, text: 'Catching a short shipment at the dock is a two-minute conversation. Catching it at invoice matching is a six-week receivable.' });
  source(s, 'Illustrative model, not measured: manual = per-carton visual check, tally sheet and later ERP entry; VeriTransit = one camera pass plus exception handling. Editable in data/charts.json; to be replaced with pilot timings.', 6.42);
}

/* ═══════════════ 18 · Status and roadmap ═══════════════ */
{
  const s = pptx.addSlide();
  frame(s, {
    eyebrow: 'Where we are',
    title: 'The hard parts are built; the pilot is what is left',
    sub: 'Kotlin end to end — one set of models shared by the app, the server, the console and the bot.',
    index: next(), total: TOTAL,
  });

  const built = [
    ['Android app', 'Compose UI, field + warehouse + delivery flows, camera scanning'],
    ['On-device AI', 'vision and document reading on the phone\'s NPU, offline'],
    ['Backend', 'Kotlin/Ktor, Postgres, reconciliation and append-only audit chain'],
    ['Admin console', 'shipments, documents, discrepancy queue, evidence'],
    ['Telegram agent surface', 'query a shipment, receive alerts, approve in chat'],
    ['Signed labels', 'Ed25519 tokens verified on the device with no network'],
  ];
  card(s, { x: G.mx, y: 1.96, w: 6.36, h: 4.1 });
  chip(s, { x: G.mx + 0.3, y: 2.14, w: 1.62, h: 0.32, text: 'RUNNING TODAY', fill: C.emeraldBg, color: C.emerald, size: 9 });
  built.forEach(([t, b], i) => {
    const y = 2.62 + i * 0.56;
    s.addShape('ellipse', { x: G.mx + 0.32, y: y + 0.1, w: 0.11, h: 0.11, fill: { color: C.emerald } });
    s.addText(t, { x: G.mx + 0.56, y, w: 2.3, h: 0.26, fontFace: FONT, fontSize: 12.5, bold: true, color: C.ink });
    s.addText(b, { x: G.mx + 0.56, y: y + 0.24, w: 5.4, h: 0.3, fontFace: FONT, fontSize: 10.5, color: C.muted });
  });

  const nx = G.mx + 6.64, nw = 5.45;
  card(s, { x: nx, y: 1.96, w: nw, h: 4.1, fill: C.inset, line: C.border });
  chip(s, { x: nx + 0.3, y: 2.14, w: 1.3, h: 0.32, text: 'NEXT', fill: 'FFFFFF', color: C.brand, size: 9 });
  const nextUp = [
    ['Pilot on one inbound dock', 'measure the numbers on slide 17 for real', C.brand],
    ['ERP / WMS write-back', 'GRN straight into the customer\'s system', C.brand],
    ['Vendor scorecards', 'discrepancy history per supplier and route', C.slate],
    ['Payment release signal', 'verified receipt as a gate, under finance policy', C.slate],
  ];
  nextUp.forEach(([t, b, col], i) => {
    const y = 2.64 + i * 0.86;
    s.addShape('roundRect', { x: nx + 0.3, y, w: nw - 0.6, h: 0.72, fill: { color: 'FFFFFF' }, line: { color: C.hair, width: 1 }, rectRadius: 0.05 });
    s.addShape('rect', { x: nx + 0.3, y: y + 0.1, w: 0.045, h: 0.52, fill: { color: col } });
    s.addText(t, { x: nx + 0.48, y: y + 0.08, w: nw - 0.84, h: 0.3, fontFace: FONT, fontSize: 12.5, bold: true, color: C.ink, valign: 'middle' });
    s.addText(b, { x: nx + 0.48, y: y + 0.38, w: nw - 0.84, h: 0.26, fontFace: FONT, fontSize: 10.5, color: C.muted });
  });

  takeaway(s, { x: G.mx, y: 6.16, w: 12.09, text: 'What we need from a pilot partner is one dock, one week, and access to the PO feed.' });
}

/* ═══════════════ 19 · Closing ═══════════════ */
{
  const s = pptx.addSlide();
  s.background = { color: C.brandDeep };

  s.addText('THE POSITION', {
    x: G.mx, y: 1.42, w: 8, h: 0.3, fontFace: FONT, fontSize: 12, bold: true, color: 'E7C3CC', charSpacing: 3.4,
  });
  s.addText('AI-powered physical verification for B2B commerce — built as a worker copilot, not another enterprise system.', {
    x: G.mx, y: 1.9, w: 11.4, h: 2.2,
    fontFace: FONT, fontSize: 31, bold: true, color: 'FFFFFF', lineSpacingMultiple: 1.12, valign: 'top',
  });
  s.addShape('rect', { x: G.mx, y: 3.70, w: 0.9, h: 0.045, fill: { color: 'D97706' } });

  [
    ['We do not replace the worker.', 'One camera pass replaces the counting, the tally sheet and the data entry.'],
    ['We do not decide the money.', 'We supply the physical evidence the three-way match has always been missing.'],
    ['We do not need new hardware.', 'A phone the site already owns, working offline, on the dock.'],
  ].forEach(([t, b], i) => {
    const x = G.mx + i * 4.03;
    s.addText(t, { x, y: 4.30, w: 3.8, h: 0.56, fontFace: FONT, fontSize: 14.5, bold: true, color: 'FFFFFF', valign: 'top', lineSpacingMultiple: 1.05 });
    s.addText(b, { x, y: 4.90, w: 3.75, h: 0.9, fontFace: FONT, fontSize: 12, color: 'C89AA6', valign: 'top', lineSpacingMultiple: 1.16 });
  });

  s.addShape('line', { x: G.mx, y: 6.4, w: 12.09, h: 0, line: { color: '8A4257', width: 0.75 } });
  s.addText('VeriTransit', { x: G.mx, y: 6.56, w: 4, h: 0.3, fontFace: FONT, fontSize: 13, bold: true, color: 'FFFFFF' });
  s.addText('Every B2B transaction has two realities. We make them agree at the dock.', {
    x: G.mx + 3.6, y: 6.58, w: 8.49, h: 0.3, fontFace: FONT, fontSize: 11.5, color: 'C89AA6', align: 'right',
  });
}

pptx.writeFile({ fileName: OUT }).then(() => {
  console.log('wrote', OUT, `(${n} slides)`);
});
