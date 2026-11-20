#!/usr/bin/env node
/**
 * audit-missing.mjs —— 媒体站 404 体检（只读，不改库）
 *
 * 查两类 404：
 *   1) /api/media/file?p=… 404 —— media_video.rel_path 指向的源文件不在了
 *   2) /api/media/thumb/v/<id> 404 —— poster_path 指向的封面不在了（没有 poster 的行单列）
 * 用法：node audit-missing.mjs   → 打印统计，明细写 data/logs/media-missing.txt
 */
import fs from 'node:fs'
import path from 'node:path'
import { rows } from '../lib/db.mjs'

const HOME = path.resolve(new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'), '../../..')
const MEDIA = path.join(HOME, 'data/media')
const OUT = path.join(HOME, 'data/logs/media-missing.txt')

const roots = rows("select id, path from media_root").map((r) => ({ id: Number(r[0]), path: r[1] }))
const rootById = new Map(roots.map((r) => [r.id, r.path]))
const list = rows("select id, rel_path, poster_path, poster_file, root_id from media_video")
  .map(([id, rel, poster, posterFile, rootId]) => ({ id, rel, poster: poster || '', posterFile: posterFile || '', rootId: Number(rootId) || 0 }))

function resolveAbs(row) {
  if (!row.rel) return ''
  if (path.isAbsolute(row.rel)) return row.rel
  const own = rootById.get(row.rootId)
  for (const base of (own ? [own] : []).concat(roots.map((r) => r.path))) {
    const abs = path.join(base, row.rel)
    if (fs.existsSync(abs)) return abs
  }
  return own ? path.join(own, row.rel) : ''
}

const stat = { total: list.length, fileOk: 0, fileMissing: 0, posterOk: 0, posterMissing: 0, posterAbsent: 0 }
const bad = []
for (const row of list) {
  const abs = resolveAbs(row)
  const fileOk = !!abs && fs.existsSync(abs)
  fileOk ? stat.fileOk++ : stat.fileMissing++
  const posterAbs = row.poster ? path.join(MEDIA, row.poster) : ''
  let posterState = 'absent'
  if (posterAbs) posterState = fs.existsSync(posterAbs) ? 'ok' : 'missing'
  if (posterState === 'ok') stat.posterOk++
  else if (posterState === 'missing') stat.posterMissing++
  else stat.posterAbsent++
  if (!fileOk || posterState === 'missing') {
    bad.push([!fileOk ? 'FILE' : '', posterState === 'missing' ? 'POSTER' : '', row.id, row.rel, row.poster || '-'].filter(Boolean).join('\t'))
  }
}

fs.writeFileSync(OUT, bad.join('\n') + (bad.length ? '\n' : ''), 'utf8')
console.log('视频 ' + stat.total + ' 条：源文件在 ' + stat.fileOk + ' / 缺失 ' + stat.fileMissing)
console.log('封面：正常 ' + stat.posterOk + ' / 指向的文件没了 ' + stat.posterMissing + ' / 本来就没封面 ' + stat.posterAbsent)
console.log('明细 ' + bad.length + ' 行 → ' + OUT)
