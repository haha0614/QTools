package com.qtools.media.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaUserItem;

/**
 * 媒体最近浏览 数据层（M2）
 *
 * <p>与收藏同理：唯一键含 NULL 列，upsert 走「精确匹配 + INSERT WHERE NOT EXISTS」。</p>
 *
 * @author qtools
 */
public interface MediaRecentMapper
{
    /** 精确匹配不存在时才插入；返回 1=新插入，0=已存在 */
    int insertIfAbsent(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq,
            @Param("pos") Double pos, @Param("dur") Double dur);

    /** 已存在则刷新续播位置与更新时间（置顶） */
    int touch(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq,
            @Param("pos") Double pos, @Param("dur") Double dur);

    /** 精确匹配删除 */
    int deleteExact(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("targetId") String targetId, @Param("albumId") String albumId, @Param("seq") Integer seq);

    /** 某类最近浏览，新→旧，最多 limit 条 */
    List<MediaUserItem> selectItemList(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("limit") int limit);

    /** 条数 */
    int countByOwner(@Param("ownerKey") String ownerKey, @Param("kind") String kind);

    /** 每类只保留最新 keep 条（按 updated_at 倒序） */
    int trim(@Param("ownerKey") String ownerKey, @Param("kind") String kind, @Param("keep") int keep);
}
