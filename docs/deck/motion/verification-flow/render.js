// Renders index.html frame-by-frame with headless Chrome, then muxes to MP4 with ffmpeg.
//   node motion/verification-flow/render.js
// Output: output.mp4 (1920x1080, 30fps) + poster.png (the final, static-ready frame).

const puppeteer = require('puppeteer-core');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const HERE = __dirname;
const FRAMES = path.join(HERE, 'frames');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FPS = 30;

(async () => {
  fs.rmSync(FRAMES, { recursive: true, force: true });
  fs.mkdirSync(FRAMES, { recursive: true });

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--force-device-scale-factor=1', '--hide-scrollbars'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080, deviceScaleFactor: 1 });
  await page.goto('file://' + path.join(HERE, 'index.html'), { waitUntil: 'load' });

  const dur = await page.evaluate(() => window.DUR);
  const total = Math.round(dur * FPS);
  process.stdout.write(`rendering ${total} frames (${dur}s @ ${FPS}fps)\n`);

  for (let i = 0; i < total; i++) {
    await page.evaluate(t => window.render(t), i / FPS);
    await page.screenshot({
      path: path.join(FRAMES, String(i).padStart(4, '0') + '.png'),
      clip: { x: 0, y: 0, width: 1920, height: 1080 },
    });
    if (i % 60 === 0) process.stdout.write(`  ${i}/${total}\n`);
  }

  // Poster = the settled final frame, which doubles as the static slide image.
  await page.evaluate(t => window.render(t), dur);
  await page.screenshot({ path: path.join(HERE, 'poster.png'), clip: { x: 0, y: 0, width: 1920, height: 1080 } });
  await browser.close();

  const out = path.join(HERE, 'output.mp4');
  fs.rmSync(out, { force: true });
  execFileSync('ffmpeg', [
    '-y', '-framerate', String(FPS), '-i', path.join(FRAMES, '%04d.png'),
    '-c:v', 'libx264', '-profile:v', 'high', '-pix_fmt', 'yuv420p',
    '-crf', '20', '-movflags', '+faststart', out,
  ], { stdio: 'inherit' });

  fs.rmSync(FRAMES, { recursive: true, force: true });
  console.log('\nwrote', out, 'and poster.png');
})();
