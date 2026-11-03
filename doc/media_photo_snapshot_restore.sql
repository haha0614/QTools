-- 媒体照片的「动数据前快照 / 动完还原」模板（P6.257）
--
-- 为什么单独一个文件：验证「移出相册 / 移入另一册 / 改分级 / 册内排序」这类操作时，
-- 手写 UPDATE 还原既慢又容易漏字段 —— 我实际就漏过 seq，导致一张图从第 1 张跑到第 13 页。
--
-- 用法：
--   1) 动之前：把 <id> 换成目标照片 id，执行【快照】，把结果贴到随手记里；
--   2) 动之后：核对变化是否符合预期（尤其 seq / album_id / album_pinned）；
--   3) 需要还原：用快照里的值填【还原模板】执行。
--
-- 涉及的 6 个可变字段（缺一不可）：
--   album_id      归属册（NULL = 未归类）
--   seq           册内序号（1 基显示 = seq + 1；移入另一册默认会追加到末尾）
--   album_pinned  人工归属标记（移出/移入都会置 1，重扫不会按目录拽回）
--   content_rating 单图分级（NULL = 继承相册）
--   hidden        隐藏标记
--   review_state  审核状态（pending / approved / rejected）

-- ---------------------------------------------------------------- 快照
SELECT id, album_id, seq, album_pinned, content_rating, hidden, review_state
  FROM media_photo
 WHERE id = <id>;

-- ---------------------------------------------------------------- 还原模板
-- 把上面快照的值原样填回；注意 NULL 要写 NULL，不要写空字符串：
-- UPDATE media_photo
--    SET album_id = '<snap.album_id>',
--        seq = <snap.seq>,
--        album_pinned = <snap.album_pinned>,
--        content_rating = <snap.content_rating 或 NULL>,
--        hidden = <snap.hidden>,
--        review_state = '<snap.review_state>'
--  WHERE id = <id>;

-- ---------------------------------------------------------------- 核对一致性
-- 还原后跑这三条，数字应与动手前一致：
SELECT album_id, COUNT(*) AS photos FROM media_photo GROUP BY album_id ORDER BY photos DESC LIMIT 5;
SELECT COUNT(*) AS uncategorized FROM media_photo WHERE album_id IS NULL;
SELECT COUNT(*) AS pinned FROM media_photo WHERE album_pinned = 1;

-- ---------------------------------------------------------------- 常见场景的附加核对
-- 1) 移出相册（未归类）：album_id 应变 NULL、album_pinned 变 1、seq 不变
-- 2) 移入另一册 + 追加：seq 应等于该册原最大值 + 1
-- 3) 移入另一册 + 保留原序号：seq 应等于原 seq；被占用时顺延到末尾
SELECT id, seq FROM media_photo WHERE album_id = '<target>' ORDER BY seq;

-- 4) 册内排序（保存后）：该页 seq 应是 startSeq, startSeq+1, … 连续
SELECT id, seq FROM media_photo WHERE album_id = '<album>' ORDER BY seq LIMIT 20;

-- ---------------------------------------------------------------- 停泊位提醒
-- seq >= 1000000 是「停泊位」（scan-media.js 的 PARK_BASE）：文件不在磁盘上时
-- 行被挪到这里，把真实序号让出来。还原时若看到这种值，别当成脏数据。
SELECT COUNT(*) AS parked FROM media_photo WHERE seq >= 1000000;
