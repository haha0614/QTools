-- ----------------------------
-- 媒体统计（M5）：media_event 增加 progress 列（完播率口径）
-- 目标库：qtools（MySQL 8.0）   字符集：utf8mb4
--
-- 背景：完播率 = event_type='play_end' 且 progress >= 90 的次数 / play_end 次数。
--       老库的 media_event 没有该列，概览与单目标统计都依赖它，必须先补。
--
-- 语义：progress 为播放进度百分比 0-100，只有 play_end 事件有意义；
--       服务端写库前已夹取到 [0,100]，非 play_end 事件一律写 0（冗余列，便于以后直接按列聚合）。
--
-- 幂等：MySQL 8.0 不支持 add column if not exists，
--       这里先查 information_schema.columns，只有列不存在时才执行 alter。
--
-- 执行方式（必须带客户端字符集，否则中文注释会变乱码）：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source doc/media_stats_alter_progress.sql"
--
-- 自检：
--   show columns from media_event;
-- ----------------------------

set @col_exists := (
    select count(*)
    from information_schema.columns
    where table_schema = database()
      and table_name = 'media_event'
      and column_name = 'progress'
);

set @ddl := if(@col_exists = 0,
    'alter table media_event add column progress tinyint unsigned not null default 0 comment ''播放进度百分比 0-100（仅 play_end 有意义）'' after value_num',
    'select ''media_event.progress already exists'' as msg');

prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
