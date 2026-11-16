import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3000);
const info = await p.evaluate(() => {
  const saved = window.localStorage.getItem('ms.mrds.lastSeen');
  const cards = [...document.querySelectorAll('[data-post-id]')].map((c) => c.getAttribute('data-post-id'));
  return { saved, cards: cards.length, first5: cards.slice(0, 5), hasSaved: saved ? cards.includes(JSON.parse(saved).id) : null };
});
console.log('debug:', JSON.stringify(info));
const bar = await p.evaluate(() => !!document.querySelector('.mr-resume'));
console.log('bar present:', bar);
if (bar) {
  const before = await p.evaluate(() => document.querySelectorAll('.mr-hit').length);
  await p.locator('.mr-resume button').first().click();
  await p.waitForTimeout(3000);
  const after = await p.evaluate(() => ({ hit: document.querySelectorAll('.mr-hit').length, err: (document.querySelector('.mr-err') || {}).textContent || '', bar: !!document.querySelector('.mr-resume'), cards: document.querySelectorAll('[data-post-id]').length }));
  console.log('locate result:', JSON.stringify(after));
}
await b.close();