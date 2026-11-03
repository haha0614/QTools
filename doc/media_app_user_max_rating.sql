-- ============================================================================
-- P6.26  按用户限定可见内容：app_user.max_rating
-- 文件：doc/media_app_user_max_rating.sql
--
-- 需求：登录用户不再"一律全量"。每个站端账号有一个可见上限：
--   general    只能看大众级
--   guidance   能看大众 + 家长指导（默认）
--   restricted 能看全部
-- owner 角色不受此列限制（站长始终全量）。
--
-- 语义（与 P6.25 的图级优先叠加）：
--   访客     → 按 media_rating_policy（当前只有 general 对未登录可见）
--   登录用户 → 隐藏所有比 max_rating 更严的档；图片按「自身档 ?? 相册档」的生效档判
--   owner    → 不隐藏
--
-- 性质：幂等；只加一列，不动既有数据（存量账号一律 guidance）。
--
-- 回滚：
--   ALTER TABLE app_user DROP COLUMN max_rating;
-- ============================================================================
SET NAMES utf8mb4;

SET @has := (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'app_user' AND column_name = 'max_rating');
SET @sql := IF(@has = 0,
  'ALTER TABLE app_user ADD COLUMN max_rating VARCHAR(16) NOT NULL DEFAULT ''guidance'' COMMENT ''可见内容上限：general/guidance/restricted；owner 不受限'' AFTER role',
  'SELECT ''max_rating 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT '0-1 账号数与默认档位' AS step, COUNT(*) AS users,
       SUM(max_rating = 'guidance') AS guidance_users
  FROM app_user;
