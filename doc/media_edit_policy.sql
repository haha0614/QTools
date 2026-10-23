-- =====================================================================
-- 媒体库「站端编辑策略」（media.userEditing）
--
-- 交付内容：
--   仅 1 行 sys_config：config_key='media.userEditing'，config_value 初始 'full'
--
-- 三档语义（与 Java 侧 MediaEditPolicyServiceImpl 严格一致）：
--   off    站端禁止编辑：/api/media 的写接口一律 403
--   local  站端只在浏览器本地改：写接口同样 403，前端可存本地 localStorage
--   full   完全编辑：站端写接口正常落库（默认档位）
--
-- 为什么默认 full：
--   本项目定位是单实例自托管（Stash 式）——克隆者跑的是「自己」的实例、自己的媒体库，
--   站端编辑就是主人的编辑能力，默认放开最省事。
--   公网演示实例请手动改成 local 或 off（一条 UPDATE 即可，见文末），
--   避免匿名访客往真库里写标签 / 评分。
--
-- 幂等：exists 才跳过，本文件可反复执行；已存在则「不动」——
--   不会把管理员已经改成 local/off 的实例duang回 full。
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_edit_policy.sql"
--   （必须带 --default-character-set=utf8mb4，否则中文备注会按 GBK 写进数据字典）
--
-- 注意：
--   1) 本表在该实例上「没有」config_key 唯一索引，因此不能用 INSERT ... ON DUPLICATE KEY，
--      这里用 INSERT ... SELECT ... WHERE NOT EXISTS 保证幂等。
--   2) 若依启动时会把 sys_config 全量预热进 Redis（CacheConstants.SYS_CONFIG_KEY），
--      本行由直连 SQL 或媒体库自己的接口写入时，Redis 里的若依参数缓存可能滞后；
--      媒体库自身的读取不依赖该缓存（直读 sys_config + 30s 本地缓存），功能不受影响。
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1) 写入默认档位（已存在则不动）
-- ---------------------------------------------------------------------
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '媒体库站端编辑策略',
       'media.userEditing',
       'full',
       'N',
       'admin',
       SYSDATE(),
       '站端编辑策略：off=站端禁止编辑 / local=只在浏览器本地改（服务端拒写） / full=完全编辑（默认）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'media.userEditing');

-- ---------------------------------------------------------------------
-- 2) 自检（应恰好 1 行）
-- ---------------------------------------------------------------------
SELECT config_id, config_name, config_key, config_value, config_type, remark
FROM sys_config
WHERE config_key = 'media.userEditing';

-- ---------------------------------------------------------------------
-- 3) 公网演示实例：把下面两条之一放开执行
-- ---------------------------------------------------------------------
-- UPDATE sys_config SET config_value = 'local', update_time = SYSDATE() WHERE config_key = 'media.userEditing';
-- UPDATE sys_config SET config_value = 'off',   update_time = SYSDATE() WHERE config_key = 'media.userEditing';
