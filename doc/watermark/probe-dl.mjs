import { chromium } from 'playwright-core';
const URL0 = process.argv[2];
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 900 } });
const p = await ctx.newPage();
for (let attempt = 1; attempt <= 3; attempt++) {
  try { await p.goto(URL0, { waitUntil: 'domcontentloaded', timeout: 45000 }); break; }
  catch (e) { console.log('goto attempt', attempt, String(e.message).slice(0, 60)); await p.waitForTimeout(1500); }
}
await p.waitForTimeout(3000);
for (let i = 0; i < 6; i++) { await p.mouse.wheel(0, 1600); await p.waitForTimeout(300); }
await p.waitForTimeout(1200);
const out = await p.evaluate(() => [...document.querySelectorAll('img')].map((el) => {
  const src = el.getAttribute('src') || '';
  const inline = src.startsWith('data:image/') && src.length > 20000;
  const attr = [...el.attributes].filter((a) => a.value.indexOf('upload_01') >= 0).map((a) => a.name)[0] || '';
  if (!inline && !attr) return null;
  const chain = [];
  let n = el;
  while (n && n !== document.body && chain.length < 5) { chain.push(n.tagName.toLowerCase() + (n.className ? '.' + String(n.className).split(' ').filter(Boolean).slice(0, 2).join('.') : '') + (n.id ? '#' + n.id : '')); n = n.parentElement; }
  return { alt: (el.getAttribute('alt') || '').slice(0, 30), cls: String(el.className || '').slice(0, 24), len: src.length, attr, chain: chain.join(' < ') };
}).filter(Boolean));
console.log(JSON.stringify(out, null, 0).replace(/},/g, '},\n'));
await b.close();