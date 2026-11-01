-- P6.86：相册可由人工改名，且扫描不得覆盖
-- 为什么需要：scan-media.js 的相册 upsert 是 ON DUPLICATE KEY UPDATE title=n.title，
-- 即每次重扫都用「目录名」把标题刷回去 —— 人工改的名字活不过一次扫描。
-- title_pinned=1 表示"这是人定的名字"，扫描端改写成 IF(title_pinned=1, title, n.title)。
alter table media_album add column title_pinned tinyint(1) not null default 0 comment '标题由人工设定，扫描不得覆盖';
