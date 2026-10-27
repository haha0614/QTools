-- ----------------------------
-- QTools 管理端 · 库根管理菜单（sys_menu 落库，P5.78）
-- 目标库：qtools（MySQL 8.0，端口 3307）
--
-- 执行方式（务必显式指定 utf8mb4；本机 mysql 客户端默认 gbk，不带会把菜单名写坏）：
--   tools/mysql/bin/mysql.exe -uroot -p -P3307 -h127.0.0.1 --default-character-set=utf8mb4 qtools \
--     -e "source E:/111/project/QTools/QTools/doc/media_root_menu.sql"
--
-- 幂等：全脚本不写死 menu_id（走自增），唯一性判据是 component='media/root/index'。
--   所有 INSERT 都是 INSERT ... SELECT ... WHERE NOT EXISTS，第二遍执行插入 0 行；
--   授权同样 NOT EXISTS 去重，可安全重跑。
--
-- 挂载位置：动态解析「媒体库」(component='media/index') 的父级目录。当前库里那个目录是
--   「媒体管理」(menu_type='M', path='/')，它的三个子菜单是 媒体库 /media、人物管理
--   /media-person、工作室管理 /media-studios；本页作为第 4 个兄弟，路由 = /media-roots
--   （若依 buildMenus 对「M 目录 + C 子项」拼成 /<子path>）。
--   —— 不硬编码 menu_id：换库 / 换父级都不用改脚本。
--
-- perms 取值依据：页面只读也用得上（看库根清单与条目数），所以菜单取 media:admin:list；
--   新增 / 编辑 / 删除 / 行内「未登录可见」开关四个动作在前端是 v-hasPermi="['media:admin:edit']"，
--   后端对应 MediaAdminController 的 POST/PUT/DELETE /admin/roots 也是 media:admin:edit。
--
-- 影响面：不 UPDATE 任何既有行；只新增 1 条 sys_menu + N 条 sys_role_menu。
-- 回滚：见文件末尾注释。
-- ----------------------------

SET NAMES utf8mb4;

-- ----------------------------
-- 0、解析父级（媒体库所在目录）、参照菜单（媒体库，用于授权继承）与同层序号
-- ----------------------------
SET @media_id := (SELECT menu_id FROM sys_menu
                  WHERE component = 'media/index' AND menu_type = 'C'
                  ORDER BY parent_id, menu_id LIMIT 1);
SET @parent_id := (SELECT parent_id FROM sys_menu WHERE menu_id = @media_id);
-- 兜底：媒体库菜单被删过时退回「任意 M 目录」
SET @parent_id := COALESCE(@parent_id,
                   (SELECT menu_id FROM sys_menu WHERE menu_type = 'M' AND path = '/' ORDER BY menu_id LIMIT 1));

SET @order_num := (SELECT IFNULL(MAX(order_num), 0) + 1 FROM sys_menu WHERE parent_id = @parent_id);

-- ----------------------------
-- 1、菜单：库根管理（component 为判重键，menu_id 走自增）
-- ----------------------------
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
SELECT '库根管理', @parent_id, @order_num, 'media-roots', 'media/root/index', '', '',
       1, 0, 'C', '0', '0', 'media:admin:list', 'files',
       'admin', SYSDATE(), '', NULL, '多库根管理页（P5.78）：未登录是否可见 / 新增 / 编辑 / 删除'
FROM DUAL
WHERE @parent_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'media/root/index');

-- ----------------------------
-- 2、授权
--    2.1 拥有「媒体库」(@media_id) 权限的所有角色：与媒体库同组可见性保持一致
--    2.2 超管角色（role_key='admin'）：若依超管走 *:*:*，落库只为前端菜单树渲染
-- ----------------------------
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, m.menu_id
FROM sys_role_menu rm
JOIN sys_menu m ON m.component = 'media/root/index'
WHERE rm.menu_id = @media_id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x
                  WHERE x.role_id = rm.role_id AND x.menu_id = m.menu_id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.component = 'media/root/index'
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
WHERE m.component = 'media/root/index';

SELECT '3.授权角色' AS step,
       (SELECT COUNT(DISTINCT role_id) FROM sys_role_menu
        WHERE menu_id = (SELECT menu_id FROM sys_menu WHERE component = 'media/root/index')) AS 已授权角色数,
       (SELECT GROUP_CONCAT(role_id ORDER BY role_id) FROM sys_role_menu
        WHERE menu_id = (SELECT menu_id FROM sys_menu WHERE component = 'media/root/index')) AS 角色id列表;

SELECT '4.判重键唯一性' AS step,
       (SELECT COUNT(*) FROM sys_menu WHERE component = 'media/root/index') AS 库根管理菜单条数;

-- 媒体数据行数（本脚本只写 sys_menu / sys_role_menu，这里应与会话开始时完全一致）
SELECT '5.媒体数据行数(应不变)' AS step,
       (SELECT COUNT(*) FROM media_video) AS 视频,
       (SELECT COUNT(*) FROM media_photo) AS 图片,
       (SELECT COUNT(*) FROM media_album) AS 相册,
       (SELECT COUNT(*) FROM media_person) AS 人物;

-- ----------------------------
-- 4、回滚（需要时手工执行）
-- ----------------------------
-- DELETE rm FROM sys_role_menu rm
--   JOIN sys_menu m ON m.menu_id = rm.menu_id
--  WHERE m.component = 'media/root/index';
-- DELETE FROM sys_menu WHERE component = 'media/root/index';
