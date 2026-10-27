-- ----------------------------
-- P5.89 · 用户端两档角色（user 普通用户 / owner 站长）
-- 目标库：qtools（MySQL 8.0）
--
-- 执行方式（本机 mysql 客户端默认字符集是 gbk，必须显式指定 utf8mb4，否则列注释会写进乱码）：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source E:/111/project/QTools/QTools/doc/app_user_role.sql"
--
-- 幂等说明：DDL 全部先查 information_schema 再 PREPARE 执行；owner 的播种只在
--   「库里当前一个 owner 都没有」时执行一次（第二遍不会改任何行的 role）。
--
-- 为什么是两档而不是若依那种角色表：
--   用户端与后台 sys_user / sys_role 是两套互不承认的身份（见 AppJwtService 的说明）。
--   P5.89 只需要一个区分「站长 / 普通用户」的开关——站长拿站端 full 权限（无视
--   media.userEditing 三档），普通用户仍按三档走。给两档建一张关联表只会多一次 join。
-- ----------------------------

SET NAMES utf8mb4;

-- ----------------------------
-- 1、role：账号角色（user 普通用户 / owner 站长）
--    存量数据全部落 'user'；owner 由下面第 3 步播种（也可事后在管理端「用户管理」行内切换）
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'role') = 0,
    'ALTER TABLE app_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT ''user'' COMMENT ''角色：user 普通用户 / owner 站长（owner 拿站端 full 权限）'' AFTER status',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 2、角色索引（管理端按角色筛选 / 统计 owner 数量时走它；owner 顶多一两个，索引很便宜）
-- ----------------------------
SET @qt_sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND INDEX_NAME = 'idx_app_user_role') = 0,
    'ALTER TABLE app_user ADD KEY idx_app_user_role (role)',
    'DO 0');
PREPARE qt_stmt FROM @qt_sql;
EXECUTE qt_stmt;
DEALLOCATE PREPARE qt_stmt;

-- ----------------------------
-- 3、播种站长（只做一次）
--    默认：把「现有账号里 id 最小的那个」置为 owner（首个注册的账号通常就是站主）。
--    想指定别的账号：把下面 @qt_owner_username 改成那个用户名再执行本文件（本文件可重复执行）。
--    幂等：只要库里已经有一个 owner，第 3 步整体跳过，不覆盖管理端后来的调整。
-- ----------------------------
SET @qt_owner_username := NULL;

SET @qt_owner_id := IFNULL(
    (SELECT u.id FROM app_user u WHERE u.username = @qt_owner_username LIMIT 1),
    (SELECT MIN(u.id) FROM app_user u));

SET @qt_has_owner := (SELECT COUNT(*) FROM app_user WHERE role = 'owner');

UPDATE app_user SET role = 'owner', updated_at = now()
 WHERE id = @qt_owner_id AND @qt_has_owner = 0;

-- ----------------------------
-- 4、执行后自检：打印当前 owner（含「无账号 / 已有 owner」两种跳过形态）
-- ----------------------------
SELECT id, username, nickname, role, updated_at AS role_updated_at
  FROM app_user WHERE role = 'owner' ORDER BY id;

SELECT IF((SELECT COUNT(*) FROM app_user WHERE role = 'owner') = 0,
          IF(@qt_owner_id IS NULL,
             '本库 app_user 无账号，未播种 owner（注册首个账号后重跑本文件即可）',
             CONCAT('播种失败：目标 id=', @qt_owner_id, ' 不存在')),
          CONCAT('当前 owner：id=', (SELECT MIN(id) FROM app_user WHERE role = 'owner'),
                 ' username=', (SELECT username FROM app_user WHERE role = 'owner' ORDER BY id LIMIT 1),
                 '（本次播种目标 id=', IFNULL(@qt_owner_id, 'NULL'), '，已存在 owner 时跳过播种）')) AS qt_result;

-- SELECT role, COUNT(*) FROM app_user GROUP BY role;
