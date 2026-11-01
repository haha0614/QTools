-- P6.88（阶段 1/3）：归属规则表 —— 把"目录自动建册"改成"规则显式决定 + 未命中即未归类"
--
-- 背景：扫描器现在是「目录 → 册」的隐式规则（--album-mode=dir/prefix），
-- 于是目录既当事实又当决定：删册会被重扫复活、册身份锚在 dir_key 上。
-- 新模型里扫描只产出**个体**（media_photo/media_video），归属由本表（人写的规则）决定；
-- 命中不了就 album_id = NULL（未归类，已是合法状态），进建议流。
create table if not exists media_album_rule (
  id            int auto_increment primary key,
  album_id      varchar(16)  not null comment '目标册',
  dir_pattern   varchar(600) not null comment '相对目录（/ 分隔、无首尾斜杠；空串=媒体根）',
  file_prefix   varchar(32)  null comment '可选：文件名前缀（原 --album-mode=prefix 的分组号）；NULL=同目录全部',
  priority      int          not null default 100 comment '小的优先（更具体的规则该更小）',
  enabled       tinyint(1)   not null default 1,
  note          varchar(200) null,
  created_at    datetime     not null default current_timestamp,
  unique key uk_album_rule (album_id, dir_pattern, file_prefix),
  key idx_rule_lookup (enabled, dir_pattern)
) comment '相册归属规则：扫描按它决定归属，命中不了就未归类';
