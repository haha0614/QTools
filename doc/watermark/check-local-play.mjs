import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--autoplay-policy=no-user-gesture-required','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds/post/3305', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3500);
const st = await p.evaluate(() => {
  const v = document.querySelector('video');
  const btn = [...document.querySelectorAll('.md-btn')].find((x) => /入库/.test(x.textContent || ''));
  return { btn: btn ? btn.textContent.trim() : null, disabled: btn ? btn.disabled : null, src: v ? v.currentSrc.slice(0, 60) : null, dur: v ? Math.round(v.duration) : null };
});
console.log('detail state:', JSON.stringify(st));
await p.evaluate(() => { const v = document.querySelector('video'); if (v) { v.muted = true; v.play().catch(() => {}); } });
await p.waitForTimeout(5000);
const playing = await p.evaluate(() => { const v = document.querySelector('video'); return v ? { t: Math.round(v.currentTime), paused: v.paused, ready: v.readyState } : null; });
console.log('playing:', JSON.stringify(playing));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mrds-local-play.png' });
await b.close();