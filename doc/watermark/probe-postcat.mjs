
import { chromium } from 'playwright-core';
const EXE = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const BASE = 'https://able.heermtat.cc';
const b = await chromium.launch({ executablePath: EXE, headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();
for (const id of ['202053', '202076']) {
  await p.goto(BASE + '/archives/' + id + '/', { waitUntil: 'domcontentloaded', timeout: 45000 });
  await p.waitForTimeout(1200);
  const r = await p.evaluate(() => {
    const rel = [...document.querySelectorAll('a[rel~="category"], a[rel="category"]')].map((a) => a.textContent.trim());
    const postCat = [...document.querySelectorAll('.post-category a, .post-meta a[href*="/category/"], .entry-meta a[href*="/category/"]')].map((a) => a.textContent.trim());
    const kw = [...document.querySelectorAll('.keywords a')].map((a) => a.textContent.trim());
    return { rel, postCat, kw: kw.slice(0, 8) };
  });
  console.log(id, JSON.stringify(r));
}
await b.close();
