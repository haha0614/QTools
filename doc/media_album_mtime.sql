-- ============================================================================
-- media_album.mtime 幂等回填（P5.59）
-- ----------------------------------------------------------------------------
-- 背景：真实库 media_album.mtime 158 行全为 NULL。后端算人物「最近日期」时
--       people[].mtime = max(相册 mtime 按 person_id 聚合, 视频 mtime 按人名聚合)，
--       相册侧全 NULL 就等于这一侧贡献 0（MediaIndexServiceImpl 人物节点构造）。
--
-- 前提：**SQL 读不到磁盘**。所以 mtime 有两条来源——
--   ① 本文件：从库里能推导的列回填（近似值）；
--   ② scan-media.js --backfill-album-mtime：按 media_album.dir 定位磁盘目录、
--      stat 后写入目录真实 mtime（权威值，可反复跑，会覆盖本文件的近似值）。
--   正确顺序：先跑本文件（mtime 立刻非空，后端立刻有数据），再跑 ② 修正。
--
-- 源列自适应（重要）：本库 media_photo **没有 mtime 列**（show columns 确认，只有
--       last_seen），media_video 才有 mtime。所以这里按 information_schema 选源列：
--         media_photo.mtime  存在   -> 用它（文件真实 mtime，最准）
--         media_photo.mtime  不存在 -> 退到 media_photo.last_seen
--                                    （= 上一次扫描时间；对「最近日期」是不会早于
--                                      真实目录 mtime 的近似值，随后由 ② 覆盖）
--       两列都没有（极端老库）-> 整段跳过，只打印提示，不改任何行。
--
-- 幂等：所有 UPDATE 都带 WHERE a.mtime IS NULL，连跑第二遍命中 0 行、ROW_COUNT()=0；
--       已写入的值不会被本文件二次覆盖（磁盘真值由 ② 写）。
--
-- 用法（PowerShell 里先 $env:MYSQL_PWD='<root 密码>'）：
--   mysql -h127.0.0.1 -P3307 -uroot --default-character-set=utf8mb4 -t -D qtools
--          < QTools/doc/media_album_mtime.sql
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------- 0) 选源列
SET @src := (SELECT column_name
               FROM information_schema.columns
              WHERE table_schema = DATABASE()
                AND table_name = 'media_photo'
                AND column_name IN ('mtime', 'last_seen')
              ORDER BY FIELD(column_name, 'mtime', 'last_seen')
              LIMIT 1);

SELECT IFNULL(@src, '(无可用源列)') AS `源列`
     , CASE WHEN @src = 'mtime' THEN '文件真实 mtime'
            WHEN @src = 'last_seen' THEN '近似：上一次扫描时间（随后跑 scan-media.js --backfill-album-mtime 覆盖）'
            ELSE '跳过（无源列）' END AS `说明`;

-- ---------------------------------------------------------------- 1) 预览（只读，不写库）
SET @preview := IF(@src IS NULL,
  'SELECT ''跳过：media_photo 既无 mtime 也无 last_seen'' AS `预览`',
  CONCAT('SELECT COUNT(*) AS `命中行`, MIN(g.mx) AS `最小`, MAX(g.mx) AS `最大` ',
         '  FROM media_album a ',
         '  JOIN (SELECT album_id, MAX(`', @src, '`) AS mx FROM media_photo GROUP BY album_id) g ',
         '    ON g.album_id = a.id ',
         ' WHERE a.mtime IS NULL AND g.mx IS NOT NULL'));
PREPARE st FROM @preview;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------- 2) 回填（只动 mtime IS NULL 的行）
SET @upd := IF(@src IS NULL,
  'SET @noop := 1',
  CONCAT('UPDATE media_album a ',
         '  JOIN (SELECT album_id, MAX(`', @src, '`) AS mx FROM media_photo GROUP BY album_id) g ',
         '    ON g.album_id = a.id ',
         '   SET a.mtime = g.mx ',
         ' WHERE a.mtime IS NULL AND g.mx IS NOT NULL'));
PREPARE st FROM @upd;
EXECUTE st;
SELECT ROW_COUNT() AS `本次更新行`;
DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------- 3) 自检
-- 判据：`仍为空` = 0（仍有的只可能是册内 0 图的无源相册）
SELECT COUNT(*) AS `相册总数`
     , SUM(mtime IS NOT NULL) AS `有mtime`
     , SUM(mtime IS NULL) AS `仍为空`
     , DATE_FORMAT(MIN(mtime), '%Y-%m-%d %H:%i:%s') AS `最早`
     , DATE_FORMAT(MAX(mtime), '%Y-%m-%d %H:%i:%s') AS `最晚`
  FROM media_album;

-- 仍为空的行逐条列出（正常应为空集；有行说明该册册内 0 图，只能等 ② 用磁盘 mtime 补）
SELECT a.id, a.title, a.dir,
       (SELECT COUNT(*) FROM media_photo p WHERE p.album_id = a.id) AS photos
  FROM media_album a
 WHERE a.mtime IS NULL
 ORDER BY a.id;
