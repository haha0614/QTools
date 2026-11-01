-- P6.77：相似图片（感知指纹）支持
-- phash = 64 位 dHash（8x8，灰度，由 thumbs/p8/<content_hash>.jpg 计算）
-- 汉明距离 <= 阈值 视为"可能是同一张图的不同编码版本"，仅供人工确认，绝不自动删除
alter table media_photo add column phash bigint null comment '64位dHash感知指纹（相似图片用）';
create index idx_photo_phash on media_photo (phash);
