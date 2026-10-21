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
 * @author qtools
 */
public interface MediaFavoriteMapper
{
    /** 精确匹配不存在时才插入；返回 1=新插入，0=已存在 */
    int insertIfAbsent(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq);

    /** 已存在则只把时间戳刷新为当前时间（置顶）；返回受影响行数 */
    int touch(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq);

    /** 精确匹配删除（取消收藏，幂等） */
    int deleteExact(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq);

    /** 收藏列表，新→旧，最多 limit 条 */
    List<MediaUserItem> selectItemList(@Param("ownerKey") String ownerKey, @Param("limit") int limit);

    /** 收藏条数；kind 为 null 时统计全部 */
    int countByOwner(@Param("ownerKey") String ownerKey, @Param("kind") String kind);

    /** 只保留最新 keep 条（收藏不设上限，这里防止无限增长） */
    int prune(@Param("ownerKey") String ownerKey, @Param("keep") int keep);
}
