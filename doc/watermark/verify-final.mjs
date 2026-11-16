import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--autoplay-policy=no-user-gesture-required','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
const idRow = process.argv[2];
await p.goto('http://localhost/tool/mrds/post/' + idRow, { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(2500);
await p.evaluate(() => { const v = document.querySelector('video'); if (v) { v.muted = true; v.play().catch(() => {}); } });
await p.waitForTimeout(6000);
const st = await p.evaluate(() => {
  const v = document.querySelector('video');
  const txt = document.querySelector('.md-content') ? document.querySelector('.md-content').innerText : '';
  const imgs = [...document.querySelectorAll('.md-gcell img')];
  return { video: v ? { dur: Math.round(v.duration), t: Math.round(v.currentTime), w: v.videoWidth, h: v.videoHeight, paused: v.paused } : null,
    imgs: imgs.length, imgOk: imgs.filter((i) => i.naturalWidth > 0).length,
    noise: /初代网红夫妻|高迪大美离婚/.test(txt), textLen: txt.length };
});
console.log(JSON.stringify(st));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-new-detail.png' });
await p.close();
const l = await ctx.newPage();
await l.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await l.waitForTimeout(2500);
const cards = await l.evaluate(() => [...document.querySelectorAll('.mr-card')].slice(0, 8).map((c) => (c.getAttribute('data-title') || c.innerText || '').split('\n')[1] || ''));
const imgsOk = await l.evaluate(() => { const i = [...document.querySelectorAll('.mr-cover img, .mr-card img')]; return { total: i.length, ok: i.filter((x) => x.naturalWidth > 0).length }; });
console.log('list imgs', JSON.stringify(imgsOk));
await l.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-list3.png' });
await b.close();