-- 媒体站 · 事件埋点与按天汇总（媒体统计 M4）
-- 库：qtools   字符集：utf8mb4
-- 约定：媒体 id 一律不是数字主键 —— video 用 media_video.id（sha1 前 12 位），
--       photo 用 album_id:seq（如 a001:12），album 用 media_album.id，page 用站点路径。
--       因此 target_key 全是字符串，不做外键约束。
--
-- 警告：执行时必须指定客户端字符集，否则中文注释会被按 GBK 解释后写进数据字典（变乱码）：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_stats_schema.sql"
--   正确校验：  select table_name, table_comment from information_schema.tables
--               where table_schema='qtools' and table_name like 'media\_stats%';
--
-- 幂等：全部 create table if not exists，可重复执行。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS media_event (
  id          BIGINT       NOT NULL AUTO_INCREMENT             COMMENT '主键',
  event_type  VARCHAR(32)  NOT NULL                            COMMENT '事件类型：play 开始播放 / play_end 播放结束 / photo_click 图片点击 / album_view 相册浏览 / page_view 页面浏览 / dwell 停留 / fav 收藏 / unfav 取消收藏',
  target_type VARCHAR(16)  NOT NULL                            COMMENT '目标类型：video 视频 / photo 图片 / album 相册 / page 页面',
  target_key  VARCHAR(64)  NOT NULL DEFAULT ''                 COMMENT '目标标识：video=media_video.id；photo=albumId:seq；album=media_album.id；page=路径',
  owner_key   VARCHAR(64)  NULL                                COMMENT '设备/账号标识（与 media_owner.owner_key 一致；匿名上报未带时为空）',
  value_num   BIGINT       NOT NULL DEFAULT 0                  COMMENT '数值：播放时长ms / 停留时长ms / 其他数值，非数值事件为 0',
  ip          VARCHAR(64)  NULL                                COMMENT '客户端IP',
  user_agent  VARCHAR(512) NULL                                COMMENT 'User-Agent（超长按 512 截断）',
  create_time DATETIME     NULL                                COMMENT '事件时间',
  PRIMARY KEY (id),
  KEY idx_media_event_time (create_time),
  KEY idx_media_event_target (target_type, target_key, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-事件埋点';

CREATE TABLE IF NOT EXISTS media_stats_daily (
  stat_date   DATE        NOT NULL                             COMMENT '统计日期',
  event_type  VARCHAR(32) NOT NULL                             COMMENT '事件类型（取值同 media_event.event_type）',
  target_type VARCHAR(16) NOT NULL                             COMMENT '目标类型（取值同 media_event.target_type）',
  target_key  VARCHAR(64) NOT NULL DEFAULT ''                  COMMENT '目标标识（取值同 media_event.target_key）',
  cnt         BIGINT      NOT NULL DEFAULT 0                   COMMENT '事件次数',
  value_sum   BIGINT      NOT NULL DEFAULT 0                   COMMENT 'value_num 累计值（播放时长ms / 停留时长ms）',
  PRIMARY KEY (stat_date, event_type, target_type, target_key),
  KEY idx_stats_daily_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-事件按天汇总';
