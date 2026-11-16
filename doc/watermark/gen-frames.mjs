
import fs from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const run = promisify(execFile);
const MYSQL = 'E:/111/project/QTools/tools/mysql/bin/mysql.exe';
const FF = 'E:/111/project/QTools/data/media/runtime/ffmpeg.exe';
const OUT_ROOT = 'E:/111/project/QTools/data/media/web';
const LIMIT = Number(process.env.LIMIT || 3);

const sql = 'select id, source_id, video_url from tool_web_post where length(video_url) > 10 order by id desc limit ' + LIMIT;
const rows = (await run(MYSQL, ['-h127.0.0.1','-P3307','-uroot','-pdqq1107.','-N','-B','-e', sql, 'qtools'])).stdout
  .trim().split(/\r?\n/).filter(Boolean).map(l => { const [id, sid, url] = l.split('\t'); return { id, sid, url }; });

fs.mkdirSync(OUT_ROOT, { recursive: true });
// 抽帧落点（百分比）：片头/中段/片尾各几张，够列表封面 + 详情预览条
const RATIOS = [0.05, 0.2, 0.4, 0.6, 0.8, 0.95];

for (const r of rows) {
  const dir = path.join(OUT_ROOT, r.sid);
  fs.mkdirSync(dir, { recursive: true });
  let ok = 0;
  for (let i = 0; i < RATIOS.length; i++) {
    const out = path.join(dir, 'f' + i + '.jpg');
    if (fs.existsSync(out) && fs.statSync(out).size > 1000) { ok++; continue; }
    try {
      await run(FF, ['-hide_banner','-nostdin','-v','error','-ss', String(RATIOS[i] * 60), '-i', r.url, '-frames:v','1','-vf','scale=480:-2', '-y', out], { timeout: 60000 });
      if (fs.existsSync(out) && fs.statSync(out).size > 1000) ok++;
    } catch (e) { /* 该落点失败不影响其它 */ }
  }
  const cover = '/web/' + r.sid + '/f2.jpg';
  const upd = "update tool_web_post set cover='" + cover + "' where id=" + r.id + ';';
  await run(MYSQL, ['-h127.0.0.1','-P3307','-uroot','-pdqq1107.','qtools'], { input: upd });
  console.log('frames', r.sid, ok + '/' + RATIOS.length, '->', cover);
}
