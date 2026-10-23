package com.qtools.media.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaUserItem;

/**
 * 媒体最近浏览 数据层（M2）
 *
 * <p>与收藏同理：唯一键含 NULL 列，upsert 走「精确匹配 + INSERT WHERE NOT EXISTS」。</p>
 *
 * <p><b>身份规则：hash 优先、老字段回退</b>（见 {@code doc/媒体库可移植性方案.md}）。
 * 播放进度跟着 {@code content_hash} 走 —— 视频改名/移动、相册重新排序后进度都不丢；
 * 哈希为 null（老数据未回填）时退回 {@code target_id} / {@code (album_id, seq)}。
 * 写语句都接受可空 {@code contentHash}：传 null 时行为与改动前完全一致。</p>
 *
 * <p>统计（{@code MediaStatsMapper}）仍按旧 key 归属，属于可接受的偏差。</p>
 *
 * @author qtools
 */
public interface MediaRecentMapper
{
    /** 精确匹配不存在时才插入；返回 1=新插入，0=已存在 */
    int insertIfAbsent(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq,
            @Param("contentHash") String contentHash, @Param("pos") Double pos, @Param("dur") Double dur);

    /** 已存在则刷新续播位置与更新时间（置顶），并把空的 content_hash 补齐 */
    int touch(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq,
            @Param("contentHash") String contentHash, @Param("pos") Double pos, @Param("dur") Double dur);

    /** 精确匹配删除；有 hash 时按 hash 也匹配，避免改名/重排后删不掉 */
    int deleteExact(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq,
            @Param("contentHash") String contentHash);

    /** 某类最近浏览，新→旧，最多 limit 条；targetId/albumId/seq 取 join 到的当前值 */
    List<MediaUserItem> selectItemList(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("limit") int limit);

    /** 条数 */
    int countByOwner(@Param("ownerKey") String ownerKey, @Param("kind") String kind);

    /** 每类只保留最新 keep 条（按 updated_at 倒序） */
    int trim(@Param("ownerKey") String ownerKey, @Param("kind") String kind, @Param("keep") int keep);
}
