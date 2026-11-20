use qtools;
-- 2026-11-20 orientation
-- 为什么：每日大赛列表的「方向」筛选与 facets 计数（selectOrientationCounts，ToolWebPostMapper.xml:290）
--         都按这一列分组；facets 是一个接口返回分类/标签/日期/方向/已入库计数的整包数据，
--         这一列缺失会让整包失败 —— 表现是分类只剩「全部/视频/图文」、已入库永远是 0/0。
-- 取值：'l' 横屏 / 'p' 竖屏 / NULL 未判定（NULL 的条目留在「全部」里）。
-- 回填：doc/watermark/backfill-orientation.mjs（优先用 video_ratios，其次图集第一张图的尺寸）。

set @ddl := (
  select if(count(*) = 0,
            'alter table tool_web_post add column orientation varchar(2) null after video_count',
            'do 0')
    from information_schema.columns
   where table_schema = 'qtools' and table_name = 'tool_web_post' and column_name = 'orientation'
);
prepare s from @ddl; execute s; deallocate prepare s;

-- 索引：facets 与列表筛选都按 (source, orientation) 分组/过滤
set @ddl2 := (
  select if(count(*) = 0,
            'create index idx_post_source_orientation on tool_web_post (source, orientation)',
            'do 0')
    from information_schema.statistics
   where table_schema = 'qtools' and table_name = 'tool_web_post' and index_name = 'idx_post_source_orientation'
);
prepare s2 from @ddl2; execute s2; deallocate prepare s2;
