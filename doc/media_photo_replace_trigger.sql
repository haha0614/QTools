-- ============================================================================
-- P6.39  同名文件被替换 → 整册退回待审核
-- 文件：doc/media_photo_replace_trigger.sql
--
-- 补 P6.37 漏掉的情况：不是「多了一张图」，而是**同一个 seq 的文件被换成了另一个文件**
-- （张数不变，导入只走 UPDATE 分支，不触发 AFTER INSERT）。
-- 这种情况同样等于「库里有未审的新内容」，必须整册退回待审核。
--
-- 判定：bytes 或 rel_path 发生变化。不用 content_hash，因为补哈希任务（--backfill-hash）
-- 会把 NULL 填成值，那是"补登记"不是"内容变了"，误触会把全库打回待审。
-- 未变化的行（重扫时写入相同值）不会命中 WHERE，所以普通重扫不会动审核状态。
-- ============================================================================
SET NAMES utf8mb4;

DROP TRIGGER IF EXISTS trg_media_photo_after_update;

CREATE TRIGGER trg_media_photo_after_update
    AFTER UPDATE ON media_photo
    FOR EACH ROW
    UPDATE media_album
       SET review_state = 'pending'
     WHERE id = NEW.album_id
       AND review_state <> 'pending'
       AND (NOT (NEW.bytes <=> OLD.bytes) OR NOT (NEW.rel_path <=> OLD.rel_path));

SELECT TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, ACTION_TIMING
  FROM information_schema.TRIGGERS
 WHERE TRIGGER_SCHEMA = 'qtools' AND EVENT_OBJECT_TABLE = 'media_photo';
