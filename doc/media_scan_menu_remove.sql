-- ----------------------------
-- QTools 管理端 · 「扫描入库」菜单下线（P6.04）
-- 目标库：qtools（MySQL 8.0，端口 3307）
--
-- 执行方式（务必显式指定 utf8mb4；本机 mysql 客户端默认 gbk，不带会把菜单名写坏）：
--   tools/mysql/bin/mysql.exe -uroot -p -P3307 -h127.0.0.1 --default-character-set=utf8mb4 qtools \
--     -e "source QTools/doc/media_scan_menu_remove.sql"
--
-- 为什么删：P6.04 已把独立「扫描入库」页删除，扫盘入口只剩两处——
--   1) 媒体库工具栏「扫描 / 导入」抽屉；
--   2) 库根管理页行内「扫描」+ 新增库根后的自动扫描。
--   菜单留着只会把人带到一个已下线的页面，所以物理删除（不是 visible=1 隐藏）。
--
-- 幂等：按 menu_id=2104 或 component='media/scan/index' 双判据删除，重复执行影响 0 行；
--   先删 sys_role_menu（若依没有外键，顺序反过来会留下悬空授权行）。
-- 影响面：只删这 1 条 sys_menu + 它的 sys_role_menu 授权行；不 UPDATE 任何其它菜单，
--   不碰 media_* 业务表。回滚：重新执行 QTools/doc/media_scan_menu_remove.sql 的反向脚本不可用，
--   需要恢复时用备份的 sys_menu 行重建（本文件末尾给出原始 INSERT 的形状）。
-- ----------------------------

SET NAMES utf8mb4;

-- 删除前留一份可回滚的字段快照（只读查询，不会写入任何表）
SELECT '0.删除前快照' AS step, menu_id, menu_name, parent_id, order_num, path, component, query,
       route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by,
       create_time, update_by, update_time, remark
FROM sys_menu
WHERE menu_id = 2104 OR component = 'media/scan/index';

-- 1、授权行先删（role_menu 没有外键，先删菜单会留下悬空行）
DELETE rm
FROM sys_role_menu rm
JOIN sys_menu m ON m.menu_id = rm.menu_id
WHERE m.menu_id = 2104 OR m.component = 'media/scan/index';

-- 2、菜单本身
DELETE FROM sys_menu
WHERE menu_id = 2104 OR component = 'media/scan/index';

-- 3、执行后自检（两条都应为 0）
SELECT '1.残留菜单' AS step,
       (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2104 OR component = 'media/scan/index' OR path = 'media-scan') AS 菜单条数;
SELECT '2.残留授权' AS step,
       (SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = 2104) AS 授权条数;
SELECT '3.媒体数据行数(应不变)' AS step,
       (SELECT COUNT(*) FROM media_video) AS 视频,
       (SELECT COUNT(*) FROM media_photo) AS 图片,
       (SELECT COUNT(*) FROM media_album) AS 相册,
       (SELECT COUNT(*) FROM media_person) AS 人物;

-- ----------------------------
-- 回滚参考（需要时手工执行；menu_id 走自增时不必固定 2104）
-- INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name,
--                       is_frame, is_cache, menu_type, visible, status, perms, icon,
--                       create_by, create_time, update_by, update_time, remark)
-- SELECT '扫描入库', 2015, 5, 'media-scan', 'media/scan/index', '', '',
--        1, 0, 'C', '0', '0', 'media:admin:list', 'search',
--        'admin', SYSDATE(), '', NULL, '扫描入库页（P5.91）';
-- 再把 sys_role_menu 授权行补回（P6.04 前只有 role_id=1 授权）
-- ----------------------------
