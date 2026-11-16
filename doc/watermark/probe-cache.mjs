
import { chromium } from 'playwright-core';
const EXE = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const BASE = 'https://able.heermtat.cc';
const b = await chromium.launch({ executablePath: EXE, headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();
for (const [name, url] of [['HOME', BASE + '/'], ['CAT', BASE + '/category/mrds/']]) {
  const resp = await p.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await p.waitForTimeout(2000);
  const h = resp.headers();
  const ids = await p.evaluate(() => [...document.querySelectorAll('a[href*="/archives/"]')].slice(0, 8).map((a) => (a.getAttribute('href') || '').match(/archives\/(\d+)/)?.[1]));
  const has = await p.evaluate(() => document.body.innerHTML.includes('/archives/202053/'));
  console.log(name, '| status', resp.status(), '| cache:', h['cf-cache-status'] || h['x-cache'] || '-', '| age:', h['age'] || '-', '| 含202053:', has, '| 前8:', JSON.stringify(ids));
}
await b.close();
