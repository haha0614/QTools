-- ============================================================================
-- P6.27  素人兜底：不允许「未归类」
-- 文件：doc/media_person_fallback.sql
--
-- 问题：视频/相册没有人物归属时，站端只是把卡片文案兜成「素人」，
-- 数据里并没有这条关系。结果：
--   · 素人人物页只有「显式挂过素人」的 1 条，视频库却有 91 条写着素人；
--   · 相册列表里出现「未归类」分组。
--
-- 语义（用户确认）：所有没有人物归属的资源，一律归到「素人」。
--   1) 没有任何演员的视频 → 挂 素人；
--   2) person_id 为空的相册 → person_id = 素人，并补 media_album_person 关系。
--
-- 性质：幂等（重复执行不会重复插关系）。
-- 回滚：无法逐条区分"原本就属于素人"与"本脚本补的"，回滚需按时间窗人工处理。
-- ============================================================================
SET NAMES utf8mb4;

-- 0) 确保「素人」这个人存在
INSERT INTO media_person (name, hidden)
SELECT '素人', 0
WHERE NOT EXISTS (SELECT 1 FROM media_person WHERE name = '素人');

SET @su := (SELECT id FROM media_person WHERE name = '素人' LIMIT 1);

-- 1) 没有任何演员的视频 → 素人
INSERT INTO media_video_actor (video_id, name, person_id, role)
SELECT v.id, '素人', @su, NULL
FROM media_video v
WHERE NOT EXISTS (SELECT 1 FROM media_video_actor a WHERE a.video_id = v.id);

-- 2) 没有人物归属的相册 → 素人
UPDATE media_album SET person_id = @su WHERE person_id IS NULL;

-- 3) 关系表补齐（已有关系不重复插）
INSERT IGNORE INTO media_album_person (album_id, person_id)
SELECT a.id, @su FROM media_album a WHERE a.person_id = @su;

SELECT '0-1 剩余未归类视频' AS step, COUNT(*) AS v
  FROM media_video v WHERE NOT EXISTS (SELECT 1 FROM media_video_actor a WHERE a.video_id = v.id)
UNION ALL
SELECT '0-2 剩余未归类相册', COUNT(*) FROM media_album WHERE person_id IS NULL
UNION ALL
SELECT '0-3 素人名下视频', COUNT(*) FROM media_video_actor WHERE person_id = @su
UNION ALL
SELECT '0-4 素人名下相册', COUNT(*) FROM media_album WHERE person_id = @su;
