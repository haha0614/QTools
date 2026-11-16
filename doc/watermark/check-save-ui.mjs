import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: false, args: ['--mute-audio','--window-position=-32000,-32000'] });
const ctx = await b.newContext({ viewport: { width: 1600, height: 1000 } });
const p = await ctx.newPage();
await p.goto('http://localhost/tool/mrds/post/3305', { waitUntil: 'networkidle', timeout: 60000 });
await p.waitForTimeout(3500);
// 直接打开保存弹窗（无登录会提示，这里只验证 UI 结构）
await p.locator('.md-btn', { hasText: '下载' }).first().click();
await p.waitForTimeout(800);
const st = await p.evaluate(() => ({
  dialog: !!document.querySelector('.el-dialog'),
  msg: (document.querySelector('.md-save-msg') || {}).textContent || '',
  outerCreateBtns: [...document.querySelectorAll('.md-save-addbtn, .md-save-padd')].map((e) => e.textContent.trim())
}));
console.log('dialog state:', JSON.stringify(st));
await b.close();