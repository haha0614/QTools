-- =====================================================================
-- 媒体库重复文件别名（M1）：同一内容出现在多条路径时，把「非主文件」沉淀到别名表
--
-- 背景：media_video.content_hash 有唯一键（uk_media_video_hash），media_photo 同一
--       内容也可以合法出现在多个相册；因此「同 hash 多路径」无法进主表。以前扫描器
--       对第二种情况只打印 DUP 并丢掉，磁盘上的副本等于没有记录。本文件给它一张表。
--
-- 约定：
--   * 主文件仍留在 media_video / media_photo，别名表只存其余路径（先入库者为主）；
--   * 本期后端**不读**别名表（只沉淀数据），未来做「同一内容的多份副本」/去重清理时再读；
--   * 纯新增表，不动任何既有表与列；可重复执行（CREATE TABLE IF NOT EXISTS）。
--
-- 幂等：反复执行不报错、不会清数据。
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_dup_alias.sql"
--   校验：
--     select table_name, table_comment from information_schema.tables
--      where table_schema='qtools' and table_name in ('media_video_alias','media_photo_alias');
--
-- 适用：MySQL 8.0（行格式 DYNAMIC；UNIQUE(content_hash, rel_path) 需 16*4+700*4=2864
--       字节，未超 3072 上限，因此不需要前缀索引）
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1) 视频别名：同 hash 的另一条路径
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS media_video_alias (
  id           BIGINT       NOT NULL AUTO_INCREMENT             COMMENT '主键',
  content_hash CHAR(16)     NOT NULL                            COMMENT '内容哈希(oshash)：与 media_video.content_hash 同一算法',
  rel_path     VARCHAR(700) NOT NULL                            COMMENT '别名文件的相对路径（相对所属库根）',
  root_id      INT          NULL DEFAULT NULL                   COMMENT '所属库根 media_root.id，NULL=默认根（配置 rootPath）',
  size         BIGINT       NOT NULL DEFAULT 0                  COMMENT '文件字节数',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '首次登记时间（重扫不刷新）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_video_alias_hash_rel (content_hash, rel_path),
  KEY idx_video_alias_root (root_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-视频重复文件别名（同 hash 多路径，主文件在 media_video）';

-- ---------------------------------------------------------------------
-- 2) 图片别名：与视频别名同构（列、键、语义一一对应）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS media_photo_alias (
  id           BIGINT       NOT NULL AUTO_INCREMENT             COMMENT '主键',
  content_hash CHAR(16)     NOT NULL                            COMMENT '内容哈希(oshash)：与 media_photo.content_hash 同一算法',
  rel_path     VARCHAR(700) NOT NULL                            COMMENT '别名文件的相对路径（相对所属库根）',
  root_id      INT          NULL DEFAULT NULL                   COMMENT '所属库根 media_root.id，NULL=默认根（配置 rootPath）',
  size         BIGINT       NOT NULL DEFAULT 0                  COMMENT '文件字节数',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '首次登记时间（重扫不刷新）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_photo_alias_hash_rel (content_hash, rel_path),
  KEY idx_photo_alias_root (root_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-图片重复文件别名（同 hash 多路径，主文件在 media_photo）';

-- ---------------------------------------------------------------------
-- 3) 自检：应看到两张表
-- ---------------------------------------------------------------------
SELECT table_name, table_rows, table_comment
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('media_video_alias', 'media_photo_alias')
 ORDER BY table_name;
