use qtools;
-- 2026-11-19 video_ratios
-- 为什么：侧车（resign-sidecar）重签成功后按段跑 ffprobe，把每段的宽高比写回这一列；
--         详情接口 /api/tool/web/post/{id} 会 select 它（ToolWebPostMapper.xml:126 video_ratios as videoRatios）。
-- 缺失时：详情接口直接 500（Unknown column 'video_ratios'）。
-- 格式：JSON 数组字符串，例如 ["1280/720"] 或 ["720/1280","720/960"]。

set @ddl := (
  select if(count(*) = 0,
            'alter table tool_web_post add column video_ratios text null after video_urls',
            'do 0')
    from information_schema.columns
   where table_schema = 'qtools' and table_name = 'tool_web_post' and column_name = 'video_ratios'
);
prepare s from @ddl; execute s; deallocate prepare s;
