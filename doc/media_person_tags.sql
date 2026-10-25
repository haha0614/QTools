-- =====================================================================
-- 媒体库人物 / 演员体系 + 标签层级（P3-A）
--
-- 交付内容：
--   (1) media_person 补列           alias / note / avatar_path / gender / birthday / use_count
--   (2) media_video_actor 补列      person_id（关联 media_person.id）+ role（角色，允许 NULL）
--       并加唯一键 uk_actor_video_person (video_id, person_id)：同一视频同一个人物只有一行
--   (3) media_album_person          相册 ↔ 人物多对多（既有 media_album.person_id 是单选列，
--       只能挂一个人物；多人物必须新表，同时把 person_id 保留为「首人物」镜像以兼容旧读路径）
--   (4) media_tag.parent_id         标签层级（父子）；环 / 自引用由应用侧沿 parent 链 20 层校验
--
-- 语义约定（与 Java 侧 MediaPersonServiceImpl / MediaOrganizeServiceImpl 严格一致）：
--   * media_video_actor 有两类行：
--       - 扫描脚本写的历史行（person_id IS NULL，只有 name）—— 任何字典写操作都不碰它们
--       - 管理端绑定的人物行（person_id = media_person.id，name 冗余人物名，role 可空）
--     风格与 media_video_tag.tag_id 完全一致：所有「全量替换」都带 person_id IS NOT NULL 条件。
--   * media_person.use_count 是冗余计数（引用它的关联行数），写入时按实际行数重算，不做 +1/-1 漂移
--   * media_album.person_id 与 media_album_person 并存：前者 = 多人物里的第一个人（无人物时 NULL），
--     由应用侧同时维护；因此「某人物名下的相册」= media_album_person ∪ media_album.person_id
--   * 幂等：所有 DDL 先查 information_schema，已存在则跳过；本文件可反复执行
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_person_tags.sql"
--   （必须带 --default-character-set=utf8mb4，否则中文注释会按 GBK 写进数据字典）
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0) 辅助过程：列 / 索引不存在才创建（与 media_organize.sql 同风格）
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS qtools_person_add_column;

DROP PROCEDURE IF EXISTS qtools_person_add_index;

DELIMITER $$

CREATE PROCEDURE qtools_person_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(500))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE qtools_person_add_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_columns VARCHAR(255), IN p_unique TINYINT)
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
-- 1) media_person 补列
--    alias        别名（可空；<=128）
--    note         备注（可空；<=512）
--    avatar_path  头像路径（可空；<=400，站内相对路径或 http(s) URL，由应用侧校验）
--    gender       性别（可空；<=16，自由文本：女 / 男 / 未知）
--    birthday     生日（可空；DATE）
--    use_count    引用次数（冗余列；= 引用它的 media_video_actor 行数，写入时重算）
-- ---------------------------------------------------------------------
CALL qtools_person_add_column('media_person', 'alias',
  'VARCHAR(128) NULL COMMENT ''别名（可空；<=128 字符）'' AFTER name');

CALL qtools_person_add_column('media_person', 'note',
  'VARCHAR(512) NULL COMMENT ''备注（可空；<=512 字符）'' AFTER alias');

CALL qtools_person_add_column('media_person', 'avatar_path',
  'VARCHAR(400) NULL COMMENT ''头像路径（可空；站内相对路径或 http(s) URL）'' AFTER note');

CALL qtools_person_add_column('media_person', 'gender',
  'VARCHAR(16) NULL COMMENT ''性别（可空；自由文本，建议 女 / 男 / 未知）'' AFTER avatar_path');

CALL qtools_person_add_column('media_person', 'birthday',
  'DATE NULL COMMENT ''生日（可空）'' AFTER gender');

CALL qtools_person_add_column('media_person', 'use_count',
  'INT NOT NULL DEFAULT 0 COMMENT ''引用次数（media_video_actor 中 person_id = 本 id 的行数，写入时重算）'' AFTER birthday');

-- ---------------------------------------------------------------------
-- 2) media_video_actor 补 person_id / role + 唯一键
--    role 允许 NULL（「未指定角色」是合法状态，不要给默认值 '主演'，
--    否则历史行与扫描行会被写成一个不存在的约定）。
-- ---------------------------------------------------------------------
CALL qtools_person_add_column('media_video_actor', 'person_id',
  'BIGINT NULL COMMENT ''人物 id，关联 media_person.id；NULL = 扫描脚本写的 name-only 历史行'' AFTER name');

CALL qtools_person_add_column('media_video_actor', 'role',
  'VARCHAR(32) NULL COMMENT ''角色（如 主演 / 配角；NULL = 未指定）'' AFTER person_id');

-- 库里若已存在 role 且为 NOT NULL（别的分支建的列），这里放宽成 NULL
DROP PROCEDURE IF EXISTS qtools_person_nullable_role;

DELIMITER $$

CREATE PROCEDURE qtools_person_nullable_role()
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_video_actor'
      AND column_name = 'role' AND is_nullable = 'NO'
  ) THEN
    ALTER TABLE media_video_actor
      MODIFY COLUMN role VARCHAR(32) NULL COMMENT '角色（如 主演 / 配角；NULL = 未指定）';
  END IF;
END$$

DELIMITER ;

CALL qtools_person_nullable_role();

DROP PROCEDURE IF EXISTS qtools_person_nullable_role;

-- 同一视频同一个人物只能一行（INSERT IGNORE 天然去重）；
-- person_id 为 NULL 的历史行不受影响（MySQL 唯一键允许多个 NULL）
CALL qtools_person_add_index('media_video_actor', 'uk_actor_video_person', '`video_id`,`person_id`', 1);

CALL qtools_person_add_index('media_video_actor', 'idx_actor_person', '`person_id`', 0);

-- ---------------------------------------------------------------------
-- 3) media_album_person —— 相册 ↔ 人物（多对多）
--    media_album.person_id 是单选列，多人物场景不够用；本表是权威关联，
--    person_id 退化成「首人物」镜像（应用侧同步维护，旧索引 people[] / admin?personId 过滤照旧可用）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS media_album_person (
  album_id    VARCHAR(16) NOT NULL                    COMMENT '相册 id，关联 media_album.id',
  person_id   BIGINT      NOT NULL                    COMMENT '人物 id，关联 media_person.id',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
  PRIMARY KEY (album_id, person_id),
  KEY idx_album_person_person (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-相册人物关联';

-- 老库把单选 person_id 回填成关联行（幂等：已在表里的不会重复插）
INSERT IGNORE INTO media_album_person (album_id, person_id)
SELECT a.id, a.person_id
  FROM media_album a
  JOIN media_person p ON p.id = a.person_id
 WHERE a.person_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 4) media_tag.parent_id —— 标签层级（父子）
--    NULL = 顶层标签。约束（自引用 / 环 / 深度 <= 20）由应用侧在写入时沿 parent 链校验，
--    MySQL 8 的 CHECK 不能跨行查表，因此不落成数据库约束。
-- ---------------------------------------------------------------------
CALL qtools_person_add_column('media_tag', 'parent_id',
  'BIGINT NULL COMMENT ''父标签 id，关联 media_tag.id；NULL = 顶层标签'' AFTER name');

CALL qtools_person_add_index('media_tag', 'idx_tag_parent', '`parent_id`', 0);

-- 建表时就已带 parent_id 的老库（本文件重复执行）不需要额外处理：上面两个 CALL 自动跳过

-- ---------------------------------------------------------------------
-- 5) 收尾：删除辅助过程
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS qtools_person_add_column;

DROP PROCEDURE IF EXISTS qtools_person_add_index;

-- ---------------------------------------------------------------------
-- 6) 自检（只读，可反复执行）
-- ---------------------------------------------------------------------
SELECT table_name, column_name, column_type, is_nullable, column_default
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND (   (table_name = 'media_person'      AND column_name IN ('alias','note','avatar_path','gender','birthday','use_count'))
        OR (table_name = 'media_video_actor' AND column_name IN ('person_id','role'))
        OR (table_name = 'media_tag'         AND column_name = 'parent_id') )
 ORDER BY table_name, ordinal_position;

SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN ('uk_actor_video_person', 'idx_actor_person', 'idx_tag_parent', 'idx_album_person_person')
 GROUP BY table_name, index_name, non_unique
 ORDER BY table_name, index_name;

SELECT 'media_person' AS t, count(*) AS c FROM media_person
UNION ALL SELECT 'media_video_actor',    count(*) FROM media_video_actor
UNION ALL SELECT 'media_album_person',   count(*) FROM media_album_person
UNION ALL SELECT 'media_video_actor人物行', count(*) FROM media_video_actor WHERE person_id IS NOT NULL
UNION ALL SELECT 'media_tag子标签',      count(*) FROM media_tag WHERE parent_id IS NOT NULL;
