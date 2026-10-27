-- ----------------------------
-- P5.79 · 用户端注册审核（app_user 状态机）
-- 目标库：qtools（MySQL 8.0）
--
-- 执行方式（必须显式指定 utf8mb4，本机 mysql 客户端默认字符集是 gbk，
-- 不带这个参数会把列注释按 GBK 写进数据字典，显示成乱码）：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source E:/111/project/QTools/QTools/doc/app_user_review.sql"
--
-- 幂等说明：DDL 一律先查 information_schema 再用 PREPARE 执行，DML 一律是「只改旧值域」的
-- 定向 UPDATE —— 重复执行 = 无操作（第 2 遍不会再改任何一行）。
--
-- ⚠️ 存量语义迁移（关键）：
--   app_schema.sql 里 status 原本是 CHAR(1)：'0' 正常 / '1' 停用。
--   本次把它升级成 VARCHAR(16) 审核状态：pending / approved / rejected。
--   顺序必须是「先扩类型、再映射值」—— 在 CHAR(1) 上直接写 'approved' 会报
--   ERROR 1406 Data too long for column 'status'（P5.79 实测踩到过）。
--   映射规则：'0' -> approved、'1' -> rejected。
--   为什么 '1' 映射成 rejected 而不是 approved：'1' 在旧语义里就是「禁止登录」，
--   映射成 rejected 才能保持「迁移前后能否登录」完全一致（老用户不会被锁在门外，
--   被停用的老账号也不会因为迁移而被放进来）。
--   其它未知值 / NULL -> approved（默认放行，绝不因为迁移把存量用户锁在门外）。
-- ----------------------------

SET NAMES utf8mb4;

-- ----------------------------
-- 1、status：CHAR(1)(0/1) -> VARCHAR(16)(pending/approved/rejected)
--    ⚠️ 先扩类型（存量 '0'/'1' 会原样保留），再在下一步映射成新值域
-- ----------------------------
SET @qt_status_type := (SELECT COLUMN_TYPE FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'status');

SET @qt_sql := IF(@qt_status_type IS NOT NULL AND @qt_status_type <> 'varchar(16)',
    'ALTER TABLE app_user MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT ''approved'' COMMENT ''审核状态：pending 待审核 / approved 已通过 / rejected 未通过（存量 0/1 已迁移）''',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 2、旧值域映射（定向 UPDATE，重复执行不再命中任何行）
-- ----------------------------
UPDATE app_user SET status = 'approved' WHERE status = '0';
UPDATE app_user SET status = 'rejected' WHERE status = '1';

-- 兜底：任何不在新值域里的状态（含 NULL）一律 approved
UPDATE app_user SET status = 'approved'
WHERE status IS NULL OR status NOT IN ('pending', 'approved', 'rejected');

-- ----------------------------
-- 3、审核备注 / 审核时间 / 审核人
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'review_note') = 0,
    'ALTER TABLE app_user ADD COLUMN review_note VARCHAR(255) NULL COMMENT ''审核备注（拒绝原因等，管理员填写）'' AFTER status',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'reviewed_at') = 0,
    'ALTER TABLE app_user ADD COLUMN reviewed_at DATETIME NULL COMMENT ''最近一次审核时间'' AFTER review_note',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'reviewed_by') = 0,
    'ALTER TABLE app_user ADD COLUMN reviewed_by BIGINT NULL COMMENT ''审核人（sys_user.user_id）'' AFTER reviewed_at',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 4、状态索引（管理端按状态筛选 / 待审核角标）
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND INDEX_NAME = 'idx_app_user_status') = 0,
    'ALTER TABLE app_user ADD KEY idx_app_user_status (status)',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 5、执行后自检（可单独跑，只读）
-- ----------------------------
-- SELECT column_name, column_type, is_nullable, column_default FROM information_schema.columns
--   WHERE table_schema = DATABASE() AND table_name = 'app_user' ORDER BY ordinal_position;
-- SELECT status, COUNT(*) FROM app_user GROUP BY status;
