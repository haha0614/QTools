import { chromium } from 'playwright-core';
const ids = process.argv.slice(2);
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--autoplay-policy=no-user-gesture-required','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
for (const id of ids) {
  const p = await ctx.newPage();
  await p.goto('http://localhost/tool/mrds/post/' + id, { waitUntil: 'networkidle', timeout: 60000 });
  await p.waitForTimeout(2500);
  await p.evaluate(() => { const v = document.querySelector('video'); if (v) { v.muted = true; v.play().catch(() => {}); } });
  await p.waitForTimeout(6000);
  const st = await p.evaluate(() => { const v = document.querySelector('video'); const box = document.querySelector('.md-player-box'); const br = box && box.getBoundingClientRect(); return v ? { dur: Math.round(v.duration), t: Math.round(v.currentTime), w: v.videoWidth, h: v.videoHeight, box: br ? [Math.round(br.width), Math.round(br.height)] : null, buf: v.buffered.length ? Math.round(v.buffered.end(0)) : 0 } : null; });
  console.log('id', id, JSON.stringify(st));
  await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-play-' + id + '.png' });
  await p.close();
}
await b.close();