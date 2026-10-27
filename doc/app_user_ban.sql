-- ----------------------------
-- P5.81 · 用户管理（app_user 封禁维度 + 写操作时间戳）
-- 目标库：qtools（MySQL 8.0）
--
-- 执行方式（必须显式指定 utf8mb4，本机 mysql 客户端默认字符集是 gbk，
-- 不带这个参数会把列注释按 GBK 写进数据字典，显示成乱码）：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source E:/111/project/QTools/QTools/doc/app_user_ban.sql"
--
-- 幂等说明：全部 DDL 一律先查 information_schema 再用 PREPARE 执行，重复执行 = 无操作（第 2 遍不报错、不改结构）。
-- 执行顺序依赖：必须先跑完 P5.79 的 app_user_review.sql（status 已升级为审核状态）。本文件只追加列，不碰 status。
--
-- ⚠️ 为什么封禁要独立字段而不是复用 status：
--   P5.79 起 status 已被审核态占用（pending / approved / rejected），语义是「能不能进站的前置条件」；
--   封禁是管理员的**处置动作**，与审核结论正交（可以「审核通过但被封禁」，解封后应回到原审核态）。
--   两者合并会丢信息：封禁再解封后无法还原「原本是 pending 还是 approved」。
-- ----------------------------

SET NAMES utf8mb4;

-- ----------------------------
-- 1、banned：是否封禁（0 正常 / 1 已封禁），存量数据全部落 0
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'banned') = 0,
    'ALTER TABLE app_user ADD COLUMN banned TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否封禁：0 正常 / 1 已封禁（独立于审核状态 status）'' AFTER status',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 2、banned_reason：封禁原因（回显到登录 / 接口拒绝文案）
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'banned_reason') = 0,
    'ALTER TABLE app_user ADD COLUMN banned_reason VARCHAR(255) NULL COMMENT ''封禁原因（管理员填写，用户被拒时可见）'' AFTER banned',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 3、banned_at：封禁时间（解封时清空）
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'banned_at') = 0,
    'ALTER TABLE app_user ADD COLUMN banned_at DATETIME NULL COMMENT ''封禁时间（解封后清空）'' AFTER banned_reason',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 4、updated_at：最近一次管理端写操作时间（改资料 / 重置密码 / 封禁解封 / 审核）
--    原先表里只有 created_at / last_login_at，没有任何「谁在什么时候改过」的落点
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'updated_at') = 0,
    'ALTER TABLE app_user ADD COLUMN updated_at DATETIME NULL COMMENT ''最近一次管理端写操作时间（资料/密码/封禁/审核）'' AFTER last_login_at',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 5、封禁索引（统计卡片 / 列表按「已封禁」筛选）
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND INDEX_NAME = 'idx_app_user_banned') = 0,
    'ALTER TABLE app_user ADD KEY idx_app_user_banned (banned)',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 6、执行后自检（可单独跑，只读）
-- ----------------------------
-- SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns
--   WHERE table_schema = DATABASE() AND table_name = 'app_user' ORDER BY ordinal_position;
-- SELECT banned, COUNT(*) FROM app_user GROUP BY banned;
