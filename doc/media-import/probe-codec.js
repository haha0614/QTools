#!/usr/bin/env node
/**
 * probe-codec.js —— 用 ffprobe 把 media_video 的 vcodec / browser_ok 修正成**真实值**
 *
 * 背景：scan-media.js 的 browser_ok 是按扩展名猜的（.mp4/.webm -> 1），且明确不跑 ffprobe。
 * 于是 HEVC / MPEG-4 Part 2 的 mp4 全被标成"浏览器能播"，前端那条"该片为 HEVC/异常编码"提示
 * 永远不会出现 —— 用户只能看到黑屏或没反应。
 *
 * 本脚本只做一件事：逐行 ffprobe 出 codec/profile/pix_fmt，按下面这张白名单重算 browser_ok。
 *
 * 判据（2026-11-19）：
 *   codec  ∈ {h264, vp8, vp9, av1}
 *   pix_fmt 是 8-bit 4:2:0（yuv420p / yuvj420p / nv12）
 *   h264 的 profile 不是 High 10 / 4:2:2 / 4:4:4（浏览器只吃 8-bit 4:2:0 的 baseline/main/high）
 *   其它一律 0
 *
 * 用法：
 *   node probe-codec.js --dry-run                 # 只打印会怎么改，不写库
 *   node probe-codec.js                           # 真写
 *   可选：--mysql=<mysql.exe> --ffprobe=<ffprobe.exe> --root=<媒体根> --limit=<n>
 *   库连接默认 127.0.0.1:3307 root/dqq1107. qtools，可用 MS_DB_HOST/MS_DB_PORT/MS_DB_USER/MS_DB_PASS/MS_DB_NAME 覆盖
 */
const { spawnSync } = require('child_process')
const path = require('path')
const fs = require('fs')

function arg(name, def) {
  const hit = process.argv.find((a) => a.startsWith('--' + name + '='))
  return hit ? hit.slice(name.length + 3) : def
}
const flag = (name) => process.argv.includes('--' + name)

// 默认路径按脚本位置推（本机路径写死是红线，见 AGENTS.md）
const HOME = path.resolve(__dirname, '../../..')
const MYSQL = arg('mysql', process.env.MS_MYSQL || path.join(HOME, 'tools/mysql/bin/mysql.exe'))
const FFPROBE = arg('ffprobe', process.env.MS_FFPROBE || path.join(HOME, 'data/media/runtime/ffprobe.exe'))
const ROOT = arg('root', process.env.MS_ROOT || path.join(HOME, 'data/media'))
const LIMIT = Number(arg('limit', '0')) || 0
const DRY = flag('dry-run')

const DB = {
  host: process.env.MS_DB_HOST || '127.0.0.1',
  port: process.env.MS_DB_PORT || '3307',
  user: process.env.MS_DB_USER || 'root',
  pass: process.env.MS_DB_PASS || 'dqq1107.',
  name: process.env.MS_DB_NAME || 'qtools'
}

const OK_CODECS = new Set(['h264', 'vp8', 'vp9', 'av1'])
const OK_PIXFMT = new Set(['yuv420p', 'yuvj420p', 'nv12'])
const BAD_H264_PROFILE = /High 10|4:2:2|4:4:4|10|12/i

function sqlStr(s) {
  return s == null ? 'NULL' : "'" + String(s).replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'"
}

function runMysql(sql) {
  const r = spawnSync(MYSQL, [
    '-h' + DB.host, '-P' + DB.port, '-u' + DB.user, '--default-character-set=utf8mb4',
    '-B', '-N', DB.name
  ], { input: sql, env: { ...process.env, MYSQL_PWD: DB.pass }, encoding: 'utf8' })
  if (r.status !== 0) throw new Error('mysql 失败: ' + (r.stderr || '').slice(0, 400))
  return r.stdout
}

function probe(abs) {
  const r = spawnSync(FFPROBE, [
    '-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream=codec_name,profile,pix_fmt', '-of', 'default=nw=1', abs
  ], { encoding: 'utf8' })
  if (r.status !== 0) return null
  const out = {}
  for (const line of String(r.stdout || '').split(/\r?\n/)) {
    const i = line.indexOf('=')
    if (i > 0) out[line.slice(0, i)] = line.slice(i + 1).trim()
  }
  return out.codec_name ? out : null
}

function decide(info) {
  if (!info) return 0
  const codec = String(info.codec_name || '').toLowerCase()
  if (!OK_CODECS.has(codec)) return 0
  const pix = String(info.pix_fmt || '').toLowerCase()
  if (!OK_PIXFMT.has(pix)) return 0
  if (codec === 'h264' && BAD_H264_PROFILE.test(String(info.profile || ''))) return 0
  return 1
}

// 媒体根映射：一个库里可能有多个根（主库 E:/111/视频、剪辑视频 E:/视频…），
// 只按默认根拼路径会有一大批文件"找不到"（实测 251 条里 64 条踩这个坑）
const roots = runMysql("SELECT id, path FROM media_root WHERE kind='root';")
  .trim().split(/\r?\n/).filter(Boolean).map((line) => {
    const [id, dir] = line.split('\t')
    return { id: Number(id), dir: dir }
  })
const rootById = new Map(roots.map((r) => [r.id, r.dir]))
const allRoots = roots.map((r) => r.dir).filter(Boolean)

const rowsSql = "SET SESSION sql_mode='';\nSELECT id, rel_path, IFNULL(vcodec,''), IFNULL(browser_ok,-1), IFNULL(root_id,0) FROM media_video ORDER BY id;"
const rows = runMysql(rowsSql).trim().split(/\r?\n/).filter(Boolean).map((line) => {
  const [id, rel, vcodec, ok, rootId] = line.split('\t')
  // media_video.id 是 hash 字符串（不是自增数字），别 Number()
  return { id: id, rel, vcodec: vcodec || '', ok: Number(ok), rootId: Number(rootId) || 0 }
})

/** 按 root_id 解析绝对路径；解析不到再依次试其它根（历史行 root_id 可能是 NULL/0） */
function resolveAbs(row) {
  if (path.isAbsolute(row.rel)) return row.rel
  const own = rootById.get(row.rootId)
  const cands = (own ? [own] : []).concat(allRoots, [ROOT]).filter(Boolean)
  for (const base of cands) {
    const abs = path.join(base, row.rel)
    if (fs.existsSync(abs)) return abs
  }
  return path.join(own || ROOT, row.rel)
}

const list = LIMIT > 0 ? rows.slice(0, LIMIT) : rows
console.log('待探测 ' + list.length + ' 条（共 ' + rows.length + '）')

const updates = []
const missingRows = []
const stat = { changed: 0, missing: 0, probed: 0 }
for (const row of list) {
  const abs = resolveAbs(row)
  if (!fs.existsSync(abs)) { stat.missing += 1; continue }
  const info = probe(abs)
  if (!info) { stat.missing += 1; continue }
  stat.probed += 1
  const ok = decide(info)
  const codec = String(info.codec_name || '').toLowerCase()
  if (ok !== row.ok || codec !== row.vcodec) {
    stat.changed += 1
    updates.push('UPDATE media_video SET vcodec=' + sqlStr(codec) + ', browser_ok=' + ok + ' WHERE id=' + sqlStr(row.id) + ';')
    console.log('  #' + row.id + ' ' + row.vcodec + '/' + row.ok + ' -> ' + codec + '/' + ok + '  ' + row.rel.slice(-48))
  }
}

console.log('探测 ' + stat.probed + ' 条，需更新 ' + stat.changed + ' 条，文件缺失/探测失败 ' + stat.missing + ' 条')
if (DRY) { console.log('--dry-run：不写库'); process.exit(0) }
if (!updates.length) { console.log('没有需要更新的行'); process.exit(0) }

runMysql("SET SESSION sql_mode='';\nSTART TRANSACTION;\n" + updates.join('\n') + '\nCOMMIT;')
console.log('已写库：' + updates.length + ' 条')
