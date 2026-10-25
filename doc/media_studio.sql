-- =====================================================================
-- 媒体库工作室（Studio / 制片商）字典（P5-A）
--
-- 交付内容：
--   (1) media_studio           工作室字典（name 唯一，含引用计数 use_count / hidden / sort / 头像）
--   (2) media_video_studio     视频 ↔ 工作室 多对多（PK(video_id, studio_id)）
--
-- 语义约定（与 Java 侧 MediaStudioServiceImpl 严格一致）：
--   * 「全量替换」= 先 delete 该 video_id 的全部关联行，再按提交的 studioIds 重新 insert，
--     PK(video_id, studio_id) 天然去重；INSERT 用 INSERT IGNORE 兜住重复提交
--   * media_studio.use_count 是冗余计数（引用它的 media_video_studio 行数），写入时按实际行数重算，
--     不做 +1/-1 漂移（与 media_tag / media_person 的 use_count 同一套理由）
--   * 删除工作室时若仍有视频引用：应用侧直接 400 并给出引用计数，不做级联删除
--     （一次误删会静默抹掉几百条绑定）
--   * 索引 videos[] 里的 studios 是「工作室名数组」，非空才输出，因此不需要额外冗余列
--   * 幂等：两个表都 CREATE TABLE IF NOT EXISTS；本文件可反复执行
--
-- 执行：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_studio.sql"
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1) media_studio —— 工作室字典
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS media_studio (
  id          BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '工作室 id',
  name        VARCHAR(120) NOT NULL                             COMMENT '工作室名（唯一；去首尾空格后 1-64 字符，列宽留余量）',
  alias       VARCHAR(128) NULL                                 COMMENT '别名（可空；<=128 字符）',
  note        VARCHAR(512) NULL                                 COMMENT '备注（可空；<=512 字符）',
  avatar_path VARCHAR(400) NULL                                 COMMENT '头像 / Logo 路径（可空；站内相对路径或 http(s) URL）',
  use_count   INT          NOT NULL DEFAULT 0                   COMMENT '引用次数（media_video_studio 中 studio_id = 本 id 的行数，写入时重算）',
  sort        INT          NOT NULL DEFAULT 0                   COMMENT '排序（升序在前）',
  hidden      TINYINT(1)   NOT NULL DEFAULT 0                   COMMENT '1 = 隐藏（管理端列表默认不出现）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_studio_name (name),
  KEY idx_studio_list (hidden, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-工作室（制片商）字典';

-- ---------------------------------------------------------------------
-- 2) media_video_studio —— 视频 ↔ 工作室（多对多）
--    video_id 与 media_video.id 同宽（VARCHAR(16)）；不建外键，删视频走应用侧清理
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS media_video_studio (
  video_id    VARCHAR(16) NOT NULL                              COMMENT '视频 id，关联 media_video.id',
  studio_id   BIGINT      NOT NULL                              COMMENT '工作室 id，关联 media_studio.id',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP    COMMENT '绑定时间',
  PRIMARY KEY (video_id, studio_id),
  KEY idx_video_studio_studio (studio_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-视频工作室关联';

-- ---------------------------------------------------------------------
-- 3) 自检（只读，可反复执行）
-- ---------------------------------------------------------------------
SELECT table_name, column_name, column_type, is_nullable, column_default
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name IN ('media_studio', 'media_video_studio')
 ORDER BY table_name, ordinal_position;

SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND table_name IN ('media_studio', 'media_video_studio')
 GROUP BY table_name, index_name, non_unique
 ORDER BY table_name, index_name;

SELECT 'media_studio' AS t, count(*) AS c FROM media_studio
UNION ALL SELECT 'media_video_studio', count(*) FROM media_video_studio;
