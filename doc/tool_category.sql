-- 工具分类 + 两个新工具（P6.262）
--
-- 背景：站点工具页原本**按工具 id 硬编码**分类（ToolsPage.vue / Home.vue 的 CATEGORY_BY_ID），
--       每加一个工具都要改前端代码。这里给 tool_info 补 category 字段，前端改为直接读它。
--
-- 执行：mysql -h127.0.0.1 -P3307 -uroot -p qtools < doc/tool_category.sql

ALTER TABLE tool_info
  ADD COLUMN category VARCHAR(32) DEFAULT NULL COMMENT '分类：text/encode/generate/security/media' AFTER icon;

UPDATE tool_info SET category = 'text'     WHERE id IN (1, 2);   -- JSON 格式化 / 文本字数统计
UPDATE tool_info SET category = 'security' WHERE id = 3;          -- 随机密码生成
UPDATE tool_info SET category = 'media'    WHERE id = 4;          -- 媒体站

-- 新增两个纯前端工具（前端页面见 QTools-C/src/views/tool/TimestampTool.vue、CodecTool.vue）
INSERT INTO tool_info (tool_name, tool_desc, tool_route, icon, category, sort, status, create_time, update_time)
VALUES ('时间戳转换', 'Unix 时间戳与日期互转，支持时区与偏移量', '/tool/timestamp', 'Clock', 'encode', 5, '0', NOW(), NOW());

INSERT INTO tool_info (tool_name, tool_desc, tool_route, icon, category, sort, status, create_time, update_time)
VALUES ('Base64 / URL 编解码', 'Base64、URL、HTML 实体互转，支持文件与图片', '/tool/codec', 'Switch', 'encode', 6, '0', NOW(), NOW());
