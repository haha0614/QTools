-- ============================================================================
-- P5.43  相册去重 / 空壳相册清理 / photo_count·bytes 重算
-- 文件：doc/media_album_dedupe.sql
--
-- 性质：幂等（连跑两遍，第二遍每一处 ROW_COUNT 都是 0）；只动 media_album、
--       media_album_person 与 media_photo 的 (album_id,seq)，不删任何一张图
--       ——被删的相册全都是 0 行的空壳。
--
-- 用法：
--   1) 先备份（必做）：
--      tools\mysql\bin\mysqldump.exe -uroot -h127.0.0.1 -P3307 --single-transaction --set-gtid-purged=OFF ^
--        qtools media_album media_photo media_album_person ^
--        > doc\backup\p543_media_album_photo_before_YYYYMMDD.sql
--   2) 执行：
--      tools\mysql\bin\mysql.exe -uroot -p -h127.0.0.1 -P3307 --default-character-set=utf8mb4 ^
--        qtools < doc\media_album_dedupe.sql
--
-- 判据（一律按「有没有图」判，不看标题长相）：
--   真相册 = media_photo 里真有行（rc>0）的相册，且 photo_count 与行数一致；
--   幽灵行 = rc=0、但同一 dir_key 下的图已被同目录另一本相册整本认领
--            （scan-media 换 album-mode / 换标题推导方式后，旧行既不删也不重算）；
--   死行   = rc=0 且整个 dir_key 下再没有任何图（目录搬走 / 已空）。
--   「dir 在磁盘上还在不在」SQL 判断不了，这里用「同 dir_key 的图是否已被别的相册
--   认领」这个等价条件兜住；前提已实测：本库 14/14 目录的库内行数与磁盘图片数逐个相等。
--
-- 需要 media_album_person（doc/media_person_profile.sql 起就有）；老库没建时把 3 条
-- 标了 [album_person] 的语句注释掉即可，其余步骤不受影响。
-- ============================================================================
SET NAMES utf8mb4;
SET SESSION sql_mode = DEFAULT;
SET SESSION group_concat_max_len = 4194304;   -- 内容签名要拼整册 rel_path
SET @PARK_BASE := 1000000;                    -- 与 scan-media.js 的 PARK_BASE 一致

-- ---------------------------------------------------------------- 0 现状快照
SELECT '0-1 相册总数' AS step, COUNT(*) AS v FROM media_album;
SELECT '0-2 photo 行数' AS step, COUNT(*) AS v FROM media_photo;
SELECT '0-3 photo_count 求和（页头「共 M 张」的来源）' AS step, SUM(photo_count) AS v FROM media_album;
SELECT '0-4 photo_count 与实际行数不一致的相册' AS step, SUM(rc <> photo_count) AS v
  FROM (SELECT a.photo_count, (SELECT COUNT(*) FROM media_photo p WHERE p.album_id = a.id) rc
          FROM media_album a) t;

-- ============================================================ 1 判定（只读）
DROP TABLE IF EXISTS tmp_p543_rc;
CREATE TABLE tmp_p543_rc (
  id          varchar(16)  NOT NULL,
  dir_key     varchar(600) NULL,
  title       varchar(300) NOT NULL,
  person_id   bigint       NULL,
  photo_count int          NOT NULL,
  bytes       bigint       NOT NULL,
  rc          int          NOT NULL,          -- 全部行数（含停泊行）
  PRIMARY KEY (id), KEY k_dir (dir_key(191)), KEY k_rc (rc)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
INSERT INTO tmp_p543_rc (id, dir_key, title, person_id, photo_count, bytes, rc)
SELECT a.id, a.dir_key, a.title, a.person_id, a.photo_count, a.bytes,
       (SELECT COUNT(*) FROM media_photo p WHERE p.album_id = a.id)
  FROM media_album a;

-- 每个相册「未停泊」的行数/字节（seq >= PARK_BASE 是 missing 停泊行，不计入 photo_count 口径）
DROP TABLE IF EXISTS tmp_p543_cnt;
CREATE TABLE tmp_p543_cnt (album_id varchar(16) NOT NULL, c int NOT NULL, b bigint NOT NULL,
  PRIMARY KEY (album_id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
INSERT INTO tmp_p543_cnt (album_id, c, b)
SELECT album_id, COUNT(*), COALESCE(SUM(bytes), 0)
  FROM media_photo WHERE seq < @PARK_BASE GROUP BY album_id;

-- 1-a 内容签名：整册（未停泊）的 rel_path 排序拼接，签名相同即「同一套内容」
DROP TABLE IF EXISTS tmp_p543_sig;
CREATE TABLE tmp_p543_sig (album_id varchar(16) NOT NULL, sig char(32) NOT NULL, c int NOT NULL,
  PRIMARY KEY (album_id), KEY k_sig (sig)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
INSERT INTO tmp_p543_sig (album_id, sig, c)
SELECT album_id, MD5(GROUP_CONCAT(rel_path ORDER BY rel_path SEPARATOR '\n')), COUNT(*)
  FROM media_photo WHERE seq < @PARK_BASE GROUP BY album_id;

-- 1-b 真重复（有图 + 同内容 + 同 dir_key）：保留行 = 组内 id 最小者
--     安全条件：保留行行号紧凑（无 seq >= 行数 的未停泊行）且迁入后总量 < PARK_BASE
DROP TABLE IF EXISTS tmp_p543_dup;
CREATE TABLE tmp_p543_dup (drop_id varchar(16) NOT NULL, keep_id varchar(16) NOT NULL,
  PRIMARY KEY (drop_id), KEY k_keep (keep_id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
INSERT INTO tmp_p543_dup (drop_id, keep_id)
SELECT s.album_id, g.keep_id
  FROM tmp_p543_sig s
  JOIN (SELECT sig, MIN(album_id) AS keep_id, COUNT(*) AS n, SUM(c) AS total_c
          FROM tmp_p543_sig GROUP BY sig HAVING n > 1) g ON g.sig = s.sig
  JOIN tmp_p543_cnt kc ON kc.album_id = g.keep_id
 WHERE s.album_id <> g.keep_id
   AND g.total_c < @PARK_BASE
   AND NOT EXISTS (SELECT 1 FROM media_photo p
                    WHERE p.album_id = g.keep_id AND p.seq < @PARK_BASE AND p.seq >= kc.c);

DROP TABLE IF EXISTS tmp_p543_move;
CREATE TABLE tmp_p543_move (photo_id bigint NOT NULL, keep_id varchar(16) NOT NULL, seq_new int NOT NULL,
  PRIMARY KEY (photo_id), KEY k_keep (keep_id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
INSERT INTO tmp_p543_move (photo_id, keep_id, seq_new)
SELECT p.id, d.keep_id, kc.c + ROW_NUMBER() OVER (PARTITION BY d.keep_id ORDER BY p.album_id, p.seq, p.id) - 1
  FROM media_photo p
  JOIN tmp_p543_dup d ON d.drop_id = p.album_id
  JOIN tmp_p543_cnt kc ON kc.album_id = d.keep_id
 WHERE p.seq < @PARK_BASE;

-- 1-c 幽灵：rc=0 且同一 dir_key 下有有图的相册（bytes 相同优先、行数多优先、id 定序）
DROP TABLE IF EXISTS tmp_p543_pair;
CREATE TABLE tmp_p543_pair (ghost_id varchar(16) NOT NULL, keeper_id varchar(16) NOT NULL,
  PRIMARY KEY (ghost_id), KEY k_keep (keeper_id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
INSERT INTO tmp_p543_pair (ghost_id, keeper_id)
SELECT ghost_id, keeper_id FROM (
  SELECT g.id AS ghost_id, k.id AS keeper_id,
         ROW_NUMBER() OVER (PARTITION BY g.id ORDER BY (k.bytes = g.bytes) DESC, k.rc DESC, k.id) AS rn
    FROM tmp_p543_rc g
    JOIN tmp_p543_rc k ON k.dir_key <=> g.dir_key AND k.rc > 0 AND k.id <> g.id
   WHERE g.rc = 0) t
 WHERE rn = 1;

-- 1-d 死行：rc=0 且整个 dir_key 下没有任何图
DROP TABLE IF EXISTS tmp_p543_dead;
CREATE TABLE tmp_p543_dead (id varchar(16) NOT NULL, PRIMARY KEY (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
INSERT INTO tmp_p543_dead (id)
SELECT g.id FROM tmp_p543_rc g
 WHERE g.rc = 0
   AND NOT EXISTS (SELECT 1 FROM tmp_p543_rc k
                    WHERE k.dir_key <=> g.dir_key AND k.rc > 0 AND k.id <> g.id);

-- ---------------------------------------------------------------- 2 预览（只读）
SELECT '2-1 预览 内容完全相同的重复相册（drop -> keep）' AS step, COUNT(*) AS v FROM tmp_p543_dup;
SELECT d.drop_id, d.keep_id, a.title AS drop_title, b.title AS keep_title, a.dir_key
  FROM tmp_p543_dup d JOIN media_album a ON a.id = d.drop_id JOIN media_album b ON b.id = d.keep_id LIMIT 50;
SELECT '2-2 内容相同但序号/停泊位不允许自动合并（需人工）' AS step, COUNT(*) AS v
  FROM tmp_p543_sig s
  JOIN (SELECT sig, MIN(album_id) AS keep_id, COUNT(*) AS n FROM tmp_p543_sig GROUP BY sig HAVING n > 1) g ON g.sig = s.sig
 WHERE s.album_id <> g.keep_id AND s.album_id NOT IN (SELECT drop_id FROM tmp_p543_dup);
SELECT '2-3 预览 幽灵行（ghost -> 已接管其图的保留行）' AS step, COUNT(*) AS v FROM tmp_p543_pair;
SELECT g.id AS ghost_id, g.title AS ghost_title, g.photo_count AS ghost_pc, g.bytes AS ghost_bytes,
       k.id AS keeper_id, k.title AS keeper_title, k.rc AS keeper_rows, g.dir_key
  FROM tmp_p543_pair pr JOIN tmp_p543_rc g ON g.id = pr.ghost_id JOIN tmp_p543_rc k ON k.id = pr.keeper_id
 ORDER BY g.dir_key, g.id LIMIT 20;
SELECT '2-4 预览 目录级死行（该 dir_key 下已无任何图）' AS step, COUNT(*) AS v FROM tmp_p543_dead;
SELECT g.id, g.dir_key, g.title, g.photo_count FROM tmp_p543_rc g JOIN tmp_p543_dead dd ON dd.id = g.id LIMIT 50;

-- ============================================================ 3 变更（单事务）
START TRANSACTION;

-- 3-a 真重复合并：把要删那本的图片行迁到保留行，再删相册行
UPDATE media_photo p JOIN tmp_p543_move m ON m.photo_id = p.id
   SET p.seq = -(m.seq_new + 1)
 WHERE p.seq <> -(m.seq_new + 1);
SELECT '3-a 搬离待合并的图片行（临时负序号，避开 uk_photo_album_seq）' AS step, ROW_COUNT() AS v;
UPDATE media_photo p JOIN tmp_p543_move m ON m.photo_id = p.id
   SET p.album_id = m.keep_id, p.seq = m.seq_new
 WHERE p.album_id <> m.keep_id OR p.seq <> m.seq_new;
SELECT '3-a 图片行迁入保留相册' AS step, ROW_COUNT() AS v;
DELETE ap FROM media_album_person ap JOIN tmp_p543_dup d ON d.drop_id = ap.album_id;   -- [album_person]
SELECT '3-a 删除重复相册的人物关联行' AS step, ROW_COUNT() AS v;
DELETE FROM media_album WHERE id IN (SELECT drop_id FROM tmp_p543_dup);
SELECT '3-a 删除重复相册行' AS step, ROW_COUNT() AS v;

-- 3-b 幽灵：先交接元数据，再删
UPDATE media_album k
  JOIN tmp_p543_pair pr ON pr.keeper_id = k.id
  JOIN media_album g ON g.id = pr.ghost_id
   SET k.person_id = g.person_id
 WHERE k.person_id IS NULL AND g.person_id IS NOT NULL;
SELECT '3-b 把 person_id 交接给保留行' AS step, ROW_COUNT() AS v;
INSERT IGNORE INTO media_album_person (album_id, person_id)                           -- [album_person]
SELECT pr.keeper_id, ap.person_id
  FROM media_album_person ap JOIN tmp_p543_pair pr ON pr.ghost_id = ap.album_id;
SELECT '3-b 把人物关联行交接给保留行' AS step, ROW_COUNT() AS v;
-- 旧标题只在「这个 dir_key 去重后只剩一本相册」时接手：否则会把同目录 288 本册刷成同名
UPDATE media_album k
  JOIN tmp_p543_pair pr ON pr.keeper_id = k.id
  JOIN media_album g ON g.id = pr.ghost_id
   SET k.title = g.title
 WHERE g.title = SUBSTRING_INDEX(k.dir_key, '/', -1)
   AND k.title <> g.title
   AND NOT EXISTS (SELECT 1 FROM tmp_p543_rc x
                    WHERE x.dir_key <=> k.dir_key AND x.id <> k.id
                      AND x.id NOT IN (SELECT ghost_id FROM tmp_p543_pair));
SELECT '3-b 接手旧相册标题（仅目录级唯一时）' AS step, ROW_COUNT() AS v;
DELETE ap FROM media_album_person ap JOIN tmp_p543_pair pr ON pr.ghost_id = ap.album_id; -- [album_person]
SELECT '3-b 删除幽灵相册的人物关联行' AS step, ROW_COUNT() AS v;
DELETE FROM media_album WHERE id IN (SELECT ghost_id FROM tmp_p543_pair);
SELECT '3-b 删除幽灵相册行' AS step, ROW_COUNT() AS v;

-- 3-c 死行
DELETE ap FROM media_album_person ap JOIN tmp_p543_dead dd ON dd.id = ap.album_id;     -- [album_person]
SELECT '3-c 删除死行的人物关联行' AS step, ROW_COUNT() AS v;
DELETE FROM media_album WHERE id IN (SELECT id FROM tmp_p543_dead);
SELECT '3-c 删除死行' AS step, ROW_COUNT() AS v;

-- 3-d 重算 photo_count / bytes（以 media_photo 实际行为准，停泊行不计）
UPDATE media_album a
  LEFT JOIN (SELECT album_id, COUNT(*) c, COALESCE(SUM(bytes), 0) b
               FROM media_photo WHERE seq < @PARK_BASE GROUP BY album_id) t ON t.album_id = a.id
   SET a.photo_count = COALESCE(t.c, 0), a.bytes = COALESCE(t.b, 0)
 WHERE a.photo_count <> COALESCE(t.c, 0) OR a.bytes <> COALESCE(t.b, 0);
SELECT '3-d 重算 photo_count/bytes 的相册行' AS step, ROW_COUNT() AS v;

COMMIT;

-- ---------------------------------------------------------------- 4 自检
SELECT '4-1 相册总数' AS step, COUNT(*) AS v FROM media_album;
SELECT '4-2 photo 行数' AS step, COUNT(*) AS v FROM media_photo;
SELECT '4-3 photo_count 求和（应等于 4-2）' AS step, SUM(photo_count) AS v FROM media_album;
SELECT '4-4 残留 0 图相册' AS step, COUNT(*) AS v FROM (
  SELECT a.id FROM media_album a WHERE (SELECT COUNT(*) FROM media_photo p WHERE p.album_id = a.id) = 0) t;
SELECT '4-5 残留 photo_count 与实际行数不一致' AS step, COUNT(*) AS v FROM (
  SELECT a.photo_count, (SELECT COUNT(*) FROM media_photo p WHERE p.album_id = a.id) rc
    FROM media_album a) t WHERE rc <> photo_count;
SELECT '4-6 残留 内容完全相同的重复相册' AS step, COUNT(*) AS v FROM (
  SELECT sig FROM (SELECT album_id, MD5(GROUP_CONCAT(rel_path ORDER BY rel_path SEPARATOR '\n')) sig
                     FROM media_photo WHERE seq < @PARK_BASE GROUP BY album_id) s
   GROUP BY sig HAVING COUNT(*) > 1) t;
SELECT '4-7 残留 同一张图挂在两本相册' AS step, COUNT(*) AS v FROM (
  SELECT rel_path FROM media_photo GROUP BY rel_path HAVING COUNT(*) > 1) t;
SELECT '4-8 残留 孤儿图片行' AS step, COUNT(*) AS v
  FROM media_photo p LEFT JOIN media_album a ON a.id = p.album_id WHERE a.id IS NULL;
SELECT '4-9 残留 孤儿人物关联行' AS step, COUNT(*) AS v
  FROM media_album_person ap LEFT JOIN media_album a ON a.id = ap.album_id WHERE a.id IS NULL;

-- ---------------------------------------------------------------- 5 清理辅助表
DROP TABLE IF EXISTS tmp_p543_move;
DROP TABLE IF EXISTS tmp_p543_dup;
DROP TABLE IF EXISTS tmp_p543_pair;
DROP TABLE IF EXISTS tmp_p543_dead;
DROP TABLE IF EXISTS tmp_p543_cnt;
DROP TABLE IF EXISTS tmp_p543_sig;
DROP TABLE IF EXISTS tmp_p543_rc;
