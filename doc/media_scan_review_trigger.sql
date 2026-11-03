SET NAMES utf8mb4;
DROP TRIGGER IF EXISTS trg_media_photo_after_insert;
CREATE TRIGGER trg_media_photo_after_insert
    AFTER INSERT ON media_photo
    FOR EACH ROW
    UPDATE media_album
       SET review_state = 'pending'
     WHERE id = NEW.album_id
       AND review_state <> 'pending';
