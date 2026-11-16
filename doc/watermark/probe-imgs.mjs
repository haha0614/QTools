import { chromium } from 'playwright-core';
const URL0 = process.argv[2];
const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--disable-blink-features=AutomationControlled'] });
const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } });
const page = await ctx.newPage();
const resp = await page.goto(URL0, { waitUntil: 'domcontentloaded', timeout: 60000 });
const raw = await resp.text();
const rawHits = (raw.match(/upload_01/g) || []).length;
const rawLoadImage = (raw.match(/loadImage\(/g) || []).length;
await page.waitForTimeout(4000);
for (let i = 0; i < 8; i++) { await page.mouse.wheel(0, 1400); await page.waitForTimeout(500); }
await page.waitForTimeout(2000);
const out = await page.evaluate(() => {
  const imgs = [...document.querySelectorAll('img')];
  const detail = imgs.map((el) => {
    const attrs = [...el.attributes].map((a) => a.name + '=' + a.value.slice(0, 90));
    return attrs.join(' | ');
  }).filter((s) => s.indexOf('upload_01') >= 0 || s.indexOf('xkrkllgl') >= 0);
  const own = [...document.querySelectorAll('[data-xkrkllgl]')].map((el) => el.tagName + ' ' + el.getAttribute('data-xkrkllgl'));
  const allAttrVals = [];
  for (const el of document.querySelectorAll('*')) for (const a of el.attributes) if (a.value.indexOf('upload_01') >= 0) allAttrVals.push(el.tagName + '.' + (el.className || '') + ' [' + a.name + '] ' + a.value.slice(0, 100));
  const galleryText = [...document.querySelectorAll('a,img')].filter((e) => (e.getAttribute('alt') || '').indexOf('相关图片') >= 0).map((e) => e.getAttribute('alt') + ' -> ' + (e.getAttribute('data-xkrkllgl') || e.getAttribute('src')));
  return { imgTotal: imgs.length, detail, own, allAttrVals: allAttrVals.slice(0, 60), allAttrCount: allAttrVals.length, galleryText };
});
console.log(JSON.stringify({ finalUrl: page.url(), rawHits, rawLoadImage, ...out }, null, 1));
await browser.close();