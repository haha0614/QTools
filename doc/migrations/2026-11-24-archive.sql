-- 2026-11-24 收藏即整帖归档（archive_state / archived_at / 源站指纹）
-- 为什么加：收藏后要把整帖（视频全段 + 图集 + 封面 + 正文/标签）落到本地，打开时优先本地播放；
--   源站改过才回源并在后台重下。已有 download_state 只描述「单段下载」，不表达「整帖归档」。
-- 谁在用：WebArchiveService（后台归档泵）、WebUserItemController（收藏入队）、
--   WebPostController（/post/{id} 回 archiveState / videoFiles）、resign-sidecar（/archive、/check）。
-- 幂等：只补缺失的列；8 列一次 rebuild（分 8 条 alter 在 6 万行表上要跑几分钟）。
use qtools;
set session group_concat_max_len = 10000;
set @cols := (select group_concat(concat('add column ', n, ' ', d) separator ', ') from (
  select 'archive_state' as n, "varchar(16) NULL COMMENT '归档状态：pending/running/done/partial/failed'" as d union all
  select 'archive_percent', "int NULL COMMENT '归档进度 0-100（按已下载段数算）'" union all
  select 'archive_error', "varchar(255) NULL COMMENT '归档失败原因'" union all
  select 'archived_at', "datetime NULL COMMENT '整帖落地完成时间'" union all
  select 'source_etag', "varchar(128) NULL COMMENT '源站 ETag/Last-Modified，判断源站是否改过'" union all
  select 'source_len', "bigint NULL COMMENT '源站 HTML 字节数'" union all
  select 'source_hash', "char(64) NULL COMMENT '正文容器 sha256（十六进制）'" union all
  select 'source_checked_at', "datetime NULL COMMENT '上次检查源站变更时间（12 小时内不重复检查）'"
) t where t.n not in (select column_name from information_schema.columns
                      where table_schema='qtools' and table_name='tool_web_post'));
set @s := if(@cols is null, 'select 1', concat('alter table tool_web_post ', @cols));
prepare st from @s; execute st; deallocate prepare st;
set @i := (select count(*) from information_schema.statistics
           where table_schema='qtools' and table_name='tool_web_post' and index_name='idx_post_archive_state');
set @s := if(@i=0, 'alter table tool_web_post add index idx_post_archive_state (archive_state, id)', 'select 1');
prepare st from @s; execute st; deallocate prepare st;
