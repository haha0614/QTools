-- ----------------------------
-- QTools 埋点表：为访问趋势查询补充索引
-- 目标库：qtools（MySQL 8.0）
--
-- 背景：数据看板要按天统计 tool_visit（where create_time >= ...、group by 日期），
--       tool_visit 原来只有 idx_tool_visit_tool_id（按工具查），没有时间维度的索引。
--
-- 幂等：MySQL 8.0 不支持 create index if not exists，
--       这里先查 information_schema.statistics，只有不存在时才执行 alter。
--
-- 执行方式：
--   mysql -uroot --default-character-set=utf8mb4 qtools -e "source E:/111/project/QTools/QTools/sql/qtools/tool_visit_index.sql"
-- ----------------------------

set @idx_exists := (
    select count(*)
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'tool_visit'
      and index_name = 'idx_tool_visit_create_time'
);

set @ddl := if(@idx_exists = 0,
    'alter table tool_visit add index idx_tool_visit_create_time (create_time)',
    'select ''idx_tool_visit_create_time already exists'' as msg');

prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

-- 自检
-- show index from tool_visit;
