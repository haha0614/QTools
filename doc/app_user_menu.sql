-- ----------------------------
-- P5.79 · 管理端「用户端 → 用户审核」菜单与权限
-- 目标库：qtools（MySQL 8.0）
--
-- 执行方式（必须显式指定 utf8mb4）：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source E:/111/project/QTools/QTools/doc/app_user_menu.sql"
--
-- 幂等说明：固定 menu_id，全部 INSERT ... SELECT ... WHERE NOT EXISTS，可安全重跑。
--
-- 权限标识（与后端 AppUserAdminController 上的 @PreAuthorize 一一对应）：
--   app:user:list   GET  /api/app/admin/users、/api/app/admin/users/{id}、/api/app/admin/users/stats
--   app:user:edit   POST /api/app/admin/users/{id}/approve、/{id}/reject、
--                   PUT /api/app/admin/users/{id}/status、POST /api/app/admin/users/batch/approve
--
-- 父级：新建顶层目录「用户端」（menu_id = 2100，parent_id = 0，path = 'appuser'），
--       子菜单「用户审核」menu_id = 2101（component = app/user/index）。
-- ----------------------------

SET NAMES utf8mb4;

-- 1、顶层目录：用户端
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2100, '用户端', 0, 7, 'appuser', NULL, '', '',
       1, 0, 'M', '0', '0', '', 'peoples',
       'admin', SYSDATE(), '', NULL, '用户端账号体系（独立于 sys_user）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2100);

-- 2、菜单：用户审核（列表 + 通过/拒绝/撤销 + 批量通过）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2101, '用户审核', 2100, 1, 'user', 'app/user/index', '', '',
       1, 0, 'C', '0', '0', 'app:user:list', 'user',
       'admin', SYSDATE(), '', NULL, '注册审核：待审核列表 / 通过 / 拒绝 / 撤销'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2101);

-- 3、按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2102, '用户查询', 2101, 1, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'app:user:list', '#',
       'admin', SYSDATE(), '', NULL, '用户端账号分页列表 / 详情 / 状态统计'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2102);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2103, '用户审核', 2101, 2, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'app:user:edit', '#',
       'admin', SYSDATE(), '', NULL, '通过 / 拒绝 / 撤销为待审核 / 批量通过'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2103);

-- 4、给角色 admin（role_id = 1）授权
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.menu_id IN (2100, 2101, 2102, 2103)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

-- 5、执行后自检（可单独跑，只读）
-- SELECT menu_id, menu_name, parent_id, menu_type, path, component, perms FROM sys_menu WHERE menu_id BETWEEN 2100 AND 2103 ORDER BY menu_id;
