package com.qtools.media.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 内容分级（P5.99）的读写查询
 *
 * <p>两张表两件事，刻意放在同一个 mapper：{@code media_video/content_rating}、
 * {@code media_album/content_rating} 是<b>被同样的策略约束</b>的一对列，
 * {@code media_rating_policy} 又是它们共用的开关。拆成两个 mapper 只会让
 * 「批量改分级」和「读策略」分散到两处，而这两件事永远要一起改。</p>
 *
 * <p>未登录可见性的<b>判定</b>不在这里：它统一在 {@code IMediaGuestAccessService}
 * （{@code MediaGuestMapper} 负责点查「这个资源属于哪一档」）。本接口只答两件事：
 * 策略是什么、把分级写进去。</p>
 *
 * @author qtools
 */
public interface MediaRatingMapper
{
    /** 策略全量（{@code [{rating, guestVisible, note}]}，按值域顺序在服务层排） */
    List<Map<String, Object>> selectPolicyList();

    /** 改一档策略；返回受影响行数（0 = 库里有行但值没变，或这一档还没有行） */
    int updatePolicy(@Param("rating") String rating, @Param("guestVisible") Integer guestVisible);

    /** 策略行不存在时补一行（预置三行由迁移完成，这里兜住「迁移跑过但被删过」的库） */
    int insertPolicy(@Param("rating") String rating, @Param("guestVisible") Integer guestVisible,
            @Param("note") String note);

    /** 单条视频改分级 */
    int updateVideoRating(@Param("id") String id, @Param("rating") String rating);

    /** 单本相册改分级 */
    int updateAlbumRating(@Param("albumId") String albumId, @Param("rating") String rating);

    /** 批量视频改分级（{@code ids} 为空时调用方不该进来，SQL 里仍带 in 以防误用） */
    int updateVideosRating(@Param("ids") List<String> ids, @Param("rating") String rating);

    /** 批量相册改分级 */
    int updateAlbumsRating(@Param("ids") List<String> ids, @Param("rating") String rating);

    /** 这批 id 里真实存在的视频数（批量接口据此区分「不存在」与「值没变」） */
    long countVideosByIds(@Param("ids") List<String> ids);

    /** 这批 id 里真实存在的相册数 */
    long countAlbumsByIds(@Param("ids") List<String> ids);
}
