
import { chromium } from 'playwright-core';
const EXE = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const b = await chromium.launch({ executablePath: EXE, headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1400, height: 1000 } });
const p = await ctx.newPage();
const urls = [];
p.on('request', (r) => { const u = r.url(); if (/\.m3u8/.test(u)) urls.push(u.slice(0, 120)); });
await p.goto('https://able.heermtat.cc/archives/200564/', { waitUntil: 'domcontentloaded', timeout: 45000 });
await p.waitForTimeout(3000);
await p.mouse.wheel(0, 600);
await p.waitForTimeout(2500);
const dom = await p.evaluate(() => ({
  videos: [...document.querySelectorAll('video')].map((v) => ({ src: (v.currentSrc || v.src || '').slice(0, 90), poster: (v.poster || '').slice(0, 60) })),
  sources: [...document.querySelectorAll('source')].map((s) => (s.src || '').slice(0, 90)),
  iframes: [...document.querySelectorAll('iframe')].map((f) => (f.src || '').slice(0, 90)),
  players: document.querySelectorAll('.vjs-player, .dplayer, [id*=player]').length
}));
console.log('页面 video:', JSON.stringify(dom.videos, null, 1));
console.log('source:', JSON.stringify(dom.sources));
console.log('iframe:', JSON.stringify(dom.iframes));
console.log('请求到的 m3u8:', JSON.stringify(urls.slice(0, 6), null, 1));
await b.close();
