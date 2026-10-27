-- =====================================================================
-- P5.78 · 多库根扩展：库根「未登录是否可见」
--
-- 目标表：media_root（由 doc/media_portability.sql 建立；索引补强见 doc/media_multiroot.sql）
-- 新增列：guest_visible TINYINT(1) NOT NULL DEFAULT 1
--   1 = 未登录可见（默认；与本次改动之前的行为完全一致）
--   0 = 仅登录可见：未登录访客在索引里看不到该根下的视频/相册/图片/人物，
--       直接访问其资源（按 id 或 hash）一律 404（不用 403，避免暴露存在性）
--
-- 为什么默认 1：老库执行本迁移后行为零变化；要收紧的根由管理端「库根管理」页逐行开关。
--
-- 幂等：先查 information_schema.columns，列已存在则执行 DO 0（空操作），可反复执行。
--       表不存在时同样跳过 —— 说明还没跑 media_portability.sql，末尾自检会给出提示。
--
-- 执行：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_root_guest_visible.sql"
--   （本机 MySQL 在 3307，加 -h127.0.0.1 -P3307 -p…）
--
-- 生效面（后端统一拦截，见 REPORT）：
--   GET /api/media/index、/api/media/video/{id}、/api/media/video/{id}/meta、
--   /api/media/album/{id}、/api/media/{thumb|preview|sprite|frame|file|stream|sub}
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1) 加列（information_schema 守卫：表/列都在才什么都不做）
-- ---------------------------------------------------------------------
SET @has_table = (SELECT COUNT(*) FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'media_root');
SET @has_col   = (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'media_root'
                     AND column_name = 'guest_visible');

SET @ddl = IF(@has_table = 1 AND @has_col = 0,
  'ALTER TABLE `media_root` ADD COLUMN `guest_visible` TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''1=未登录可见，0=仅登录可见'' AFTER `kind`',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 2) 自检（只读）：应看到 guest_visible 一行，default 为 1
--    未建表时这里会回 0 行，配合上面 @has_table=0 即可判断
-- ---------------------------------------------------------------------
SELECT table_name, column_name, column_type, is_nullable, column_default, column_comment
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_root' AND column_name = 'guest_visible';

-- ---------------------------------------------------------------------
-- 3) 现有库根一览（只读，供决定哪些根要收紧成「仅登录可见」）
-- ---------------------------------------------------------------------
SELECT r.id, r.name, r.path, r.kind, r.guest_visible,
       (SELECT COUNT(*) FROM media_video v WHERE v.root_id = r.id) AS videos,
       (SELECT COUNT(*) FROM media_photo p WHERE p.root_id = r.id) AS photos
  FROM media_root r
 ORDER BY r.id;
