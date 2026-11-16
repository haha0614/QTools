import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
await p.evaluate(async () => {
  const r = await fetch('/api/tool/web/posts?page=16&size=24');
  const j = await r.json();
  localStorage.setItem('ms.mrds.scrollAnchor', JSON.stringify({ id: (j.rows||[])[0].id, title: (j.rows||[])[0].title, page: 16, top: 120, at: Date.now() }));
});
await p.reload({ waitUntil: 'domcontentloaded' });
await p.waitForTimeout(4000);
await p.locator('.mr-resume button', { hasText: '回去' }).click();
await p.waitForTimeout(2200);
const ys = [];
for (let i = 0; i < 6; i++) {
  await p.mouse.wheel(0, -700);
  for (let k = 0; k < 8; k++) { ys.push(await p.evaluate(() => Math.round(window.scrollY))); await p.waitForTimeout(90); }
}
console.log('滚动位置序列:', JSON.stringify(ys));
const drops = [];
for (let i = 1; i < ys.length; i++) if (ys[i] - ys[i-1] > 400) drops.push({ i, from: ys[i-1], to: ys[i] });
console.log('向上（数值变小）突变:', JSON.stringify(ys.filter((v,i)=> i>0 && v < ys[i-1] - 400).slice(0,5)), '| 向下突变(加载补偿):', JSON.stringify(drops.slice(0,5)));
console.log('最小值 =', Math.min(...ys), Math.min(...ys) > 800 ? '（从未掉回页面顶部区域 ✓）' : '（出现过顶部区域 ✗）');
await b.close();