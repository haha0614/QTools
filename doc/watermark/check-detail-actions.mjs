import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 900 } });
const p = await ctx.newPage();
const errs = [];
p.on('pageerror', (e) => errs.push(String(e.message).slice(0, 120)));
await p.goto('http://localhost/tool/mrds?focus=97', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
await p.evaluate(() => document.querySelector('[data-post-id="97"]').click());
await p.waitForTimeout(7000);
const st = await p.evaluate(() => {
  const tools = document.querySelector('.mr-sheet-tools');
  const vids = [...document.querySelectorAll('.md-player-box')];
  return {
    toolbar: tools ? tools.textContent.replace(/\s+/g, ' ').trim() : null,
    actionRow: (document.querySelector('.md-actions') || {}).textContent?.replace(/\s+/g, ' ').trim(),
    perVideoDownload: document.querySelectorAll('.md-vdl').length,
    errorHints: document.querySelectorAll('.md-player-box .md-player-hint').length,
    boxes: vids.length
  };
});
console.log('结果:', JSON.stringify(st));
console.log('errors:', JSON.stringify(errs.slice(0,2)));
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/detail-actions.png' });
await b.close();