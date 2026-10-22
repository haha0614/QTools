-- ----------------------------
-- QTools 工具管理 · 管理端菜单与按钮权限
-- 目标库：qtools（MySQL 8.0）
--
-- 执行方式（必须显式指定 utf8mb4，否则中文菜单名会按 GBK 写入而乱码）：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source E:/111/project/QTools/QTools/doc/qtool-menu.sql"
--
-- 幂等说明：
--   1) 固定 menu_id：2007（菜单）+ 2008~2012（按钮权限），不依赖自增，重复执行 id 不变；
--   2) 全部使用 INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS，重复执行只补缺失行；
--   3) 授权同样用 NOT EXISTS 去重，可安全重跑；
--   4) 若数据库中已手工建过 menu_id=2007（本机现状：顶层菜单、path=qtool、component=qtool/index），
--      本条会被跳过，不会覆盖你手工调整过的内容。
--
-- 位置说明：
--   本机现状把「工具管理」放在**顶层**（parent_id = 0，路由 /qtool）；
--   若希望与媒体库一样收进「系统工具」（menu_id = 3，路由 /tool/qtool），
--   把下方 parent_id 改成 3 即可（首次执行前改，或删掉 2007 后重跑）。
--
-- 权限标识（与后端 ToolAdminController 的 @PreAuthorize 一一对应）：
--   qtool:admin:list    GET  /api/qtool/info/list
--   qtool:admin:query   GET  /api/qtool/info/{id}
--   qtool:admin:add     POST /api/qtool/info
--   qtool:admin:edit    PUT  /api/qtool/info
--   qtool:admin:remove  DELETE /api/qtool/info/{ids}
--   qtool:admin:export  POST /api/qtool/info/export
-- ----------------------------

-- ----------------------------
-- 1、菜单：工具管理（menu_id = 2007，parent_id = 0 顶层，order_num = 11）
-- ----------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2007, '工具管理', 0, 11, 'qtool', 'qtool/index', '', '',
       1, 0, 'C', '0', '0', 'qtool:admin:list', 'list',
       'admin', SYSDATE(), '', NULL, '工具站工具信息维护（qtool-tool 模块）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2007);

-- ----------------------------
-- 2、按钮权限（menu_type = 'F'，parent_id = 2007，menu_id = 2008~2012）
-- ----------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2008, '工具查询', 2007, 1, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'qtool:admin:query', '#',
       'admin', SYSDATE(), '', NULL, '详情查看'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2008);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2009, '工具新增', 2007, 2, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'qtool:admin:add', '#',
       'admin', SYSDATE(), '', NULL, '新增工具'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2009);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2010, '工具修改', 2007, 3, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'qtool:admin:edit', '#',
       'admin', SYSDATE(), '', NULL, '修改工具（含上下架 status）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2010);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2011, '工具删除', 2007, 4, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'qtool:admin:remove', '#',
       'admin', SYSDATE(), '', NULL, '删除工具'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2011);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2012, '工具导出', 2007, 5, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'qtool:admin:export', '#',
       'admin', SYSDATE(), '', NULL, '导出工具信息 Excel'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2012);

-- ----------------------------
-- 3、给角色 admin（role_id = 1）授权
--    注：若依超级管理员（role_key='admin'）在 SysPermissionService 里直接拿 "*:*:*"，
--    不查 sys_role_menu；这里仍落库，保证前端菜单树与自定义角色可正常渲染。
-- ----------------------------
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.menu_id IN (2007, 2008, 2009, 2010, 2011, 2012)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

-- ----------------------------
-- 4、执行后自检（只读，可单独跑）
-- ----------------------------
-- SELECT menu_id, menu_name, parent_id, menu_type, path, component, perms FROM sys_menu WHERE menu_id BETWEEN 2007 AND 2012 ORDER BY menu_id;
-- SELECT role_id, COUNT(*) FROM sys_role_menu WHERE menu_id BETWEEN 2007 AND 2012 GROUP BY role_id;
