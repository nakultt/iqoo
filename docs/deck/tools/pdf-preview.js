// Dev QA only: rasterise the exported PDF to PNGs so slide layout can be eyeballed.
//   node tools/pdf-preview.js <pdf> <outDir> [firstPage] [lastPage]
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');

const [pdfPath, outDir, first = '1', last = '99'] = process.argv.slice(2);
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

(async () => {
  fs.mkdirSync(outDir, { recursive: true });
  const b64 = fs.readFileSync(pdfPath).toString('base64');
  const pdfjs = path.join(__dirname, '../node_modules/pdfjs-dist/build/pdf.mjs');
  const worker = path.join(__dirname, '../node_modules/pdfjs-dist/build/pdf.worker.mjs');

  const html = `<style>html,body{margin:0;padding:0}canvas{display:block}</style><canvas id="c"></canvas><script type="module">
    import * as pdfjsLib from '../node_modules/pdfjs-dist/build/pdf.mjs';
    pdfjsLib.GlobalWorkerOptions.workerSrc = '../node_modules/pdfjs-dist/build/pdf.worker.mjs';
    const raw = atob(window.__B64);
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    window.__doc = await pdfjsLib.getDocument({ data: bytes }).promise;
    window.__render = async (num, scale) => {
      const page = await window.__doc.getPage(num);
      const vp = page.getViewport({ scale });
      const c = document.getElementById('c');
      c.width = vp.width; c.height = vp.height;
      await page.render({ canvasContext: c.getContext('2d'), viewport: vp }).promise;
      return [vp.width, vp.height];
    };
    window.__ready = true;
  </script>`;

  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--allow-file-access-from-files', '--hide-scrollbars'],
  });
  const page = await browser.newPage();
  page.on('pageerror', e => console.error('page error:', e.message));
  page.on('console', m => { if (m.type() === 'error') console.error('console:', m.text()); });
  const tmpHtml = path.join(__dirname, '_preview.html');
  fs.writeFileSync(tmpHtml, html);
  await page.evaluateOnNewDocument(b => { window.__B64 = b; }, b64);
  await page.goto('file://' + tmpHtml, { waitUntil: 'load' });
  await page.waitForFunction('window.__ready === true', { timeout: 60000 });

  const total = await page.evaluate(() => window.__doc.numPages);
  const lo = Math.max(1, parseInt(first, 10));
  const hi = Math.min(total, parseInt(last, 10));
  const [pw, ph] = await page.evaluate((n, s) => window.__render(n, s), lo, 1.4);
  await page.setViewport({ width: Math.ceil(pw), height: Math.ceil(ph) });
  await new Promise(r => setTimeout(r, 200));
  for (let i = lo; i <= hi; i++) {
    const [w, h] = await page.evaluate((n, s) => window.__render(n, s), i, 1.4);
    await page.screenshot({
      path: path.join(outDir, `slide-${String(i).padStart(2, '0')}.png`),
      clip: { x: 0, y: 0, width: Math.ceil(w), height: Math.ceil(h) },
    });
  }
  await browser.close();
  fs.rmSync(tmpHtml, { force: true });
  console.log(`rendered pages ${lo}-${hi} of ${total} to ${outDir}`);
})();
