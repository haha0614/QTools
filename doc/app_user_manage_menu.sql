-- ----------------------------
-- P5.81 · 管理端菜单改名：「用户审核」 -> 「用户管理」
-- 目标库：qtools（MySQL 8.0）
--
-- 执行方式（必须显式指定 utf8mb4）：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source E:/111/project/QTools/QTools/doc/app_user_manage_menu.sql"
--
-- 幂等说明：全部是「只改旧值」的定向 UPDATE —— 第 2 遍 WHERE 不再命中任何行，等于无操作。
--
-- 只改 menu_name / remark / update_*，**不动** path(2101='user')、component(app/user/index)、
-- perms(app:user:list / app:user:edit)：路由与权限标识保持不变，已有角色授权（sys_role_menu）也不动，
-- 所以前端路由 /admin-ui/appuser/user 与后端 @PreAuthorize 无需任何配套变更。
-- 前序依赖：先跑过 P5.79 的 app_user_menu.sql（2100~2103 四行菜单存在）。
-- ----------------------------

SET NAMES utf8mb4;

-- 1、菜单 2101：用户审核 -> 用户管理
UPDATE sys_menu
SET menu_name = '用户管理',
    remark    = '用户管理：全部账号 / 资料编辑 / 重置密码 / 封禁解封 / 删除 / 批量操作',
    update_by = 'admin',
    update_time = SYSDATE()
WHERE menu_id = 2101
  AND menu_name <> '用户管理';

-- 2、按钮权限 2103 的名称跟着改（它挂在 2101 下，原名「用户审核」会与菜单对不上）
UPDATE sys_menu
SET menu_name = '用户管理',
    remark    = '改资料 / 重置密码 / 封禁解封 / 删除 / 通过 / 拒绝 / 批量操作（app:user:edit）',
    update_by = 'admin',
    update_time = SYSDATE()
WHERE menu_id = 2103
  AND menu_name <> '用户管理';

-- 3、执行后自检（可单独跑，只读）
-- SELECT menu_id, menu_name, parent_id, menu_type, path, component, perms FROM sys_menu WHERE menu_id BETWEEN 2100 AND 2103 ORDER BY menu_id;
