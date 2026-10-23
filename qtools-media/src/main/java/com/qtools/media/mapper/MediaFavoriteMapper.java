package com.qtools.media.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaUserItem;

/**
 * 媒体收藏 数据层（M2）
 *
 * <p>media_favorite 的唯一键是 (owner_key,kind,target_id,album_id,seq)，而 video 行的
 * album_id/seq 为 NULL、photo 行的 target_id 为 NULL —— MySQL 唯一索引不判 NULL 重复，
 * 因此 {@code ON DUPLICATE KEY} 在这里完全失效。本层一律用「按 kind 分支的精确匹配」
 * 加 {@code INSERT ... WHERE NOT EXISTS} 保证幂等。</p>
 *
 * <p><b>身份规则：hash 优先、老字段回退</b>（见 {@code doc/媒体库可移植性方案.md}）。
 * {@code content_hash} 是内容的稳定身份：视频改名/移动、相册内插图导致 seq 后移之后，
 * 老的 {@code target_id} / {@code (album_id, seq)} 会指向别的东西乃至悬空；哈希不为 null
 * 时按哈希认行，为 null（老数据未回填）时退回老字段。因此每条写语句都允许两种匹配同时成立，
 * 并且都接受一个可空的 {@code contentHash} 参数：传 null 时行为与改动前完全一致。</p>
 *
 * <p>统计（{@code MediaStatsMapper}）仍按旧 key 归属，属于可接受的偏差。</p>
 *
 * @author qtools
 */
public interface MediaFavoriteMapper
{
    /**
     * 精确匹配不存在时才插入；返回 1=新插入，0=已存在
     *
     * @param contentHash 可空；非空时同时按它认行，命中老行则不会重复插入
     */
    int insertIfAbsent(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq,
            @Param("contentHash") String contentHash);

    /**
     * 已存在则只把时间戳刷新为当前时间（置顶），并把空的 {@code content_hash} 补齐；返回受影响行数
     *
     * <p>补齐用 {@code coalesce(新值, 旧值)}：只填空值，绝不用 null 覆盖已有的哈希。</p>
     */
    int touch(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq,
            @Param("contentHash") String contentHash);

    /** 精确匹配删除（取消收藏，幂等）；有 hash 时按 hash 也匹配，避免改名/重排后删不掉 */
    int deleteExact(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq,
            @Param("contentHash") String contentHash);

    /** 收藏列表，新→旧，最多 limit 条；targetId/albumId/seq 取 join 到的当前值 */
    List<MediaUserItem> selectItemList(@Param("ownerKey") String ownerKey, @Param("limit") int limit);

    /** 收藏条数；kind 为 null 时统计全部 */
    int countByOwner(@Param("ownerKey") String ownerKey, @Param("kind") String kind);

    /** 只保留最新 keep 条（收藏不设上限，这里防止无限增长） */
    int prune(@Param("ownerKey") String ownerKey, @Param("keep") int keep);
}
