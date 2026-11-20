#!/usr/bin/env node
/**
 * fix-moved.mjs —— 把「文件被挪走、库里还指着旧路径」的视频行修回来（默认只预览，--apply 才写库）
 *
 * 背景：audit-missing.mjs 报 17 条源文件不在，实测它们全被挪到 D:/Audio and Video/剪辑视频/0-第一人称开车/，
 * 而 media_video 的 missing 列后端并不读，行还当正常片展示 → 点开就是 404。
 * 正确做法不是删行：按内容哈希（oshash，同 scan-media.js:586）确认是同一份文件后，改 rel_path/root_id。
 *
 * 用法：
 *   node fix-moved.mjs           # 只打印将要改的行
 *   node fix-moved.mjs --apply   # 写库
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { rows, sql } from '../lib/db.mjs'

const APPLY = process.argv.includes('--apply')
const ROOTS = ['D:/Audio and Video/yellow', 'D:/Audio and Video/剪辑视频']

// oshash：md5(十进制 size + 首/中/尾各 64KB) 前 16 位。与 scan-media.js:586 的 oshashFile 同算法，
// 改动任一处必须同步另一处（--hash-bytes 默认 65536）。
function readRange(fd, h, start, len, bufSize) {
  const buf = Buffer.allocUnsafe(Math.min(bufSize, len))
  let pos = start
  let left = len
  while (left > 0) {
    const n = fs.readSync(fd, buf, 0, Math.min(buf.length, left), pos)
    if (n <= 0) break
    h.update(buf.subarray(0, n))
    pos += n
    left -= n
  }
}
function oshashFile(abs, win = 65536) {
  const fd = fs.openSync(abs, 'r')
  try {
    const size = fs.fstatSync(fd).size
    const h = crypto.createHash('md5')
    h.update(String(size), 'utf8')
    if (size <= win * 3) readRange(fd, h, 0, size, 1024 * 1024)
    else {
      readRange(fd, h, 0, win, win)
      readRange(fd, h, Math.floor((size - win) / 2), win, win)
      readRange(fd, h, size - win, win, win)
    }
    return h.digest('hex').slice(0, 16)
  } finally {
    fs.closeSync(fd)
  }
}

const rootRows = rows('select id, path from media_root').map((r) => ({ id: Number(r[0]), path: r[1] }))
const rootIds = new Map(rootRows.map((r) => [r.path, r.id]))
const rootById = new Map(rootRows.map((r) => [r.id, r.path]))
const absOf = (rel, rootId) => {
  if (path.isAbsolute(rel)) return rel
  const own = rootById.get(rootId)
  for (const base of (own ? [own] : []).concat(ROOTS)) if (fs.existsSync(path.join(base, rel))) return path.join(base, rel)
  return own ? path.join(own, rel) : path.join(ROOTS[0], rel)
}
const rows_ = rows('select id, rel_path, content_hash, root_id from media_video')
  .map(([id, rel, hash, rootId]) => ({ id, rel, hash: hash || '', rootId: Number(rootId) || 0 }))

// 1) 找源文件真没了、且只有 basename 能对上的行
const byName = new Map()
for (const base of ROOTS) {
  const stack = [base]
  while (stack.length) {
    const dir = stack.pop()
    let es
    try { es = fs.readdirSync(dir, { withFileTypes: true }) } catch { continue }
    for (const e of es) {
      const p = path.join(dir, e.name)
      if (e.isDirectory()) { stack.push(p); continue }
      const arr = byName.get(e.name) || []
      arr.push(p)
      byName.set(e.name, arr)
    }
  }
}

const fixes = []
for (const row of rows_) {
  if (fs.existsSync(absOf(row.rel, row.rootId))) continue
  const cands = byName.get(path.basename(row.rel)) || []
  let hit = ''
  for (const c of cands) {
    if (!row.hash) continue
    if (oshashFile(c) === row.hash) { hit = c; break }
  }
  if (!hit) { console.log('SKIP  ' + row.id + ' 找不到同内容文件：' + row.rel); continue }
  const hitFwd = hit.replace(/\\/g, '/')
  const root = ROOTS.find((r) => hitFwd.startsWith(r))
  if (!root) { console.log('SKIP  ' + row.id + ' 命中文件不在已知根下：' + hit); continue }
  const rootId = rootIds.get(root) || row.rootId
  const st = fs.statSync(hit)
  fixes.push({ id: row.id, from: row.rel, to: hitFwd.slice(root.length + 1), rootId, size: st.size, mtime: Math.floor(st.mtimeMs) })
}

console.log('待修 ' + fixes.length + ' 条' + (APPLY ? '' : '（预览，加 --apply 写库）'))
for (const f of fixes) console.log('  ' + f.id + '  ' + f.from + '  ->  [' + f.rootId + '] ' + f.to)
if (!APPLY || !fixes.length) process.exit(0)

const esc = (s) => "'" + String(s).replace(/\\/g, '\\\\').replace(/'/g, "''") + "'"
sql('start transaction;')
for (const f of fixes) {
  sql('update media_video set rel_path=' + esc(f.to) + ', root_id=' + f.rootId + ', size=' + f.size + ', mtime=from_unixtime(' + Math.floor(f.mtime / 1000) + '), missing=0 where id=' + esc(f.id) + ';')
}
sql('commit;')
console.log('已写库 ' + fixes.length + ' 条')
