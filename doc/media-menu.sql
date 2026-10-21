-- ----------------------------
-- QTools 媒体库 · 管理端菜单与权限（M3-A）
-- 目标库：qtools（MySQL 8.0）
--
-- 执行方式（必须显式指定 utf8mb4，本机 mysql 客户端默认字符集是 gbk，
-- 不带这个参数会把菜单名/备注按 GBK 写进数据字典，显示成乱码）：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source E:/111/project/QTools/QTools/doc/media-menu.sql"
--
-- 幂等说明：
--   1) 固定 menu_id：2001（菜单）+ 2002~2006（按钮权限），不依赖自增，重复执行 id 不变；
--   2) 全部使用 INSERT ... SELECT ... WHERE NOT EXISTS，重复执行只会补缺失行，不会产生重复；
--   3) 授权部分同样用 NOT EXISTS 去重，可安全重跑。
--
-- 父级选择：menu_id = 3「系统工具」（sys_menu 顶层目录，path='tool'），
--   因此前端路由为 /tool/media，组件 media/index。改成顶层只需把 parent_id 改成 0。
--
-- 权限标识（与后端 MediaAdminController 上的 @PreAuthorize 一一对应）：
--   media:admin:list    GET /videos,/albums,/photos,/detail,/people,/stats
--   media:admin:edit    PATCH /hidden/*, PATCH /album/*/person, POST /restore/*
--   media:admin:remove  DELETE /{kind}/{id}（软删）
--   media:admin:scan    POST /scan, GET /scan/latest,/scan/list,/scan/{jobId}
--   media:admin:query   预留（详情页/导出用），当前接口仍用 list 校验
-- ----------------------------

-- ----------------------------
-- 1、菜单：媒体库（menu_id = 2001，parent_id = 3 系统工具）
-- ----------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2001, '媒体库', 3, 10, 'media', 'media/index', '', '',
       1, 0, 'C', '0', '0', 'media:admin:list', 'picture',
       'admin', SYSDATE(), '', NULL, '媒体库管理菜单（父级：系统工具 menu_id=3）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2001);

-- ----------------------------
-- 2、按钮权限（menu_type = 'F'，menu_id = 2002~2006）
-- ----------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2002, '媒体查询', 2001, 1, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'media:admin:list', '#',
       'admin', SYSDATE(), '', NULL, '媒体库列表/详情/统计/人物下拉'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2002);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2003, '媒体编辑', 2001, 2, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'media:admin:edit', '#',
       'admin', SYSDATE(), '', NULL, '隐藏/取消隐藏、恢复、改人物归属'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2003);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2004, '媒体删除', 2001, 3, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'media:admin:remove', '#',
       'admin', SYSDATE(), '', NULL, '软删：移入 _媒体站/trash 并置 hidden=1（不真删）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2004);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2005, '重建索引', 2001, 4, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'media:admin:scan', '#',
       'admin', SYSDATE(), '', NULL, '异步执行 doc/media-import/import.js（同一时刻仅 1 个）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2005);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT 2006, '媒体详情', 2001, 5, '', NULL, '', '',
       1, 0, 'F', '0', '0', 'media:admin:query', '#',
       'admin', SYSDATE(), '', NULL, '预留：详情页/导出。当前 /detail 仍按 media:admin:list 校验'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 2006);

-- ----------------------------
-- 3、给角色 admin（role_id = 1）授权
--    注：若依超级管理员（user_id=1 且 role_key='admin'）在 SysPermissionService 里
--    直接拿 "*:*:*"，不查 sys_role_menu；这里仍落库，保证前端菜单树能渲染出来。
-- ----------------------------
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.menu_id IN (2001, 2002, 2003, 2004, 2005, 2006)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

-- ----------------------------
-- 4、执行后自检（可单独跑，只读）
-- ----------------------------
-- SELECT menu_id, menu_name, parent_id, menu_type, path, component, perms FROM sys_menu WHERE menu_id BETWEEN 2001 AND 2006 ORDER BY menu_id;
-- SELECT role_id, COUNT(*) FROM sys_role_menu WHERE menu_id BETWEEN 2001 AND 2006 GROUP BY role_id;
