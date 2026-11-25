-- 2026-11-24 收藏即整帖归档（archive_state / archived_at / 源站指纹）
-- 为什么加：收藏后要把整帖（视频全段 + 图集 + 封面 + 正文/标签）落到本地，打开时优先本地播放；
--   源站改过才回源并在后台重下。已有 download_state 只描述「单段下载」，不表达「整帖归档」。
-- 谁在用：WebAutoImportService（收藏即入队 + 后台泵 + describe()）、WebUserItemController（收藏入队）、
--   WebPostController（/post/{id} 合并 mediaState/mediaWork）、resign-sidecar（/archive、/check）。
-- 幂等：只补缺失的列与索引；tool_web_post 已有 archive_state 就整段跳过。
--
-- 【本表不能直接 ALTER，三条实测结论（2026-11-25，本机 58,783 行 / 175 MB / 18 MB 索引）】
--   1) 生成列会拦路：tool_web_post.published_ts 是 STORED 生成列，严格模式下重建表要重算它，
--      而 published_at 里有 5,731 行是「6 月 7 日」这种**无年份**写法 →
--      ERROR 1411 (HY000): Incorrect datetime value: '5-25' for function str_to_date。
--      → 先跑 2026-11-25-published-ts-tolerant.sql（把生成列改成缺年份时留 NULL），再跑本文件。
--   2) INSTANT 一律被拒：本表既有 STORED 生成列、又有全文索引 ft_web_text，
--      哪怕先把全文索引摘掉，ADD COLUMN 仍报 ERROR 1845 (0A000):
--      ALGORITHM=INSTANT is not supported for this operation. Try ALGORITHM=COPY/INPLACE.
--      （不摘索引时报的是 ERROR 1846 "one FULLTEXT index creation at a time"）
--   3) 普通 ALTER 太慢：实测 68 分钟（全文索引逐行重键），期间站点必须停写。
-- 所以走「整表换壳」：造一张不带全文索引的空壳 → 一次性加 8 列 → 灌数据（17 秒）
--   → 在副本上建全文索引（81 秒）→ 核对行数/校验和 → 两张表换名。
--   全文索引重建很快（45~81 秒），慢的从来不是它，而是带索引的逐行 online copy。
-- 【注意】重建全文索引必须带 WITH PARSER ngram，否则中文搜不出来（match 命中数会掉到 0）。
use qtools;
set session group_concat_max_len = 1000000;

-- 已经加过就什么都不做
set @need := (select count(*) = 0 from information_schema.columns
              where table_schema = 'qtools' and table_name = 'tool_web_post' and column_name = 'archive_state');

-- 1) 干净起点（上次跑到一半的副本直接丢掉，里面没有独家数据）
set @s := if(@need, 'drop table if exists tool_web_post_swap', 'select ''skip''');
prepare st from @s; execute st; deallocate prepare st;

-- 2) 空壳：结构照抄（含生成列；生成列不会复制数据，灌数时排除）
set @s := if(@need, 'create table tool_web_post_swap like tool_web_post', 'select ''skip''');
prepare st from @s; execute st; deallocate prepare st;

-- 3) 先在副本上摘掉全文索引：带全文索引灌 58k 行会慢到不可用
set @s := if(@need, 'alter table tool_web_post_swap drop index ft_web_text', 'select ''skip''');
prepare st from @s; execute st; deallocate prepare st;

-- 4) 八个归档列 + 状态索引
set @cols := (select group_concat(concat('add column ', n, ' ', d) separator ', ') from (
  select 'archive_state' as n, "varchar(16) NULL COMMENT '归档状态：pending/running/done/partial/failed'" as d union all
  select 'archive_percent', "int NULL COMMENT '归档进度 0-100（按已下载段数算）'" union all
  select 'archive_error', "varchar(255) NULL COMMENT '归档失败原因'" union all
  select 'archived_at', "datetime NULL COMMENT '整帖落地完成时间'" union all
  select 'source_etag', "varchar(128) NULL COMMENT '源站 ETag/Last-Modified，判断源站是否改过'" union all
  select 'source_len', "bigint NULL COMMENT '源站 HTML 字节数'" union all
  select 'source_hash', "char(64) NULL COMMENT '正文容器 sha256（十六进制）'" union all
  select 'source_checked_at', "datetime NULL COMMENT '上次检查源站变更时间（12 小时内不重复检查）'"
) t);
set @s := if(@need, concat('alter table tool_web_post_swap ', @cols,
  ', add index idx_post_archive_state (archive_state, id)'), 'select ''skip''');
prepare st from @s; execute st; deallocate prepare st;

-- 5) 灌数据：列清单必须排除生成列，否则报
--    ERROR 3105 (HY000): The value specified for generated column 'published_ts' ... is not allowed
set @ins := (select group_concat(concat('`', column_name, '`') order by ordinal_position)
             from information_schema.columns
             where table_schema = 'qtools' and table_name = 'tool_web_post'
               and (generation_expression is null or generation_expression = ''));
set @s := if(@need, concat('insert into tool_web_post_swap (', @ins, ') select ', @ins, ' from tool_web_post'), 'select ''skip''');
prepare st from @s; execute st; deallocate prepare st;

-- 6) 副本建全文索引（ngram 必须带）
set @s := if(@need, 'alter table tool_web_post_swap add fulltext key ft_web_text (title, content_text) with parser ngram', 'select ''skip''');
prepare st from @s; execute st; deallocate prepare st;

-- 7) 核对：两行数字必须完全一致，再执行第 8 步
select 'old' as side, count(*) as rows, sum(id) as id_sum,
       sum(length(content_text)) as text_len, sum(crc32(coalesce(title, ''))) as title_crc
from tool_web_post
union all
select 'new', count(*), sum(id), sum(length(content_text)), sum(crc32(coalesce(title, '')))
from tool_web_post_swap;

-- 8) 换名（默认注释掉，人工核对第 7 步两行一致后再单独执行）
--    rename table tool_web_post to tool_web_post_old20261125,
--                 tool_web_post_swap to tool_web_post;
--    换名后自检： select count(*) from tool_web_post;
--                select count(*) from tool_web_post where match(title, content_text) against('心梗' in boolean mode);
--    两条都正常再删旧表： drop table tool_web_post_old20261125;
