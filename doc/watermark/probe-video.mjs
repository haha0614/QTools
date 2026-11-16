import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1300, height: 950 } });
const p = await ctx.newPage();
for (const id of ['201994', '202007']) {
  const resp = await p.goto('https://able.heermtat.cc/archives/' + id + '/', { waitUntil: 'domcontentloaded', timeout: 45000 });
  const raw = (await resp.text()) || '';
  await p.waitForTimeout(1500);
  const hasPlayer = await p.evaluate(() => !!document.querySelector('video, .dplayer, [id*=player], iframe'));
  const idx = raw.indexOf('m3u8');
  console.log(id, '| rawLen', raw.length, '| m3u8Idx', idx, '| hasPlayer', hasPlayer, '| videoJson', raw.slice(Math.max(0, idx - 120), idx + 60).replace(/\s+/g, ' ').slice(0, 180));
}
await b.close();