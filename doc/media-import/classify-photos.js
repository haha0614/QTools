#!/usr/bin/env node
'use strict';
/**
 * 图片内容自动分级（P6.24）—— 由后端 MediaPhotoClassifyService 调起，不给人手跑。
 *
 * 输入：--photos=<json 文件，[{id, hash, seq}]>  --thumbs=<thumbs 根目录>
 * 输出（stdout，一行一个 JSON，后端逐行解析）：
 *   RESULT {"id":113391,"seq":0,"rating":"restricted","porn":0.89,"sexy":0.07,"neutral":0.02}
 *   ERROR  {"id":113392,"message":"thumb missing"}
 *   DONE   {"checked":30,"failed":0,"ms":21000}
 *
 * 判定口径（与《内容分级与可见性》一致，只判画面硬线，不猜"擦边"）：
 *   Porn+Hentai >= 0.60                 → restricted（露点/性行为）
 *   Sexy >= 0.45 或 Porn >= 0.30        → guidance  （穿着暴露、性暗示）
 *   Neutral >= 0.55                     → general
 *   其余（两个高分咬得很紧 / 分数都不高） → review    （不写库，交人工）
 *
 * 为什么不让后端自己跑模型：nsfwjs 是 tfjs 生态，Node 侧最省事；模型只加载一次，
 * 一批 30 张约 20 秒。脚本本身不写库 —— 写库由后端调用既有的 /rating/batch 完成。
 */
const fs = require('fs');
const path = require('path');

function arg(name, def) {
  const hit = process.argv.find((a) => a.startsWith('--' + name + '='));
  return hit ? hit.slice(name.length + 3) : def;
}

const PORN_T = Number(arg('porn-threshold', '0.6'));
const SEXY_T = Number(arg('sexy-threshold', '0.45'));

function rate(porn, sexy, neutral) {
  // P6.33：能判的三档直接给（都会显式写库）；判不了的进 review，交人工，不硬塞一档。
  if (porn >= PORN_T) return 'restricted';
  if (sexy >= SEXY_T || porn >= 0.3) return 'guidance';
  if (neutral >= 0.55) return 'general';
  return 'review';
}

async function main() {
  const photosFile = arg('photos');
  const thumbs = arg('thumbs');
  if (!photosFile || !fs.existsSync(photosFile)) {
    console.error('missing --photos');
    process.exit(2);
  }
  if (!thumbs) {
    console.error('missing --thumbs');
    process.exit(2);
  }
  const list = JSON.parse(fs.readFileSync(photosFile, 'utf8'));
  const started = Date.now();

  const sharp = require('sharp');
  const tf = require('@tensorflow/tfjs');
  const nsfwjs = require('nsfwjs');
  const model = await nsfwjs.load();

  let checked = 0;
  let failed = 0;
  for (const item of list) {
    try {
      const file = path.join(thumbs, 'p', String(item.hash) + '.jpg');
      if (!fs.existsSync(file)) throw new Error('thumb missing');
      const { data, info } = await sharp(file)
        .resize(224, 224, { fit: 'fill' })
        .removeAlpha()
        .raw()
        .toBuffer({ resolveWithObject: true });
      const input = tf.tensor3d(new Uint8Array(data), [info.height, info.width, 3]);
      const preds = await model.classify(input, 5);
      input.dispose();
      const p = {};
      preds.forEach((x) => { p[x.className] = x.probability; });
      const porn = (p.Porn || 0) + (p.Hentai || 0);
      const sexy = p.Sexy || 0;
      const neutral = p.Neutral || 0;
      checked += 1;
      process.stdout.write('RESULT ' + JSON.stringify({
        id: item.id, seq: item.seq, rating: rate(porn, sexy, neutral),
        porn: Number(porn.toFixed(4)), sexy: Number(sexy.toFixed(4)), neutral: Number(neutral.toFixed(4))
      }) + '\n');
    } catch (e) {
      failed += 1;
      process.stdout.write('ERROR ' + JSON.stringify({ id: item.id, message: String(e && e.message || e) }) + '\n');
    }
  }
  process.stdout.write('DONE ' + JSON.stringify({ checked, failed, ms: Date.now() - started }) + '\n');
}

main().catch((e) => {
  console.error('FATAL ' + (e && e.stack || e));
  process.exit(1);
});


