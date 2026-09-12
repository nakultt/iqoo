// VeriTransit deck design system.
// Palette is the product's own "Field Operational Precision" language
// (app/src/main/java/com/veritransit/inspector/ui/theme/Color.kt) so the deck
// and the app read as one thing: alabaster canvas, one burgundy accent,
// restrained diagnostic colours for status only.

const C = {
  bg:       'F4F3F0', // alabaster canvas
  surface:  'FFFFFF',
  inset:    'EFEDE9',
  hair:     'E2E8F0',
  border:   'CBD5E1',

  ink:      '0F172A',
  slate:    '334155',
  muted:    '64748B',
  faint:    '94A3B8',

  brand:    '7E1530', // primary accent — transit burgundy
  brandDeep:'5C001D',
  brandTint:'FBF1F3',

  emerald:  '059669', // match
  emeraldBg:'ECFDF5',
  amber:    'B45309', // needs a look
  amberDot: 'D97706',
  amberBg:  'FFFBEB',
  crimson:  'B91C1C', // mismatch
  crimsonBg:'FEF2F2',
  azure:    '0284C7', // secondary accent — machine-read / QR
  azureBg:  'F0F9FF',
};

const FONT = 'Aptos';
const MONO = 'Consolas';

// 16:9 canvas is 13.333 x 7.5in
const G = {
  W: 13.333,
  H: 7.5,
  mx: 0.62,          // left/right margin
  contentW: 12.093,  // W - 2*mx
  eyebrowY: 0.42,
  titleY: 0.66,
  subY: 1.30,
  bodyTop: 1.82,     // content zone starts here on a titled slide
  footY: 6.94,
};

/** Shared slide chrome: eyebrow, title, optional deck line, footer rule + page number. */
function frame(slide, { eyebrow, title, sub, index, total, accent = C.brand, dark = false }) {
  const ink = dark ? 'FFFFFF' : C.ink;
  const mute = dark ? 'CBD5E1' : C.muted;

  slide.background = { color: dark ? C.brandDeep : C.bg };

  if (eyebrow) {
    slide.addText(eyebrow.toUpperCase(), {
      x: G.mx, y: G.eyebrowY, w: G.contentW, h: 0.24,
      fontFace: FONT, fontSize: 11.5, bold: true, charSpacing: 1.8,
      color: dark ? 'E7C3CC' : accent,
    });
  }
  // A title that needs two lines would collide with the deck line, so it drops a
  // size and the deck line moves down with it.
  const twoLine = title && title.length > 65;
  if (title) {
    slide.addText(title, {
      x: G.mx, y: G.titleY, w: G.contentW, h: twoLine ? 0.92 : 0.62,
      fontFace: FONT, fontSize: twoLine ? 24 : 27, bold: true, color: ink,
      lineSpacingMultiple: 1.05, valign: 'top',
    });
  }
  if (sub) {
    slide.addText(sub, {
      x: G.mx, y: twoLine ? 1.5 : G.subY, w: G.contentW - 0.4, h: 0.34,
      fontFace: FONT, fontSize: 15, color: mute, valign: 'top',
    });
  }
  if (index) {
    slide.addShape('line', {
      x: G.mx, y: G.footY, w: G.contentW, h: 0,
      line: { color: dark ? '8A4257' : C.hair, width: 0.75 },
    });
    slide.addText('VeriTransit  ·  AI verification for B2B commerce', {
      x: G.mx, y: G.footY + 0.08, w: 6, h: 0.26,
      fontFace: FONT, fontSize: 9.5, color: dark ? 'C89AA6' : C.faint,
    });
    slide.addText(`${index} / ${total}`, {
      x: G.W - G.mx - 2, y: G.footY + 0.08, w: 2, h: 0.26,
      fontFace: FONT, fontSize: 9.5, color: dark ? 'C89AA6' : C.faint, align: 'right',
    });
  }
}

/** A white card with a hairline border — the deck's only container. */
function card(slide, { x, y, w, h, fill = C.surface, line = C.hair, lineWidth = 1, radius = 0.035, shadow = true }) {
  slide.addShape('roundRect', {
    x, y, w, h,
    fill: { color: fill },
    line: { color: line, width: lineWidth },
    rectRadius: radius,
    ...(shadow ? { shadow: { type: 'outer', color: '0F172A', opacity: 0.06, blur: 10, offset: 2, angle: 90 } } : {}),
  });
}

/** Headline number + label, for metric rows. */
function kpi(slide, { x, y, w, h = 1.5, value, label, note, color = C.brand, valueSize = 42 }) {
  card(slide, { x, y, w, h });
  slide.addText(value, {
    x: x + 0.26, y: y + 0.14, w: w - 0.5, h: 0.72,
    fontFace: FONT, fontSize: valueSize, bold: true, color, valign: 'middle',
  });
  slide.addText(label, {
    x: x + 0.26, y: y + 0.82, w: w - 0.5, h: 0.46,
    fontFace: FONT, fontSize: 13.5, bold: true, color: C.ink, valign: 'top', lineSpacingMultiple: 1.0,
  });
  if (note) {
    slide.addText(note, {
      x: x + 0.26, y: y + 1.32, w: w - 0.46, h: 0.9,
      fontFace: FONT, fontSize: 11, color: C.muted, valign: 'top', lineSpacingMultiple: 1.0,
    });
  }
}

/** Small status chip (VERIFIED / CHECK / MISMATCH and friends). */
function chip(slide, { x, y, w, h = 0.34, text, fill, color, size = 11 }) {
  slide.addShape('roundRect', {
    x, y, w, h, fill: { color: fill }, line: { color, width: 1 }, rectRadius: 0.5,
  });
  slide.addText(text, {
    x, y, w, h, fontFace: FONT, fontSize: size, bold: true, color,
    align: 'center', valign: 'middle', charSpacing: 0.6,
  });
}

/** Source / method footnote pinned above the footer rule. */
function source(slide, text, y = 6.58) {
  slide.addText(text, {
    x: G.mx, y, w: G.contentW, h: 0.28,
    fontFace: FONT, fontSize: 9.5, color: C.faint, italic: false, valign: 'top',
  });
}

/** The "so what" line under a chart. */
function takeaway(slide, { x, y, w, text, color = C.brand }) {
  slide.addShape('rect', { x, y, w: 0.035, h: 0.52, fill: { color } });
  slide.addText(text, {
    x: x + 0.18, y: y - 0.04, w: w - 0.18, h: 0.6,
    fontFace: FONT, fontSize: 13.5, bold: true, color: C.ink, valign: 'middle',
    lineSpacingMultiple: 1.05,
  });
}

/** Rounded node for hand-built diagrams. */
function node(slide, { x, y, w, h, title, sub, fill = C.surface, line = C.hair, color = C.ink, titleSize = 12.5, subSize = 10, accent }) {
  slide.addShape('roundRect', {
    x, y, w, h, fill: { color: fill }, line: { color: line, width: 1.15 }, rectRadius: 0.06,
  });
  if (accent) slide.addShape('rect', { x: x + 0.001, y: y + 0.1, w: 0.045, h: h - 0.2, fill: { color: accent } });
  slide.addText(title, {
    x: x + 0.16, y: sub ? y + 0.08 : y, w: w - 0.3, h: sub ? 0.3 : h,
    fontFace: FONT, fontSize: titleSize, bold: true, color,
    valign: sub ? 'top' : 'middle', align: 'left',
  });
  if (sub) {
    slide.addText(sub, {
      x: x + 0.16, y: y + 0.34, w: w - 0.28, h: h - 0.4,
      fontFace: FONT, fontSize: subSize, color: C.muted, valign: 'top', lineSpacingMultiple: 0.95,
    });
  }
}

/** Directional connector between diagram nodes. */
function arrow(slide, { x, y, w, h = 0, color = C.border, width = 1.25, flipH = false, flipV = false, dash }) {
  slide.addShape('line', {
    x, y, w, h,
    line: { color, width, endArrowType: 'triangle', ...(dash ? { dashType: dash } : {}) },
    flipH, flipV,
  });
}

module.exports = { C, FONT, MONO, G, frame, card, kpi, chip, source, takeaway, node, arrow };
