-- 66-tool-watermark.sql —— 工具站注册「抖音去水印」
-- 幂等：以主键 id=9 为幂等键，重复执行只刷新该行，不新增数据
-- 依赖：12-tool-tool_init.sql 必须先执行（tool_info 由它建表）
-- 注：tool_info 当前没有 category 列，前端用 CATEGORY_BY_ID 硬映射（id=9 → media）

insert into tool_info (id, tool_name, tool_desc, tool_route, icon, sort, status, create_time, update_time) values
  (9, '抖音去水印', '粘贴抖音分享链接，取回无水印原片直链', '/tool/watermark', 'Download', 9, '0', sysdate(), sysdate())
on duplicate key update
  tool_name   = values(tool_name),
  tool_desc   = values(tool_desc),
  tool_route  = values(tool_route),
  icon        = values(icon),
  sort        = values(sort),
  status      = values(status),
  update_time = sysdate();
