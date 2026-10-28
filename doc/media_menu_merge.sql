-- ----------------------------
-- QTools 管理端 · 媒体库入口合并（sys_menu 落库，P5.97）
-- 目标库：qtools（MySQL 8.0，端口 3307）
--
-- 执行方式（务必显式指定 utf8mb4；本机 mysql 客户端默认 gbk，不带会把菜单名写坏）：
--   tools/mysql/bin/mysql.exe -uroot -p -P3307 -h127.0.0.1 --default-character-set=utf8mb4 qtools \
--     -e "source E:/111/project/QTools/QTools/doc/media_menu_merge.sql"
--
-- 改了什么（只改可见性，不删行、不动权限）：
--   1) 「扫描入库」（component='media/scan/index'）设为 visible='1'（若依语义：0=显示 / 1=隐藏）。
--      保留行 = 保留历史授权（sys_role_menu）与菜单管理里的可恢复点；扫描入口并入
--      「媒体库」页工具栏的「扫描 / 导入」抽屉与「库根管理」页的每行「扫描」+ 新增库根预演。
--   2) 「库根管理」保证可见（visible='0'）：它现在是扫描入口之一，被藏起来就没地方建根了。
--
-- 为什么用 UPDATE 而不是 DELETE：
--   sys_role_menu 里那一行授权是历史记录；删菜单要连带删授权，回滚时角色权限就丢了。
--   隐藏只是「不在侧边栏渲染」，/media-scan 路由仍然可达（排障时可以直接进）。
--
-- 幂等：两条 UPDATE 都带 visible <> 目标值 的判据，第二遍执行影响 0 行；
--       末尾自检全是只读 SELECT，可安全重跑。
-- 回滚（需要时手工执行）：
--   UPDATE sys_menu SET visible='0' WHERE component='media/scan/index';
-- ----------------------------

SET NAMES utf8mb4;

-- ----------------------------
-- 0、改动前状态（只读，留痕）
-- ----------------------------
SELECT '0.改动前' AS step, menu_id AS 菜单id, menu_name AS 菜单名, visible AS 可见, status AS 状态,
       perms AS 权限标识, component AS 组件
FROM sys_menu
WHERE component IN ('media/scan/index', 'media/root/index')
ORDER BY menu_id;

-- ----------------------------
-- 1、隐藏「扫描入库」菜单（不删行）
-- ----------------------------
UPDATE sys_menu
SET visible = '1',
    update_by = 'admin',
    update_time = SYSDATE(),
    remark = CONCAT(IFNULL(remark, ''), ' [P5.97 入口已并入媒体库/库根管理，visible=1 隐藏但不删行]')
WHERE component = 'media/scan/index'
  AND visible <> '1';

-- ----------------------------
-- 2、保证「库根管理」可见（扫描入口之一）
-- ----------------------------
UPDATE sys_menu
SET visible = '0',
    update_by = 'admin',
    update_time = SYSDATE()
WHERE component = 'media/root/index'
  AND visible <> '0';

-- ----------------------------
-- 3、执行后自检（只读）
-- ----------------------------
SELECT '1.媒体库相关菜单（C 类页面）' AS step,
       m.menu_id AS 菜单id, m.menu_name AS 菜单名,
       COALESCE(p.menu_name, '(顶层)') AS 父菜单,
       m.path AS 路由地址, m.component AS 组件, m.visible AS 可见, m.order_num AS 序号
FROM sys_menu m
LEFT JOIN sys_menu p ON p.menu_id = m.parent_id
WHERE m.menu_type = 'C'
  AND (m.component LIKE 'media/%' OR m.path LIKE 'media%')
ORDER BY m.parent_id, m.order_num, m.menu_id;

SELECT '2.隐藏项唯一性' AS step,
       (SELECT COUNT(*) FROM sys_menu WHERE component = 'media/scan/index') AS 扫描入库菜单条数,
       (SELECT COUNT(*) FROM sys_menu WHERE component = 'media/scan/index' AND visible = '1') AS 已隐藏条数,
       (SELECT COUNT(*) FROM sys_menu WHERE component = 'media/root/index') AS 库根管理菜单条数,
       (SELECT COUNT(*) FROM sys_menu WHERE component = 'media/root/index' AND visible = '0') AS 可见条数;

SELECT '3.授权未被改动（扫描入库仍挂在原角色上）' AS step,
       m.menu_id AS 菜单id, m.visible AS 可见,
       (SELECT COUNT(DISTINCT role_id) FROM sys_role_menu WHERE menu_id = m.menu_id) AS 已授权角色数
FROM sys_menu m
WHERE m.component = 'media/scan/index';

-- 媒体数据行数（本脚本只写 sys_menu，这里必须与会话开始时完全一致）
SELECT '4.媒体数据行数(应不变)' AS step,
       (SELECT COUNT(*) FROM media_video) AS 视频,
       (SELECT COUNT(*) FROM media_photo) AS 图片,
       (SELECT COUNT(*) FROM media_album) AS 相册,
       (SELECT COUNT(*) FROM media_root) AS 库根;
