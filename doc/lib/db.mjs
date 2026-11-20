/**
 * MySQL 客户端封装（QTools/doc 下所有脚本共用）
 *
 * 为什么不引 npm 的 mysql2：这些脚本跑在便携环境，MySQL 客户端本来就在 tools/mysql 里，
 * 用 CLI 不增加依赖（见 AGENTS.md「复用优先 / 自研举证」）。
 *
 * 约定（改之前先看，别各写一套）：
 *   - 密码走 MYSQL_PWD 环境变量，不出现在命令行：既不进进程列表，也不再刷
 *     "[Warning] Using a password on the command line interface can be insecure"
 *   - 密码来源顺序：MYSQL_PWD → QTOOLS_MYSQL_PWD → QTools/qtools-admin/src/main/resources/secret.yml
 *   - 一律 --default-character-set=utf8mb4 -N -B（无表头、制表符分隔）
 *   - 一律先 set session sql_mode=''：归档条目只有「月 日」，严格模式下写生成列 published_ts 会报 1411
 *   - 客户端路径：QTOOLS_MYSQL → MYSQL → 本机便携布局；库 qtools @ 127.0.0.1:3307
 *
 * 用法：
 *   import { sql, rows, sqlAsync, execStdin } from '../lib/db.mjs';
 *   const list = rows('select id, title from tool_web_post limit 3');   // [['1','标题'], ...]
 *   const n = sql('select count(*) from tool_web_post').trim();        // 同步，返回 stdout 原文
 *   const out = await sqlAsync('select 1', { timeout: 120000 });        // 异步，失败会 reject
 *   execStdin('update ...; update ...;');                              // 大批量写：走 stdin
 */
import { execFile, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

export const MYSQL = process.env.QTOOLS_MYSQL || process.env.MYSQL || 'D:/project/QTools-All/tools/mysql/bin/mysql.exe';
export const DB = { host: '127.0.0.1', port: 3307, user: 'root', database: 'qtools' };

const ARGS = ['-h' + DB.host, '-P' + DB.port, '-u' + DB.user, '--protocol=TCP', '--default-character-set=utf8mb4', '-N', '-B'];

let cachedPwd = null;
export function dbPassword() {
  if (cachedPwd !== null) return cachedPwd;
  const env = process.env.MYSQL_PWD || process.env.QTOOLS_MYSQL_PWD;
  if (env) { cachedPwd = env; return cachedPwd; }
  const f = path.resolve(HERE, '../../qtools-admin/src/main/resources/secret.yml');
  try {
    const m = fs.readFileSync(f, 'utf8').match(/^\s*password\s*:\s*["']?([^"'\r\n]+)/m);
    if (m) { cachedPwd = m[1].trim(); return cachedPwd; }
  } catch (e) { /* 落到下面统一报错 */ }
  throw new Error('找不到 MySQL 密码：设置 MYSQL_PWD，或检查 ' + f);
}

const opts = (o = {}) => ({
  encoding: 'utf8',
  env: { ...process.env, MYSQL_PWD: dbPassword() },
  maxBuffer: o.maxBuffer || 1 << 28,
  timeout: o.timeout,
});

const relaxed = (query) => (query == null ? query : "set session sql_mode=''; " + query);

/** 同步执行：返回 stdout 原文（未 trim） */
export function sql(query, o = {}) {
  return execFileSync(MYSQL, [...ARGS, '-e', relaxed(query), DB.database], opts(o));
}

/** 同步执行并按行/制表符切成二维数组 */
export function rows(query, o) {
  return sql(query, o).split(/\r?\n/).filter(Boolean).map((l) => l.split('\t'));
}

/** 异步执行：返回 stdout 原文（未 trim），出错 reject */
export function sqlAsync(query, o = {}) {
  return new Promise((resolve, reject) => {
    execFile(MYSQL, [...ARGS, '-e', relaxed(query), DB.database], opts(o),
      (err, stdout) => (err ? reject(err) : resolve(String(stdout || ''))));
  });
}

/** 大批量写：整段 SQL 从 stdin 进去（不占命令行长度） */
export function execStdin(text, o = {}) {
  return new Promise((resolve, reject) => {
    const child = execFile(MYSQL, [...ARGS, DB.database], opts(o),
      (err, stdout, stderr) => (err ? reject(new Error(String(stderr || err.message))) : resolve(String(stdout || ''))));
    child.stdin.end("set session sql_mode='';\n" + text);
  });
}
