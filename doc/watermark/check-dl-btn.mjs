import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
const st = await p.evaluate(() => { const btn = document.querySelector('.mr-dl-toggle'); if (!btn) return { none: true }; const r = btn.getBoundingClientRect(); return { text: btn.textContent.trim(), x: Math.round(r.x), y: Math.round(r.y), visible: r.width > 0 }; });
console.log('下载记录按钮:', JSON.stringify(st));
if (!st.none) {
  await p.locator('.mr-dl-toggle').click();
  await p.waitForTimeout(600);
  console.log('点开后面板:', JSON.stringify(await p.evaluate(() => ({ panel: !!document.querySelector('.mr-dl-panel'), empty: (document.querySelector('.mr-dl-empty') || {}).textContent || null }))));
}
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/dl-button.png' });
await b.close();