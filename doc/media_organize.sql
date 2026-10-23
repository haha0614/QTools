-- =====================================================================
-- 媒体库组织能力（M3-A）：评分 / 标签字典 / 时间点标记
--
-- 交付内容：
--   (1) media_video.rating        人工评分 0-10（TINYINT NULL；NULL = 未评分）
--   (2) media_video_marker        时间点标记（id, video_id, pos, label, color, create_time）
--   (3) media_tag                 标签字典（id, name UNIQUE, color, use_count, create_time）
--   (4) media_video_tag.tag_id    关联列 —— 字典标签挂到既有 media_video_tag 上
--
-- 另：站端能不能改这些属性由「站端编辑策略」决定（off / local / full），
--     它不是 DDL，单独在 doc/media_edit_policy.sql（sys_config['media.userEditing']），
--     语义见本文件末尾「7) 站端编辑策略」。
--
-- 语义约定（与 Java 侧 MediaOrganizeServiceImpl 严格一致）：
--   * 评分刻度 0-10：10 最好，NULL = 未评分（0 是有效的最低分，不是"没有分"）
--   * 字典标签写进 media_video_tag 时 facet 固定为 '标签'、tag_id = media_tag.id、
--     name 冗余存放标签名 —— 这样「索引接口 / 详情接口」既有的 facet/name 读取路径
--     一行都不用改，字典标签自动出现在 tags 里（成对 [facet,name]）。
--     扫描脚本写的历史行 facet ∈ {分类,类型,题材,行为,角色,场景,穿着,审查,质量,部位,动作,其他,演员}，
--     没有 '标签'，两者不会冲突。
--   * use_count 是冗余计数，写入时按 media_video_tag 实际行数重算（不做 +1/-1 漂移）
--   * 幂等：所有 DDL 先查 information_schema，已存在则跳过；本文件可反复执行
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_organize.sql"
--   （必须带 --default-character-set=utf8mb4，否则中文注释会按 GBK 写进数据字典）
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0) 辅助过程：列 / 索引不存在才创建（与 media_multiroot.sql、media_trash_multiroot.sql 同风格）
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS qtools_organize_add_column;

DROP PROCEDURE IF EXISTS qtools_organize_add_index;

DELIMITER $$

CREATE PROCEDURE qtools_organize_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(500))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE qtools_organize_add_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_columns VARCHAR(255), IN p_unique TINYINT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = p_table AND index_name = p_index
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', IF(p_unique = 1, 'UNIQUE ', ''), 'INDEX `', p_index, '` (', p_columns, ')');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

-- ---------------------------------------------------------------------
-- 1) media_video.rating —— 人工评分 0-10
--    NULL 表示未评分；0 是"最低分"这一合法取值，因此不能用 0 当默认值。
-- ---------------------------------------------------------------------
CALL qtools_organize_add_column('media_video', 'rating',
  'TINYINT NULL COMMENT ''人工评分 0-10：10 最好，NULL = 未评分（0 是有效的最低分）'' AFTER hidden');

CALL qtools_organize_add_index('media_video', 'idx_media_video_rating', '`rating`', 0);

-- ---------------------------------------------------------------------
-- 2) media_video_marker —— 视频时间点标记
--    一集视频多个标记；按 (video_id, pos) 取用，因此建联合索引。
--    pos 上限由应用侧按 media_video.dur 校验（dur 为 NULL 时跳过校验）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS media_video_marker (
  id          BIGINT        NOT NULL AUTO_INCREMENT              COMMENT '主键',
  video_id    VARCHAR(16)   NOT NULL                             COMMENT '视频 id，关联 media_video.id',
  pos         DECIMAL(10,2) NOT NULL                             COMMENT '时间点（秒，保留 2 位；应用侧校验 <= media_video.dur，dur 为空时跳过）',
  label       VARCHAR(64)   NULL                                 COMMENT '标记名称（可空；去首尾空格后 <= 64 字符）',
  color       VARCHAR(16)   NULL                                 COMMENT '标记颜色（可空；建议 #RRGGBB）',
  create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_marker_video_pos (video_id, pos)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-视频时间点标记';

-- ---------------------------------------------------------------------
-- 3) media_tag —— 标签字典
--    既有 media_video_tag 只是 (video_id, facet, name) 关联表，没有字典，
--    因此新建字典表；name 全局唯一，重名新建一律复用既有行（应用侧保证）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS media_tag (
  id          BIGINT      NOT NULL AUTO_INCREMENT                COMMENT '主键',
  name        VARCHAR(32) NOT NULL                               COMMENT '标签名（去首尾空格后 1-32 字符，全局唯一）',
  color       VARCHAR(16) NULL                                   COMMENT '标签颜色（可空；建议 #RRGGBB）',
  use_count   INT         NOT NULL DEFAULT 0                     COMMENT '引用次数（media_video_tag 中 tag_id 相同的行数，写入时重算）',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP     COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tag_name (name),
  KEY idx_tag_use_count (use_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-标签字典';

-- ---------------------------------------------------------------------
-- 4) media_video_tag 补 tag_id + 索引
--    uk_tag_video_tag (video_id, tag_id)：同一视频同一字典标签只能有一行，
--    写关联时用 INSERT IGNORE 天然去重（历史行 tag_id 为 NULL，MySQL 唯一键允许多个 NULL，
--    因此既有 684 行不受影响）。
-- ---------------------------------------------------------------------
CALL qtools_organize_add_column('media_video_tag', 'tag_id',
  'BIGINT NULL COMMENT ''标签字典 id，关联 media_tag.id；NULL = 扫描脚本写的 facet 标签，不属字典'' AFTER name');

CALL qtools_organize_add_index('media_video_tag', 'uk_tag_video_tag', '`video_id`,`tag_id`', 1);

CALL qtools_organize_add_index('media_video_tag', 'idx_tag_tag_id', '`tag_id`', 0);

-- ---------------------------------------------------------------------
-- 5) 收尾：删除辅助过程
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS qtools_organize_add_column;

DROP PROCEDURE IF EXISTS qtools_organize_add_index;

-- ---------------------------------------------------------------------
-- 6) 自检（只读）：应看到 rating 列、media_video_marker / media_tag 两张表、
--    media_video_tag.tag_id 及 uk_tag_video_tag / idx_tag_tag_id 两个索引
-- ---------------------------------------------------------------------
SELECT table_name, column_name, column_type, is_nullable
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND (   (table_name = 'media_video'     AND column_name = 'rating')
        OR (table_name = 'media_video_tag' AND column_name = 'tag_id') )
 ORDER BY table_name, column_name;

SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN ('idx_media_video_rating', 'uk_tag_video_tag', 'idx_tag_tag_id',
                      'idx_marker_video_pos', 'uk_tag_name', 'idx_tag_use_count')
 GROUP BY table_name, index_name, non_unique
 ORDER BY table_name, index_name;

SELECT table_name, table_rows
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('media_tag', 'media_video_marker')
 ORDER BY table_name;

-- ---------------------------------------------------------------------
-- 7) 站端编辑策略（语义说明；DDL/INSERT 在 doc/media_edit_policy.sql）
--
--   sys_config['media.userEditing'] 一行控制 /api/media 下的写接口：
--
--     full   完全编辑（默认）：站端可现场建标签、改评分、打标记、批量补丁，正常落库
--     local  站端只在浏览器本地改：服务端写接口一律 403，前端可把改动存 localStorage
--     off    站端禁止编辑：写接口一律 403，界面按只读渲染
--
--   闸门位置：MediaEditController 的每个写接口第一行就调
--   IMediaEditPolicyService.requireWritable()，先于 body 解析和任何业务调用；
--   非 full 时回 403 {"error":"当前为演示/本地模式，站端不允许写入
--   （管理员可在媒体库「编辑策略」中改为完全编辑）"}。
--
--   读接口不受影响：GET /api/media/edit-policy（前端据此决定可编辑/只读）与
--   GET /api/media/tags（标签选择器字典）在任何档位下都可用。
--
--   默认 full 的理由：本项目是单实例自托管（Stash 式），克隆者跑的是自己的实例、
--   自己的媒体库，站端编辑就是主人的编辑能力。
--   ★ 公网演示实例请务必改成 local 或 off，否则匿名访客可以直接往真库写标签 / 评分：
--       UPDATE sys_config SET config_value = 'local' WHERE config_key = 'media.userEditing';
--
--   改档位两条路：管理端 PUT /api/media/admin/edit-policy body {"level":"local"}
--   （需 media:admin:edit 权限），或直接执行上面那条 UPDATE。
--   服务端读取带 30s 本地缓存，管理端接口写入时立即失效；直连改库最迟 30s 生效。
-- ---------------------------------------------------------------------
