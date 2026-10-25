-- ----------------------------
-- QTools 管理端 · 工作室管理菜单（sys_menu 落库，P5.4）
-- 目标库：qtools（MySQL 8.0，端口 3307）
--
-- 执行方式（务必显式指定 utf8mb4；本机 mysql 客户端默认 gbk，不带会把菜单名写坏）：
--   tools/mysql/bin/mysql.exe -uroot -p -P3307 -h127.0.0.1 --default-character-set=utf8mb4 qtools \
--     -e "source E:/111/project/QTools/doc/media_studio_menu.sql"
--
-- 幂等：全脚本不写死 menu_id（自动增长），唯一性判据是 component='media/studio/index'。
--   所有 INSERT 都是 INSERT ... SELECT ... WHERE NOT EXISTS，第二遍执行插入 0 行；
--   授权同样 NOT EXISTS 去重，可安全重跑。
--
-- 为什么挂在顶层（parent_id=0），而不是挂到「媒体库」下面：
--   本页的 URL 被两处前端写死成 /media-studios ——
--     a) QTools-M/src/views/media/person/index.vue 工具栏「工作室管理」按钮 push('/media-studios')；
--     b) QTools-C 站端不发这个入口，后台菜单是唯一入口，sidebar 直接指到此页。
--   若依 buildMenus 对「顶层 C + is_frame=1」的处理（SysMenuServiceImpl.java:192-204 + getRouterPath:474-477）
--   是「外层 path='/' + 子路由 path=menu.path」，所以 path='media-studios' 正好落在 /media-studios；
--   一旦挂到某个目录（M）下面，路径就变成 /<目录>/media-studios，前端那两处 push 全部 404。
--   因此父级固定顶层；脚本仍然**动态解析**「媒体库」(@media_id) —— 它只用来继承授权面与定位同类菜单，
--   从不硬编码 menu_id。
--
-- perms 取值依据（与该页实际权限一致）：
--   QTools-M/src/router/index.js 里这条页面的动态路由是 permissions: ['media:admin:edit']，
--   QTools-M/src/views/media/studio/StudioView.vue 的「新建 / 编辑 / 删除 / 保存」四个按钮也都是
--   v-hasPermi="['media:admin:edit']"；后端 MediaAdminController 的 POST/PUT/DELETE /admin/studios 同样
--   @PreAuthorize("@ss.hasPermi('media:admin:edit')")。只读用户进这页没有意义（列表打开也只能看），
--   所以菜单 perms 取 media:admin:edit。
--
-- 影响面：不 UPDATE 任何既有行；只新增 1 条 sys_menu + N 条 sys_role_menu。
--
-- 已知限制：
--   1) 判重键是 component：以后若把菜单手动挪到别的父级下，本脚本不会再动它（幂等跳过）；
--      需要手工 UPDATE 改父级。
--   2) 授权面 = 「持有媒体库菜单(@media_id)的角色」+ 超管。当前只有 role_id=1（超级管理员）持有媒体库，
--      所以这次实际只会给超管加一行；若将来把「媒体库」授权给只读角色，它会连 media:admin:edit 一起
--      进权限集 —— 到那时要么把本菜单 perms 改成 media:admin:list，要么不要把它授权给该角色。
--   3) 顶层顺延序号 = MAX(order_num)+1（当前 13，排在「人物管理」12 之后）。想让它在别的位置，
--      在「菜单管理」里拖顺序即可，不需要改本脚本。
-- ----------------------------

SET NAMES utf8mb4;

-- ----------------------------
-- 0、解析参照菜单（「媒体库」，用于授权继承）与顶层序号
-- ----------------------------
SET @media_id := (SELECT menu_id FROM sys_menu
                  WHERE component = 'media/index' AND menu_type = 'C'
                  ORDER BY parent_id, menu_id LIMIT 1);
SET @media_id := COALESCE(@media_id,
                 (SELECT menu_id FROM sys_menu
                  WHERE menu_type = 'M' AND (path = 'media' OR menu_name = '媒体')
                  ORDER BY menu_id LIMIT 1));

-- 顶层顺延序号（与「人物管理」同层，见头部说明：本页必须留在 /media-studios）
SET @order_num := (SELECT IFNULL(MAX(order_num), 0) + 1 FROM sys_menu WHERE parent_id = 0);

-- ----------------------------
-- 1、菜单：工作室管理（component 为判重键，menu_id 走自增）
-- ----------------------------
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT '工作室管理', 0, @order_num, 'media-studios', 'media/studio/index', '', '',
       1, 0, 'C', '0', '0', 'media:admin:edit', 'building',
       'admin', SYSDATE(), '', NULL, '媒体库工作室管理页（P5-A 动态路由改为 sys_menu 记录，P5.4 落库）'
FROM DUAL
WHERE @media_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'media/studio/index');

-- ----------------------------
-- 2、授权
--    2.1 拥有「媒体库」(@media_id) 权限的所有角色：与媒体库同组可见性保持一致
--    2.2 超管角色（role_key='admin'）：若依超管走 *:*:*，落库只为前端菜单树渲染
-- ----------------------------
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, m.menu_id
FROM sys_role_menu rm
JOIN sys_menu m ON m.component = 'media/studio/index'
WHERE rm.menu_id = @media_id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x
                  WHERE x.role_id = rm.role_id AND x.menu_id = m.menu_id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.component = 'media/studio/index'
WHERE r.role_key = 'admin' AND r.status = '0'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x
                  WHERE x.role_id = r.role_id AND x.menu_id = m.menu_id);

-- ----------------------------
-- 3、执行后自检（只读）
-- ----------------------------
SELECT '1.参照菜单(媒体库)' AS step, menu_id, menu_name, parent_id, menu_type, path, component
FROM sys_menu WHERE menu_id = @media_id;

SELECT '2.新建菜单' AS step,
       m.menu_id AS 菜单id, m.menu_name AS 菜单名, m.parent_id AS 父id,
       COALESCE(p.menu_name, '(顶层菜单)') AS 父菜单名,
       m.path AS 路由地址, m.component AS 组件, m.perms AS 权限标识,
       m.icon AS 图标, m.order_num AS 序号, m.menu_type AS 类型,
       m.visible AS 显示, m.status AS 状态
FROM sys_menu m
LEFT JOIN sys_menu p ON p.menu_id = m.parent_id
WHERE m.component = 'media/studio/index';

SELECT '3.授权角色' AS step,
       (SELECT COUNT(DISTINCT role_id) FROM sys_role_menu
        WHERE menu_id = (SELECT menu_id FROM sys_menu WHERE component = 'media/studio/index')) AS 已授权角色数,
       (SELECT GROUP_CONCAT(role_id ORDER BY role_id) FROM sys_role_menu
        WHERE menu_id = (SELECT menu_id FROM sys_menu WHERE component = 'media/studio/index')) AS 角色id列表;

SELECT '4.判重键唯一性' AS step,
       (SELECT COUNT(*) FROM sys_menu WHERE component = 'media/studio/index') AS 工作室管理菜单条数;

-- 顶层路由唯一性：同一 path 只允许一条顶层菜单（否则 /getRouters 会下发两条同名路由）
SELECT '5.顶层同路径检查' AS step,
       (SELECT COUNT(*) FROM sys_menu WHERE parent_id = 0 AND path = 'media-studios') AS 同路径菜单条数;

-- 媒体数据行数（本脚本只写 sys_menu / sys_role_menu，这里应与会话开始时完全一致）
SELECT '6.媒体数据行数(应不变)' AS step,
       (SELECT COUNT(*) FROM media_video) AS 视频,
       (SELECT COUNT(*) FROM media_photo) AS 图片,
       (SELECT COUNT(*) FROM media_album) AS 相册,
       (SELECT COUNT(*) FROM media_person) AS 人物,
       (SELECT COUNT(*) FROM media_tag) AS 标签;

-- ----------------------------
-- 4、回滚（需要时手工执行，先确认 name 再删；超管角色 id 一般就是 1）
-- ----------------------------
-- DELETE rm FROM sys_role_menu rm
--   JOIN sys_menu m ON m.menu_id = rm.menu_id
--  WHERE m.component = 'media/studio/index';
-- DELETE FROM sys_menu WHERE component = 'media/studio/index';
-- 前端回滚：恢复 QTools-M/src/router/index.js 的 dynamicRoutes 里 path='/media-studios' 那一段
--          （P5.4 起该页改由 sys_menu 下发，动态路由已删除）。
-- 备份：doc/backup/sys_menu_sys_role_menu_before_media_studio.sql（执行前用 mysqldump 导出）
