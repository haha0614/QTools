
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import { sql, execStdin } from '../lib/db.mjs';
const C = ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'].find(p=>{try{return fs.existsSync(p)}catch{return false}});
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const LIMIT = Number(process.env.LIMIT || 30);

const listSql = 'select source_id, url from tool_web_post order by id desc limit ' + LIMIT;
const rows = sql(listSql)
  .trim().split(/\r?\n/).filter(Boolean).map(l => { const [sid, url] = l.split('\t'); return { sid, url }; });
console.log('to crawl', rows.length);

const b = await chromium.launch({ executablePath: C, headless: false, args: ['--mute-audio','--window-position=-32000,-32000','--window-size=1200,900'] });
const p = await (await b.newContext({ userAgent: UA, viewport: { width: 1280, height: 900 } })).newPage();

const esc = (s) => String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "''");
let done = 0;
for (const row of rows) {
  try {
    const resp = await p.goto(row.url, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await p.waitForTimeout(600);
    // ★ 关键：图片真实地址只在**原始 HTML** 的 loadImage("…") 里；
    // 渲染后脚本会被执行/替换，DOM 里就查不到了。所以先用响应体把原图抓下来。
    let rawHtml = '';
    try { rawHtml = (await resp.text()) || ''; } catch (e) { rawHtml = ''; }
    const d = await p.evaluate((raw) => {
      const txt = el => (el ? (el.textContent || '').trim() : '');
      // 详情页面包屑：首页 > <分类> > <标题> —— 取倒数第二段
      let category = '';
      const crumb = document.querySelector('.xqbj-breadcrumb-container');
      if (crumb) {
        const parts = (crumb.innerText || '').split('>').map(s => s.trim()).filter(Boolean);
        if (parts.length >= 2) category = parts[parts.length - 2];
      }
      // 真实正文在 .post 里（.post-content 常被广告串占满，图片在它的兄弟节点里）；
      // 取「图片数量最多」的那个候选容器更稳。
      let box = null, bestScore = -1;
      document.querySelectorAll('.post, article, .post-content, .entry-content').forEach((el) => {
        const score = el.querySelectorAll('img').length * 2 + el.querySelectorAll('p').length + (el.innerHTML.match(/loadImage\(/g) || []).length * 2;
        if (score > bestScore) { bestScore = score; box = el; }
      });
      const blocks = [];
      let imageCount = 0, videoCount = 0;
      if (box) {
        // 帖内图的真实地址写在页面的 loadImage("…") 调用里（<img> 只是懒加载占位），
        // 所以整页扫这些调用，按出现顺序取。
        const imgUrls = [];
        // 用字符串切分解析 loadImage("URL") —— 正则里反斜杠在多层模板串里太容易写错，
        // 这里直接按 'loadImage(' 切开再取引号内内容，稳且可读。
        const scan = (text) => {
          if (!text) return;
          const parts = String(text).split('loadImage(');
          for (let i = 1; i < parts.length; i++) {
            const chunk = parts[i];
            const q1 = chunk.search(/["']/);
            if (q1 < 0) continue;
            const quote = chunk[q1];
            const q2 = chunk.indexOf(quote, q1 + 1);
            if (q2 < 0) continue;
            const url = chunk.slice(q1 + 1, q2).trim();
            if (/^https?:/.test(url) && !imgUrls.includes(url)) imgUrls.push(url);
          }
        };
        scan(document.documentElement.outerHTML);
        if (!imgUrls.length) scan(raw);
        // 只保留「本帖自己的图」：源站帖内图都在 /upload-images/ 下；
        // /uploads/default/… 与 /usr/… 是站点公共素材（推荐位、图标），每个帖子都一样，必须剔掉
        // 只留本帖自己的图：/common/ 是站点公共横幅（每个帖子完全一样），/usr/ 是主题素材
        // 只排除「站点公共素材」：/common/（每个帖子一样的横幅）、/usr/（主题图标）。
        // 注意 /uploads/default/other/<日期>/… 是**帖内图**（按上传日期分目录），不能排除 ——
        // 上一版把它一起排掉，导致所有帖子都变成"只有文字"。
        const own = imgUrls.filter((u) => !/\/common\//.test(u) && !/\/usr\//.test(u));
        // 文字段落（去掉广告串）
        const texts = [];
        box.querySelectorAll('p, h2, h3, blockquote').forEach(el => {
          const t = txt(el).replace(/\s+/g, ' ');
          // 过滤每个帖子都有的站内公告 / 推荐位 / 广告串（用户口径：详情页要干净）
          const NOISE = /在线约炮|同城约炮|广告|最新地址|自动回复|推荐浏览器|邮箱自动|热门大赛|重磅热瓜|温馨提示|重要提示|点击可下载|官方网址|分享每日大赛|复制最新网址|谨防假冒|永久有效/;
          if (t && t.length > 1 && !NOISE.test(t)) texts.push(t);
        });
        const hasVideo = !!box.querySelector('video, iframe, .dplayer, [id*="player" i], #player');
        texts.forEach(t => blocks.push({ type: 'text', text: t.slice(0, 2000) }));
        own.forEach(u => { blocks.push({ type: 'image', url: u }); imageCount++; });
        if (hasVideo) { blocks.push({ type: 'video', index: 1 }); videoCount = 1; }
      }
      // 播放地址写在页面配置 JSON 里："video":{"url":"https://…m3u8?auth_key=…","type":"hls"}
      // 它是签名地址（有有效期），存下来只供"立刻播放/抽帧当封面"用，不能当长期资源。
      let videoUrl = '';
      const src = raw || document.documentElement.outerHTML;
      const k = src.indexOf("m3u8");
      if (k >= 0) {
        // 往前找 "url":" 的起点，再往后读到闭合引号；JSON 里斜杠被转义成 \\/（charCode 92），要去掉
        const startKey = src.lastIndexOf(String.fromCharCode(34) + "url" + String.fromCharCode(34) + ":", k);
        if (startKey >= 0) {
          let j = startKey + 7;
          let out = "";
          while (j < src.length && src.charCodeAt(j) !== 34) {
            const c = src.charCodeAt(j);
            if (c === 92 && j + 1 < src.length) { j++; out += src[j]; } else { out += src[j]; }
            j++;
          }
          if (out.indexOf("m3u8") >= 0) videoUrl = out;
        }
      }
      return { category, blocks, imageCount, videoCount, videoUrl, firstImage: (blocks.find(x => x.type === 'image') || {}).url || '' };
    }, rawHtml);
    const contentJson = JSON.stringify(d.blocks).replace(/'/g, "''").replace(/\\/g, '\\\\');
    const text = esc(d.blocks.filter(x => x.type === 'text').map(x => x.text).join('\n')).slice(0, 60000);
    const updSql = "update tool_web_post set video_url='" + esc(d.videoUrl || '') + "', content='" + contentJson + "', content_text='" + text + "', image_count=" + d.imageCount + ", video_count=" + d.videoCount + (d.category ? ", category='" + esc(d.category) + "'" : '') + (d.firstImage ? ", cover='" + esc(d.firstImage) + "'" : '') + " where source_id='" + row.sid + "';\n";
    await execStdin(updSql);
    done++;
    console.log('  ok', row.sid, '| blocks', d.blocks.length, '| imgs', d.imageCount, '| video', d.videoCount, '| cat', d.category);
  } catch (e) { console.log('  fail', row.sid, String(e.message).slice(0, 80)); }
  await sleep(150);
}
await b.close();
console.log('updated', done, '/', rows.length);
