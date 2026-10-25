-- =====================================================================
-- 媒体站「播放列表」media_playlist / media_playlist_item（P5-B）
--
-- 交付内容：
--   2 张表 —— 一个命名列表（可归属某台匿名设备，也可全局）+ 有序条目。
--
-- 为什么条目单独一张表而不是 media_playlist.ids JSON 列：
--   1. 顺序是播放列表的全部语义，seq 是显式列，改名 / 增删一条不必重写整个 JSON；
--   2. (playlist_id, video_id) 唯一键让「同一视频在一个列表里只出现一次」由库保证，
--      服务层的「已在列表里就 no-op」只是省一次写，不是正确性的唯一依赖；
--   3. 反转「哪些列表包含这个视频」是一次索引查找（idx_media_playlist_item_video），
--      JSON 列只能全表扫。
--
-- 为什么 PK 是 (playlist_id, seq) 而不是自增 id：
--   条目只按 (列表, 位置) 被访问，没有独立身份；用复合主键顺带保证「同一列表内 seq 不重复」。
--   代价是「删除中间一条」必须把后面的 seq 前移（服务层用「按 seq 升序逐条改小」实现，
--   目标 seq 永远小于当前 seq，因此不会撞主键）。
--
-- owner_key 语义与 media_saved_filter 完全一致（见 doc/media_saved_filter.sql）：
--   owner_key = 某设备  → 该设备私有
--   owner_key IS NULL   → 全局列表（所有访客可见，只能由管理端清理）
--   name 单列唯一会让 A 设备建了「稍后看」把 B 设备的同名列表卡死，因此唯一键是 (name, owner_key)。
--
-- video_id 类型跟随 media_video.id：VARCHAR(16)（sha1(rel_path) 前 12 位）。
-- 刻意不加外键：扫描 / 软删 / 回收站都会让条目指向一个暂时不存在的视频，
-- 那是「列表里有一条当前找不到的片子」（GET 详情里 title/dur 为 null），不该让写列表失败。
--
-- 幂等：CREATE TABLE IF NOT EXISTS，本文件可反复执行（第二遍无副作用）。
--
-- 执行：
--     mysql -uroot -p -P3307 --default-character-set=utf8mb4 qtools -e "source media_playlist.sql"
--
-- 作者：qtools
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS media_playlist (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(64)  NOT NULL                 COMMENT '名称（同一 owner 下唯一）',
    owner_key   VARCHAR(64)  NULL     DEFAULT NULL    COMMENT '归属匿名设备；NULL=全局列表',
    note        VARCHAR(255) NULL     DEFAULT NULL    COMMENT '备注（管理端可视；站端暂不写）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（条目变化也刷新）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_playlist_name_owner (name, owner_key),
    KEY idx_media_playlist_owner (owner_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '媒体站播放列表';

CREATE TABLE IF NOT EXISTS media_playlist_item (
    playlist_id BIGINT      NOT NULL                 COMMENT '所属列表 media_playlist.id',
    video_id    VARCHAR(16) NOT NULL                 COMMENT '视频 media_video.id',
    seq         INT         NOT NULL                 COMMENT '顺序（0 起，同一列表内连续）',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (playlist_id, seq),
    UNIQUE KEY uk_media_playlist_item_video (playlist_id, video_id),
    KEY idx_media_playlist_item_video (video_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '媒体站播放列表条目（有序）';

-- ---------------------------------------------------------------------
-- 自检：表结构（应能看到 uk_media_playlist_item_video 与主键 (playlist_id, seq)）
-- ---------------------------------------------------------------------
SHOW CREATE TABLE media_playlist;
SHOW CREATE TABLE media_playlist_item;

-- ---------------------------------------------------------------------
-- 自检：条数（新库应为 0）
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS playlist_rows FROM media_playlist;
SELECT COUNT(*) AS playlist_item_rows FROM media_playlist_item;
