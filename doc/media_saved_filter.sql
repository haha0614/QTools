-- =====================================================================
-- 媒体站「保存的筛选条件」media_saved_filter（P2-A）
--
-- 交付内容：
--   1 张表 —— 站端把当前筛选组合存成一个命名条目，下次一键还原。
--
-- 为什么 params 是 TEXT 而不是结构化列：
--   筛选条件的维度由前端决定（kind / 人物 / 标签 / 评分 / 排序 / 关键词 …），
--   后端只保证「是合法 JSON」且 < 4KB（UTF-8 字节）。前端加一个维度不必改表、不必发版。
--
-- 为什么唯一键是 (name, owner_key) 而不是 name 单列：
--   owner_key 是匿名设备身份（见 MediaOwnerServiceImpl）：
--     owner_key = 某设备  → 该设备私有条目
--     owner_key IS NULL   → 全局条目（所有访客可见，只能由管理端清理）
--   name 单列唯一会让「A 设备存了『我的收藏』」把 B 设备的同名条目标成不可用
--   （INSERT 只能整行覆盖，owner_key 会被改写）。改用复合键后：
--     同名同 owner = 覆盖（需求要求，不是 409）；
--     不同 owner 可以各存一份同名；
--     全局条目（owner_key IS NULL）不参与唯一判重 —— MySQL 对 NULL 不做重复判定，
--     因此全局条目只由管理端 / 种子 SQL 写入，站端写路径永远带 owner_key。
--
-- 幂等：CREATE TABLE IF NOT EXISTS，本文件可反复执行（第二遍无副作用）。
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 -P3307 qtools -e "source media_saved_filter.sql"
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS media_saved_filter (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(64)  NOT NULL                 COMMENT '名称（同一 owner 下唯一，重复保存=覆盖）',
    params      TEXT         NOT NULL                 COMMENT '筛选条件原始 JSON（服务层校验 < 4KB）',
    owner_key   VARCHAR(64)  NULL     DEFAULT NULL    COMMENT '归属匿名设备；NULL=全局条目',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_saved_filter_name_owner (name, owner_key),
    KEY idx_media_saved_filter_owner (owner_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '媒体站保存的筛选条件';

-- ---------------------------------------------------------------------
-- 自检：表结构（应能看到 uk_media_saved_filter_name_owner）
-- ---------------------------------------------------------------------
SHOW CREATE TABLE media_saved_filter;

-- ---------------------------------------------------------------------
-- 自检：条数（新库应为 0）
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS saved_filter_rows FROM media_saved_filter;
