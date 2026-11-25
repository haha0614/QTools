-- 2026-11-25 published_ts 生成列改成「无年份就给 NULL」
-- 症状：`alter table tool_web_post add column ...` 直接失败
--   ERROR 1411 (HY000): Incorrect datetime value: '5-25' for function str_to_date
-- 根因：published_ts 是 STORED 生成列 str_to_date(...)，而归档抓取故意把「6 月 7 日」（无年份）写进 published_at
--   （crawl-archives.mjs 注释：硬推年份会造假数据）。重建表时 MySQL 用严格模式重算生成列 → 整条 DDL 失败；
--   ALGORITHM=INSTANT 也不可用（表上有 ft_web_text 全文索引，InnoDB 报 ERROR 1846）。
-- 修法：表达式加 case，只有含「年」才解析 → 无年份仍为 NULL（与设计一致：排在有日期的内容之后），
--   任何未来重建表都不再失败。值不变（真日期不变、原来的 NULL 还是 NULL），索引 idx_web_post_ts 由 MODIFY 保留。
-- 幂等：先查 information_schema，表达式里已经有 case 就跳过 —— 这条 DDL 会重建整表（本机实测约 30 分钟，
--   因为要重键全文索引），千万别重复跑。
use qtools;

set @needs := (select count(*) from information_schema.columns
               where table_schema = 'qtools' and table_name = 'tool_web_post'
                 and column_name = 'published_ts' and generation_expression not like '%case%');
set @s := if(@needs = 0, 'select ''published_ts 已是容错表达式，跳过''',
  'alter table tool_web_post modify column published_ts date generated always as (
     case when published_at like ''%年%''
          then str_to_date(replace(replace(replace(published_at, '' 年 '', ''-''), '' 月 '', ''-''), '' 日'', ''''), ''%Y-%m-%d'')
     end
   ) stored');
prepare st from @s; execute st; deallocate prepare st;
