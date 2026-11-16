import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 900 } });
const p = await ctx.newPage();
await p.addInitScript(() => { localStorage.setItem('ms.mrds.listState', JSON.stringify({ page: 1, pageStart: 1, scrollY: 0, at: Date.now() })); });
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
// 直接打开图片多的那条（search 定位后点开）
await p.fill('.mr-search', '占星猫');
await p.press('.mr-search', 'Enter');
await p.waitForTimeout(3000);
const n = await p.evaluate(() => document.querySelectorAll('[data-post-id]').length);
if (n > 0) { await p.evaluate(() => document.querySelector('[data-post-id]').click()); await p.waitForTimeout(6000); }
const st = await p.evaluate(() => {
  const q = (s) => document.querySelector(s);
  const info = (el) => el ? { ch: el.clientHeight, sh: el.scrollHeight, ov: getComputedStyle(el).overflowY } : null;
  return { cards: document.querySelectorAll('.md-gcell').length, side: info(q('.md-side')), main: info(q('.md-main')) };
});
console.log('弹窗内:', JSON.stringify(st));
const mb = await p.locator('.md-main').boundingBox().catch(() => null);
if (mb) { await p.mouse.move(mb.x + mb.width / 2, mb.y + 200); await p.mouse.wheel(0, 600); await p.waitForTimeout(600); }
const a = await p.evaluate(() => ({ side: Math.round((document.querySelector('.md-side') || {}).scrollTop || 0), main: Math.round((document.querySelector('.md-main') || {}).scrollTop || 0) }));
console.log('滚右栏后:', JSON.stringify(a), a.main > 0 ? '右栏独立滚动 ✓' : '右栏没动 ✗');
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/two-scroll-ok.png' });
await b.close();