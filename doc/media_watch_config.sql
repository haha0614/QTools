-- =====================================================================
-- 媒体库「自动扫描（文件监听）」运行期配置 —— sys_config 三键
--
-- 交付内容（各 1 行 sys_config）：
--   media.watchEnabled            'false'  是否监听媒体根并自动触发 mode=scan（默认关）
--   media.watchDebounceSeconds    '5'      去抖窗口（秒，1-60）：窗口内 N 个事件合并成 1 次扫描
--   media.watchQuietMinutes       ''       夜间静默区间（空 = 不静默；HH:mm-HH:mm 或 起始-结束 分钟）
--
-- 与 Java 侧严格一致（MediaWatchConfigServiceImpl）：
--   * 三个键是「当前生效值」的唯一数据源，读取带 30s 本地缓存（直改库最迟 30s 生效）
--   * 键缺失 / 值非法 → 回退 yml 兜底 qtools.media.watch.debounceSeconds / quietMinutes 并 warn；
--     media.watchEnabled 没有 yml 兜底（旧的 qtools.media.watch.enabled 已迁移到这一行），
--     缺失 / 非法一律按 false
--
-- 两层模型（重要）：
--   * 部署层能力总闸是 yml 的 qtools.media.watch.allowRuntimeToggle（默认 true）。
--     false 时禁止一切运行期开关：PUT /api/media/admin/watch/config 一律 403、pause/resume 不可用，
--     改开关只能改本表的值 + 重启进程。
--   * 本表三键是运行期那一层：PUT /api/media/admin/watch/config 改完立即生效（不重启）。
--
-- 幂等：WHERE NOT EXISTS，本文件可反复执行；已存在则「不动」——
--   不会把管理员已经打开的 media.watchEnabled 又按回 false。
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 -P3307 qtools -e "source media_watch_config.sql"
--   （必须带 --default-character-set=utf8mb4，否则中文备注会按 GBK 写进数据字典）
--
-- 注意：
--   1) 本实例 sys_config 没有 config_key 唯一索引，因此不能用 INSERT ... ON DUPLICATE KEY，
--      这里用 INSERT ... SELECT ... WHERE NOT EXISTS 保证幂等（Java 侧写库同理）。
--   2) 若依启动时会把 sys_config 全量预热进 Redis，本表由直连 SQL 改动时若依的参数缓存可能滞后；
--      媒体库自身的读取不依赖该缓存（直读 sys_config + 30s 本地缓存），功能不受影响。
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1) 三键（已存在则不动）
-- ---------------------------------------------------------------------
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '媒体库自动扫描开关',
       'media.watchEnabled',
       'false',
       'N',
       'admin',
       SYSDATE(),
       '自动扫描（文件监听）：true=监听媒体根并自动触发 mode=scan / false=不注册任何 WatchService（默认）。运行期可改：PUT /api/media/admin/watch/config'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'media.watchEnabled');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '媒体库自动扫描去抖秒数',
       'media.watchDebounceSeconds',
       '5',
       'N',
       'admin',
       SYSDATE(),
       '自动扫描去抖窗口（秒，1-60）：窗口内 N 个文件事件合并成 1 次扫描'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'media.watchDebounceSeconds');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '媒体库自动扫描夜间静默区间',
       'media.watchQuietMinutes',
       '',
       'N',
       'admin',
       SYSDATE(),
       '夜间静默区间：HH:mm-HH:mm（如 23:00-07:00）或起始-结束分钟数（如 1380-420，跨零点允许）；空=不静默'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'media.watchQuietMinutes');

-- ---------------------------------------------------------------------
-- 2) 自检（应恰好 3 行，初值 false / 5 / 空串）
-- ---------------------------------------------------------------------
SELECT config_id, config_name, config_key, config_value, config_type, remark
FROM sys_config
WHERE config_key IN ('media.watchEnabled', 'media.watchDebounceSeconds', 'media.watchQuietMinutes')
ORDER BY config_key;

-- ---------------------------------------------------------------------
-- 3) 常用手工操作（按需放开执行；能打开运行期开关时优先用 PUT 接口）
-- ---------------------------------------------------------------------
-- 打开自动扫描（需重启才生效：本行只是把值改掉，进程内监听器要重启才会挂载）
-- UPDATE sys_config SET config_value = 'true',  update_time = SYSDATE() WHERE config_key = 'media.watchEnabled';
-- 改去抖到 10 秒
-- UPDATE sys_config SET config_value = '10',    update_time = SYSDATE() WHERE config_key = 'media.watchDebounceSeconds';
-- 夜间静默 23:00-07:00（跨零点）
-- UPDATE sys_config SET config_value = '23:00-07:00', update_time = SYSDATE() WHERE config_key = 'media.watchQuietMinutes';
