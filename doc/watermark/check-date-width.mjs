import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 950 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(4000);
const st = await p.evaluate(() => {
  const row = document.querySelector('.mr-dates-all');
  const dp = document.querySelector('.mr-dates-all .mr-datepicker');
  return { rowH: Math.round(row.getBoundingClientRect().height), dpW: dp ? Math.round(dp.getBoundingClientRect().width) : null, dpCls: dp ? dp.className : null };
});
console.log('筛选行高:', st.rowH, '| 日期控件宽度:', st.dpW, st.dpW && st.dpW <= 260 ? '（已收窄 ✓）' : '（仍然过宽 ✗）');
await p.screenshot({ path: 'E:/111/project/QTools/doc/shots/date-width.png' });
await b.close();