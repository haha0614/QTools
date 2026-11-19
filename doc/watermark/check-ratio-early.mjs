import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 900 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds?focus=148950', { waitUntil: 'domcontentloaded', timeout: 60000 });
await p.waitForTimeout(5000);
await p.evaluate(() => document.querySelector('[data-post-id="148950"]').click());
// 极早采样：播放器刚挂载、视频还没元数据时，框的宽高比就应该等于 16:9（1280x720）
await p.waitForTimeout(700);
const early = await p.evaluate(() => {
  const box = document.querySelector('.md-player-box');
  if (!box) return null;
  const r = box.getBoundingClientRect();
  const v = box.querySelector('video');
  return { w: Math.round(r.width), h: Math.round(r.height), ratio: (r.width / r.height).toFixed(2), ready: v ? v.readyState : null };
});
console.log('早期采样:', JSON.stringify(early));
await p.waitForTimeout(6000);
const late = await p.evaluate(() => {
  const box = document.querySelector('.md-player-box');
  const r = box.getBoundingClientRect();
  const v = box.querySelector('video');
  return { w: Math.round(r.width), h: Math.round(r.height), ratio: (r.width / r.height).toFixed(2), ready: v ? v.readyState : null, vw: v ? v.videoWidth : null, vh: v ? v.videoHeight : null };
});
console.log('稳定后采样:', JSON.stringify(late));
console.log('结论:', early && late && Math.abs(early.ratio - late.ratio) < 0.05 ? '加载中与加载后比例一致 ✓' : '仍然跳动 ✗');
await b.close();