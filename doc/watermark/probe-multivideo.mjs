import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true, args: ['--mute-audio'] });
const ctx = await b.newContext({ viewport: { width: 1200, height: 800 } });
const p = await ctx.newPage();
const ids = ['201984', '202021', '201159', '201418', '201828'];
for (const id of ids) {
  try {
    const resp = await p.goto('https://able.heermtat.cc/archives/' + id + '/', { waitUntil: 'domcontentloaded', timeout: 45000 });
    const raw = (await resp.text()) || '';
    const set = new Set();
    let from = 0;
    while (true) {
      const k = raw.indexOf('m3u8', from);
      if (k < 0) break;
      from = k + 4;
      const q = String.fromCharCode(34);
      const sk = raw.lastIndexOf(q + 'url' + q + ':', k);
      if (sk < 0) continue;
      let j = sk + 7, out = '';
      while (j < raw.length && raw.charCodeAt(j) !== 34) { const c = raw.charCodeAt(j); if (c === 92 && j + 1 < raw.length) { j++; out += raw[j]; } else { out += raw[j]; } j++; }
      if (out.indexOf('m3u8') >= 0) set.add(out.split('?')[0]);
    }
    console.log(id, 'distinct m3u8:', set.size);
  } catch (e) { console.log(id, 'ERR'); }
}
await b.close();