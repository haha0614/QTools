import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds?focus=97', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
await p.evaluate(() => document.querySelector('[data-post-id="97"]').click());
await p.waitForTimeout(6000);
const st = await p.evaluate(() => {
  const y = (sel) => { const el = document.querySelector(sel); return el ? Math.round(el.getBoundingClientRect().top) : null; };
  return { title: y('.md-title'), tags: y('.md-tags'), meta: y('.md-meta'), side: y('.md-side'), content: y('.md-content'), docH: Math.round(document.querySelector('.mr-sheet-body').scrollHeight) };
});
console.log('顺序:', JSON.stringify(st));
console.log(st.title < st.side && st.side < st.content ? '标题 → 视频 → 正文 ✓' : '顺序不对 ✗');
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/mobile-detail3.png' });
await b.close();