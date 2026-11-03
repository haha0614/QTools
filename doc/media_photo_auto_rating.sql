-- ============================================================================
-- P6.36  自动分级结果落库（分级复核的前提）
-- 文件：doc/media_photo_auto_rating.sql
--
-- 之前模型的判定只存在于扫描过程的返回值里，关掉弹窗就没了 —— 复核必须重扫。
-- 落库之后：复核队列是持久的（换个人来审也能接着审），也方便以后调阈值回放。
--   auto_rating  模型结论 general / guidance / restricted / review
--   auto_porn    Porn+Hentai 的分数（复核界面展示用）
--   auto_at      判定时间
-- 人工定档后 auto_rating 一并改成人工档位，这张就从复核队列里消失。
-- ============================================================================
SET NAMES utf8mb4;

ALTER TABLE media_photo
    ADD COLUMN auto_rating VARCHAR(16) NULL COMMENT '模型判定 general/guidance/restricted/review' AFTER content_rating,
    ADD COLUMN auto_porn DECIMAL(5,4) NULL COMMENT 'Porn+Hentai 分数' AFTER auto_rating,
    ADD COLUMN auto_at DATETIME NULL COMMENT '判定时间' AFTER auto_porn;

CREATE INDEX idx_media_photo_auto ON media_photo (auto_rating);

SELECT 'photo auto_rating' AS t, COUNT(*) AS n FROM media_photo WHERE auto_rating IS NOT NULL;
