-- ============================================================================
-- P6.35  内容审核状态（review_state）
-- 文件：doc/media_review_state.sql
--
-- 问题：media_album / media_video 的 content_rating 默认 'general'，
-- 新扫描进来的内容没人定级时，未登录访客立刻就能看到 —— 等于"先发布、后审核"。
--
-- 方案：加一个与分级正交的审核状态，站端可见 = approved 且通过分级门槛。
--   pending  = 新扫描/新导入，不展示，等审核（存量也全部置为 pending，用户要求重审）
--   approved = 通过
--   rejected = 驳回，永不展示（后台保留可恢复）
--
-- 照片不单独设状态：继承所属相册的审核状态；图片自己的分级只管"这本里哪些图能露"。
-- ============================================================================
SET NAMES utf8mb4;

ALTER TABLE media_album
    ADD COLUMN review_state VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '审核状态 pending/approved/rejected'
    AFTER content_rating;

ALTER TABLE media_video
    ADD COLUMN review_state VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '审核状态 pending/approved/rejected'
    AFTER content_rating;

-- 存量全部待审核（用户口径：全部重审）
UPDATE media_album SET review_state = 'pending';
UPDATE media_video SET review_state = 'pending';

CREATE INDEX idx_media_album_review ON media_album (review_state);
CREATE INDEX idx_media_video_review ON media_video (review_state);

SELECT 'album' AS t, review_state, COUNT(*) n FROM media_album GROUP BY review_state
UNION ALL
SELECT 'video', review_state, COUNT(*) FROM media_video GROUP BY review_state;
