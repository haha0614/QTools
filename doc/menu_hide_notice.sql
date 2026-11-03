-- 隐藏「通知公告」菜单（P6.245）
--
-- 背景：后台导航栏的通知铃铛已移除；sys_notice 表里 0 行数据；菜单仍挂在
--       「系统管理 → 通知公告」，点进去是一张空列表 —— 死入口，容易让人以为漏了配置。
--
-- 处置：**只隐藏，不删除**。菜单、页面（views/system/notice）、后端接口与表结构全部保留，
--       将来要做「待处理事项」之类的通知聚合，直接把 visible 改回 '0' 即可复用。
--
-- 执行：mysql -h127.0.0.1 -P3307 -uroot -p qtools < doc/menu_hide_notice.sql

UPDATE sys_menu SET visible = '1' WHERE menu_id = 107;

-- 回滚（需要恢复入口时执行）
-- UPDATE sys_menu SET visible = '0' WHERE menu_id = 107;
