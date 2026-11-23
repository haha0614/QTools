import { chromium } from 'playwright-core';
const C = ['C:/Program Files/Google/Chrome/Application/chrome.exe'].find(() => true);
const b = await chromium.launch({ executablePath: C, headless: true, args: ['--mute-audio','--no-sandbox'] });
const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
const bad = [];
p.on('response', (r) => { const u = r.url(); if (/\/web\/(\d+)\/(cover|p1)\.jpg/.test(u) && r.status() >= 400) bad.push(u + ' ' + r.status()); });
const errs = [];
p.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 120)); });
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded' });
await p.waitForTimeout(6000);
const stat = await p.evaluate(() => {
  const imgs = [...document.querySelectorAll('.mr-cover img, .mr-pcover img, .mr-fcover img, .mr-row-cover img')];
  const broken = imgs.filter((i) => i.complete && i.naturalWidth === 0).length;
  return { cards: document.querySelectorAll('.mr-card').length, imgs: imgs.length, broken };
});
await p.screenshot({ path: 'E:/111/project/QTools/tmp/mrds-covers.png' });
console.log(JSON.stringify({ ...stat, bad404: bad.length, sample: bad.slice(0, 3), errs: errs.slice(0, 2) }));
await b.close();
