-- 媒体站并入 QTools · 数据表（M1）
-- 库：qtools   字符集：utf8mb4
-- 约定：id 尽量沿用现有 index.json 的稳定 id（video: sha1(rel)[:12]；album: a001…）
--
-- 警告：执行时必须指定客户端字符集，否则中文注释会被按 GBK 解释后写进数据字典（变乱码）：
--     mysql -uroot --default-character-set=utf8mb4 qtools -e "source media_schema.sql"
--   正确校验：  select table_name, table_comment from information_schema.tables
--               where table_schema='qtools' and table_name like 'media\_%';

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS media_person (
  id          BIGINT       NOT NULL AUTO_INCREMENT             COMMENT '主键',
  name        VARCHAR(120) NOT NULL                            COMMENT '人物名（与站内分组一致，如 念念 / Machi马吉 / 未归类）',
  sort        INT          NOT NULL DEFAULT 0                  COMMENT '排序值，越小越靠前',
  hidden      TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '是否隐藏：0 显示 / 1 隐藏',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_person_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-人物';

CREATE TABLE IF NOT EXISTS media_album (
  id          VARCHAR(16)   NOT NULL                           COMMENT '相册 id（沿用原站 a001…a156）',
  person_id   BIGINT        NULL                               COMMENT '所属人物，关联 media_person.id',
  title       VARCHAR(300)  NOT NULL                           COMMENT '相册标题（取自目录名/资料名）',
  dir         VARCHAR(600)  NOT NULL                           COMMENT '相册物理目录（相对媒体根；同一目录可对应多本相册，如念念 144 套）',
  photo_count INT           NOT NULL DEFAULT 0                 COMMENT '图片数量',
  bytes       BIGINT        NOT NULL DEFAULT 0                 COMMENT '图片总字节数',
  cover_path  VARCHAR(500)  NULL                               COMMENT '封面路径（相对站根，如 thumbs/p/a001/0000.jpg）',
  note        VARCHAR(1000) NULL                               COMMENT '备注',
  sort        INT           NOT NULL DEFAULT 0                 COMMENT '排序值，越小越靠前',
  hidden      TINYINT(1)    NOT NULL DEFAULT 0                 COMMENT '是否隐藏：0 显示 / 1 隐藏',
  mtime       DATETIME      NULL                               COMMENT '目录最后修改时间',
  PRIMARY KEY (id),
  KEY idx_album_dir (dir(200)),
  KEY idx_album_person (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-相册';

CREATE TABLE IF NOT EXISTS media_photo (
  id         BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  album_id   VARCHAR(16)  NOT NULL                             COMMENT '所属相册 id',
  seq        INT          NOT NULL                             COMMENT '册内序号（从 0 开始，对应原站 i）',
  rel_path   VARCHAR(700) NOT NULL                             COMMENT '图片路径（相对媒体根）',
  bytes      BIGINT       NOT NULL DEFAULT 0                   COMMENT '文件字节数（源索引未提供时为 0）',
  w          INT          NULL                                 COMMENT '宽度像素',
  h          INT          NULL                                 COMMENT '高度像素',
  is_big     TINYINT(1)   NOT NULL DEFAULT 0                   COMMENT '是否大图：1 需生成 preview 大图',
  hidden     TINYINT(1)   NOT NULL DEFAULT 0                   COMMENT '是否隐藏：0 显示 / 1 隐藏',
  PRIMARY KEY (id),
  UNIQUE KEY uk_photo_album_seq (album_id, seq),
  KEY idx_photo_album (album_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-图片';

CREATE TABLE IF NOT EXISTS media_video (
  id          VARCHAR(16)   NOT NULL                           COMMENT '视频 id（sha1(rel_path) 前 12 位，沿用原站）',
  folder      VARCHAR(120)  NOT NULL                           COMMENT '一级目录（分类，如 写真 / ASMR / 跳舞）',
  rel_path    VARCHAR(700)  NOT NULL                           COMMENT '视频路径（相对媒体根）',
  title       VARCHAR(300)  NOT NULL                           COMMENT '标题',
  size        BIGINT        NOT NULL DEFAULT 0                 COMMENT '文件字节数',
  dur         DECIMAL(10,2) NULL                               COMMENT '时长（秒，保留 2 位）',
  w           INT           NULL                               COMMENT '视频宽度像素',
  h           INT           NULL                               COMMENT '视频高度像素',
  vcodec      VARCHAR(32)   NULL                               COMMENT '视频编码（h264 / vp9 / av1 …）',
  fps         DECIMAL(8,3)  NULL                               COMMENT '平均帧率（帧/秒，P5.16 帧级 seek 用；NULL = 未探测）',
  browser_ok  TINYINT(1)    NOT NULL DEFAULT 1                 COMMENT '浏览器可否直接播放：1 可以 / 0 需转码或下载',
  poster_path VARCHAR(500)  NULL                               COMMENT '封面路径（相对站根，thumbs/v/<id>.jpg）',
  doc_path    VARCHAR(700)  NULL                               COMMENT '同名说明文件路径（含【概要】结构化标签）',
  sub_path    VARCHAR(700)  NULL                               COMMENT '字幕文件路径（.srt / .vtt）',
  hidden      TINYINT(1)    NOT NULL DEFAULT 0                 COMMENT '是否隐藏：0 显示 / 1 隐藏（软删后置 1）',
  mtime       DATETIME      NULL                               COMMENT '文件最后修改时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_video_rel (rel_path(255)),
  KEY idx_video_folder (folder),
  KEY idx_video_hidden (hidden)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-视频';

CREATE TABLE IF NOT EXISTS media_video_tag (
  video_id VARCHAR(16)  NOT NULL                              COMMENT '视频 id，关联 media_video.id',
  facet    VARCHAR(32)  NOT NULL                              COMMENT '标签维度（题材 / 风格 / 场景 / 服装 …）',
  name     VARCHAR(120) NOT NULL                              COMMENT '标签值',
  KEY idx_tag_facet_name (facet, name),
  KEY idx_tag_video (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-视频标签';

CREATE TABLE IF NOT EXISTS media_video_actor (
  video_id VARCHAR(16)  NOT NULL                              COMMENT '视频 id，关联 media_video.id',
  name     VARCHAR(120) NOT NULL                              COMMENT '演员 / 人物名',
  KEY idx_actor_name (name),
  KEY idx_actor_video (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-视频演员';

CREATE TABLE IF NOT EXISTS media_owner (
  id           BIGINT      NOT NULL AUTO_INCREMENT             COMMENT '主键',
  owner_key    VARCHAR(64) NOT NULL                            COMMENT '匿名设备标识（cookie ms_owner / 请求头 X-Ms-Owner）',
  display_name VARCHAR(64) NULL                                COMMENT '昵称',
  avatar       MEDIUMTEXT  NULL                                COMMENT '头像（data:image/...;base64 或 URL，上限 512KB）',
  user_id      BIGINT      NULL                                COMMENT '绑定的用户端账号 app_user.id（登录后认领，未登录为 NULL）',
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '首次访问时间',
  last_seen    DATETIME    NULL                                COMMENT '最近访问时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_owner_key (owner_key),
  KEY idx_owner_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-所有者(匿名设备/账号)';

CREATE TABLE IF NOT EXISTS media_favorite (
  id         BIGINT      NOT NULL AUTO_INCREMENT               COMMENT '主键',
  owner_key  VARCHAR(64) NOT NULL                              COMMENT '归属设备/账号标识',
  kind       VARCHAR(10) NOT NULL                              COMMENT '类型：video 视频 / photo 图片',
  target_id  VARCHAR(32) NULL                                  COMMENT 'video 类型时填 media_video.id',
  album_id   VARCHAR(16) NULL                                  COMMENT 'photo 类型时填相册 id',
  seq        INT         NULL                                  COMMENT 'photo 类型时填册内序号',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '收藏时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fav (owner_key, kind, target_id, album_id, seq),
  KEY idx_fav_owner (owner_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-收藏';

CREATE TABLE IF NOT EXISTS media_recent (
  id         BIGINT        NOT NULL AUTO_INCREMENT             COMMENT '主键',
  owner_key  VARCHAR(64)   NOT NULL                            COMMENT '归属设备/账号标识',
  kind       VARCHAR(10)   NOT NULL                            COMMENT '类型：video 视频 / photo 图片',
  target_id  VARCHAR(32)   NULL                                COMMENT 'video 类型时填 media_video.id',
  album_id   VARCHAR(16)   NULL                                COMMENT 'photo 类型时填相册 id',
  seq        INT           NULL                                COMMENT 'photo 类型时填册内序号',
  pos        DECIMAL(10,2) NULL                                COMMENT '观看进度（秒，视频续播用）',
  dur        DECIMAL(10,2) NULL                                COMMENT '视频总时长（秒）',
  updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近浏览时间（每次写入刷新）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_recent (owner_key, kind, target_id, album_id, seq),
  KEY idx_recent_owner (owner_key, kind, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-最近浏览';

CREATE TABLE IF NOT EXISTS media_scan_job (
  id          BIGINT      NOT NULL AUTO_INCREMENT              COMMENT '主键',
  type        VARCHAR(16) NOT NULL                             COMMENT '任务类型：full 全量重建索引 / thumb 缩略图',
  status      VARCHAR(16) NOT NULL DEFAULT 'running'            COMMENT '状态：running 运行中 / done 完成 / failed 失败',
  total       INT         NOT NULL DEFAULT 0                   COMMENT '计划处理总数',
  done        INT         NOT NULL DEFAULT 0                   COMMENT '已处理数量',
  started_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP   COMMENT '开始时间',
  finished_at DATETIME    NULL                                 COMMENT '结束时间',
  log         TEXT        NULL                                 COMMENT '执行日志（尾部若干行）',
  PRIMARY KEY (id),
  KEY idx_scan_status (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体-扫描任务';
