-- =====================================================================
-- 「未归类」相册容器 → 「素人」，并删除该容器行（P5.62）
--
-- 用户需求原话：「把未归类的内容合并到素人下」
--
-- 背景（合并前取证，2026-10-26）：
--   media_person 113「未归类」= 相册归档容器（不是真人）：
--     media_album 2 行（a158 images 73 张 / a159 写真 45 张，共 118 张），
--     media_album_person 2 行，media_video_actor 0 行，use_count 0。
--   media_person 125「素人」= 真业务兜底人物：159 部视频（use_count=159），0 本相册 / 0 张照片。
--   media_person 126「E2E演员」= 端到端测试假人物，库里无任何关联（本脚本执行前已不在库内）。
--
-- 本文件做五件事（幂等：连跑两遍，第二遍 0 行变更）：
--   (1) media_album.person_id 113 → 125（单选镜像列）
--   (2) media_album_person 同步换绑：INSERT IGNORE + DELETE 两段式，
--       避开 PK(album_id, person_id) 撞键（素人若已绑同一本相册，忽略而不是报错）
--   (3) media_video_actor 若也有 113 的行，同样两段式换绑
--       （uk_actor_video_person(video_id, person_id) 撞键风险同 (2)），name 同步改成「素人」
--   (4) 重算 use_count：口径与 MediaPersonMapper#recomputePersonUseCount 完全一致
--       （= media_video_actor 行数；相册数/照片数不落 use_count，由
--         MediaIndexServiceImpl#buildIndex 现算成 people[].count / people[].photos）
--   (5) 确认三张表都不再引用该 id 后 DELETE media_person 的容器行
--
-- 合并后预期（自检段会打出来）：
--   素人：相册 2 本 / 照片 118 张 / 视频 159 部（use_count 不变）
--   未归类：人物行 0 行，遗留引用 0 处；人物总数 15 → 14（本机执行时表内已是 14：
--   测试人物「E2E演员」(126) 在本次操作前已被清理，详见交付报告）
--
-- 执行（必须显式 --default-character-set=utf8mb4：本机 mysql 客户端默认 gbk，不带会把中文人物名写坏）：
--   tools/mysql/bin/mysql.exe -uroot -p -P3307 -h127.0.0.1 --default-character-set=utf8mb4 qtools -t \
--     < QTools/doc/media_person_merge_uncategorized.sql
--
-- 备份：doc/backup/media_person_merge_p562_before_20261026.sql
--   （mysqldump media_person / media_album / media_video_actor / media_album_person）
--
-- 已知限制：
--   1) 按**名字**解析 id（'未归类' / '素人'），不写死 113/125；同名多行取最小 id。
--   2) 「素人」不存在时第 0 步断言直接报错中断（不会静默 0 行）。
--   3) 本来就没有归属人物的相册（person_id IS NULL，当前 a157 / a160）不受影响：
--      用户端仍按 PhotosPage 的 UNGROUPED 分组成「未归类」展示 —— 那是前端分组名，不是人物行。
--   4) 后端 MediaPersonServiceImpl.PLACEHOLDER_NAMES 仍含「未归类」：删行后该过滤空转，
--      保留以兼容其它库/旧快照，不构成错误。
--   5) 防新产生「未归类」的源头改动在 QTools/doc/media-import/import.js（本脚本只管存量）。
-- =====================================================================

SET NAMES utf8mb4;

-- 断言用临时表：插入 NULL 会触发 1048（Column cannot be null），mysql 客户端随即中断本脚本。
-- 临时表必须先建（CREATE 会隐式提交，不能放进事务里）。
DROP TEMPORARY TABLE IF EXISTS p562_assert;
CREATE TEMPORARY TABLE p562_assert (v TINYINT NOT NULL);

-- ---------------------------------------------------------------------
-- 0) 解析 id + 断言「素人」存在
-- ---------------------------------------------------------------------
SET @src_id := (SELECT id FROM media_person WHERE name = '未归类' ORDER BY id LIMIT 1);
SET @dst_id := (SELECT id FROM media_person WHERE name = '素人'   ORDER BY id LIMIT 1);

SELECT 'STEP-0 解析' AS check_item, @src_id AS src_id, @dst_id AS dst_id;

INSERT INTO p562_assert (v) SELECT @dst_id;
INSERT INTO p562_assert (v) SELECT IF(@src_id IS NULL OR @src_id <> @dst_id, 1, NULL);

START TRANSACTION;

-- ---------------------------------------------------------------------
-- 1) 预览命中行（先看清要动哪些行，再改）
-- ---------------------------------------------------------------------
SELECT 'STEP-1 预览 待迁移相册' AS check_item, COUNT(*) AS albums,
       COALESCE(SUM(photo_count), 0) AS photos
  FROM media_album WHERE person_id = @src_id;
SELECT id, title, photo_count FROM media_album WHERE person_id = @src_id ORDER BY id;
SELECT 'STEP-1 预览 album_person 行' AS check_item, COUNT(*) AS rows_ap
  FROM media_album_person WHERE person_id = @src_id;
SELECT 'STEP-1 预览 video_actor 行' AS check_item, COUNT(*) AS rows_va
  FROM media_video_actor WHERE person_id = @src_id;

-- ---------------------------------------------------------------------
-- 2) media_album.person_id 换绑（单选镜像列）
-- ---------------------------------------------------------------------
UPDATE media_album SET person_id = @dst_id
 WHERE person_id = @src_id AND @src_id IS NOT NULL AND @dst_id IS NOT NULL AND @src_id <> @dst_id;
SELECT 'STEP-2 media_album 换绑行数' AS check_item, ROW_COUNT() AS rows_changed;

-- ---------------------------------------------------------------------
-- 3) media_album_person 换绑：先 INSERT IGNORE 补绑，再删旧绑
--    （先插后删，任何时刻都不会出现「相册没有人物」的空窗）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO media_album_person (album_id, person_id, create_time)
SELECT ap.album_id, @dst_id, ap.create_time
  FROM media_album_person ap
 WHERE ap.person_id = @src_id AND @src_id IS NOT NULL AND @dst_id IS NOT NULL AND @src_id <> @dst_id;
SELECT 'STEP-3 album_person 新增行数' AS check_item, ROW_COUNT() AS rows_inserted;

DELETE FROM media_album_person WHERE person_id = @src_id AND @src_id IS NOT NULL;
SELECT 'STEP-3 album_person 删除行数' AS check_item, ROW_COUNT() AS rows_deleted;

-- ---------------------------------------------------------------------
-- 4) media_video_actor 若也有该 id 的行，同样两段式换绑（当前 0 行，留作通用处理）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO media_video_actor (video_id, name, person_id, role)
SELECT va.video_id, (SELECT p.name FROM media_person p WHERE p.id = @dst_id), @dst_id, va.role
  FROM media_video_actor va
 WHERE va.person_id = @src_id AND @src_id IS NOT NULL AND @dst_id IS NOT NULL AND @src_id <> @dst_id;
SELECT 'STEP-4 video_actor 新增行数' AS check_item, ROW_COUNT() AS rows_inserted;

DELETE FROM media_video_actor WHERE person_id = @src_id AND @src_id IS NOT NULL;
SELECT 'STEP-4 video_actor 删除行数' AS check_item, ROW_COUNT() AS rows_deleted;

-- ---------------------------------------------------------------------
-- 5) 重算 use_count（只数 media_video_actor 行，与 Java 侧同一口径）
-- ---------------------------------------------------------------------
UPDATE media_person p
   SET p.use_count = (SELECT COUNT(*) FROM media_video_actor a WHERE a.person_id = p.id)
 WHERE p.id IN (@dst_id, @src_id);
SELECT 'STEP-5 use_count 重算行数' AS check_item, ROW_COUNT() AS rows_changed;

-- ---------------------------------------------------------------------
-- 6) 素人 note 追加合并记录（带 P5.62 标记，第二遍 0 行变更）
-- ---------------------------------------------------------------------
UPDATE media_person
   SET note = CONCAT(COALESCE(note, ''),
        '（P5.62：原「未归类」相册容器的 2 本相册 a158 images 73 张 / a159 写真 45 张，'
        '共 118 张照片，已并入本行，容器行已删除；见 doc/media_person_merge_uncategorized.sql。）')
 WHERE id = @dst_id AND COALESCE(note, '') NOT LIKE '%P5.62%';
SELECT 'STEP-6 note 追加行数' AS check_item, ROW_COUNT() AS rows_changed;

-- ---------------------------------------------------------------------
-- 7) 断言「三张表都不再引用」后才删容器行（仍被引用 -> 断言失败，脚本中断）
-- ---------------------------------------------------------------------
INSERT INTO p562_assert (v)
SELECT IF((SELECT COUNT(*) FROM media_album        WHERE person_id = @src_id)
        + (SELECT COUNT(*) FROM media_album_person WHERE person_id = @src_id)
        + (SELECT COUNT(*) FROM media_video_actor  WHERE person_id = @src_id) = 0, 1, NULL);

DELETE FROM media_person WHERE id = @src_id AND @src_id IS NOT NULL AND @src_id <> @dst_id;
SELECT 'STEP-7 删除容器人物行数' AS check_item, ROW_COUNT() AS rows_deleted;

-- ---------------------------------------------------------------------
-- 8) 末尾自检
-- ---------------------------------------------------------------------
SELECT 'POST 素人统计' AS check_item,
       (SELECT COUNT(*)                            FROM media_album WHERE person_id = @dst_id) AS albums,
       (SELECT COALESCE(SUM(photo_count), 0)       FROM media_album WHERE person_id = @dst_id) AS photos,
       (SELECT use_count                           FROM media_person WHERE id = @dst_id)       AS use_count,
       (SELECT COUNT(*)                            FROM media_video_actor WHERE person_id = @dst_id) AS video_rows;

SELECT 'POST 遗留引用（应全为 0）' AS check_item,
       (SELECT COUNT(*) FROM media_person WHERE name = '未归类') AS person_rows_left,
       (SELECT COUNT(*) FROM media_album a JOIN media_person p ON p.id = a.person_id
         WHERE p.name = '未归类')                                            AS album_left,
       (SELECT COUNT(*) FROM media_album_person ap JOIN media_person p ON p.id = ap.person_id
         WHERE p.name = '未归类')                                            AS album_person_left,
       (SELECT COUNT(*) FROM media_video_actor va JOIN media_person p ON p.id = va.person_id
         WHERE p.name = '未归类')                                            AS video_actor_left;

SELECT 'POST 孤儿引用（应全为 0）' AS check_item,
       (SELECT COUNT(*) FROM media_album_person ap LEFT JOIN media_person p ON p.id = ap.person_id
         WHERE p.id IS NULL)                                                 AS orphan_album_person,
       (SELECT COUNT(*) FROM media_album a LEFT JOIN media_person p ON p.id = a.person_id
         WHERE a.person_id IS NOT NULL AND p.id IS NULL)                     AS orphan_album,
       (SELECT COUNT(*) FROM media_video_actor va LEFT JOIN media_person p ON p.id = va.person_id
         WHERE va.person_id IS NOT NULL AND p.id IS NULL)                    AS orphan_video_actor;

SELECT 'POST 人物总数' AS check_item, COUNT(*) AS persons FROM media_person;

-- 测试假人物（如「E2E演员」）：本脚本不按名字删测试行 —— 那可能误删真实同名人物。
-- 这里只做只读报数：本机执行时它已被其它会话清理，故期望 0。
SELECT 'POST 测试人物残留（只读报数，应 0）' AS check_item,
       (SELECT COUNT(*) FROM media_person WHERE name = 'E2E演员') AS test_persons_left;

COMMIT;
