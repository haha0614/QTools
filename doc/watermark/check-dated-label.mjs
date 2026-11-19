import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1890, height: 922 } });
const p = await ctx.newPage();
const j = [];
p.on('response', async (r) => { if (r.url().includes('/tool/web/facets')) { try { const d = await r.json(); j.push({ dated: d.dated, total: d.total }); } catch (e) {} } });
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(2500);
const label = await p.evaluate(() => {
  const els = [...document.querySelectorAll('.mr-dates-all, .mr-dates-all *')];
  const t = els.map((e) => (e.textContent || '').trim()).filter((s) => s.includes('已有日期'));
  return t.length ? t[0].replace(/\s+/g, ' ') : (document.body.innerText.match(/已有日期[^\n]*/) || [''])[0];
});
console.log('页面显示:', label);
console.log('facets 响应:', JSON.stringify(j));
await b.close();