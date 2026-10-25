package com.qtools.media.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaStudio;

/**
 * 媒体工作室 数据层（media_studio + media_video_studio）
 *
 * <p>风格与 {@link MediaPersonMapper} 一致：写语句全部幂等（INSERT IGNORE / 覆盖式 UPDATE），
 * 由 {@code MediaStudioServiceImpl} 在同一事务里组合；「全量替换」先带 video_id 删关联再重插。</p>
 *
 * @author qtools
 */
public interface MediaStudioMapper
{
    // ------------------------------------------------------------------ 工作室字典

    /** 管理端列表：全部工作室（含 hidden），带 videoCount */
    List<MediaStudio> selectStudioList();

    /** 站端列表：只出 hidden = 0，带 videoCount */
    List<MediaStudio> selectStudioVisibleList();

    /** 按 id 查（带 videoCount） */
    MediaStudio selectStudioDetail(@Param("id") Long id);

    /** 按名称查（新建 / 改名的重名校验） */
    MediaStudio selectStudioByName(@Param("name") String name);

    /** 新建工作室；主键回填到入参对象（useGeneratedKeys） */
    int insertStudio(MediaStudio studio);

    /** 覆盖式更新（名称 / 别名 / 备注 / 头像 / 排序 / 隐藏；null = 清空该列） */
    int updateStudio(MediaStudio studio);

    int deleteStudio(@Param("id") Long id);

    /** 按关联行实际数量重算 use_count（不做 +1/-1 漂移） */
    int recomputeStudioUseCount(@Param("studioIds") List<Long> studioIds);

    /** 入参里实际存在的工作室数（未知 studioId 直接 400 用） */
    long countStudiosByIds(@Param("studioIds") List<Long> studioIds);

    /** 视频是否存在（绑定前校验） */
    long countVideoById(@Param("videoId") String videoId);

    /** 某工作室被多少个视频引用（删工作室前算引用） */
    long countVideoRefsByStudio(@Param("studioId") Long studioId);

    // ------------------------------------------------------------------ 视频 ↔ 工作室

    /** 某视频绑定的工作室（全量替换后回显用） */
    List<Map<String, Object>> selectVideoStudios(@Param("videoId") String videoId);

    /** 某视频当前绑定的工作室 id（替换前记下旧值，重算 use_count 不漏「刚被换掉」的那些） */
    List<Long> selectStudioIdsByVideoId(@Param("videoId") String videoId);

    /** 清掉某视频的全部工作室关联 */
    int deleteVideoStudios(@Param("videoId") String videoId);

    /** 绑定一个工作室到视频（studio 不存在则不插；PK(video_id, studio_id) 天然去重） */
    int insertVideoStudio(@Param("videoId") String videoId, @Param("studioId") Long studioId);

    // ------------------------------------------------------------------ 站端 / 索引

    /** 某工作室名下的视频分页（hidden = 0 的视频） */
    List<Map<String, Object>> selectStudioVideoPage(@Param("studioId") Long studioId, @Param("offset") int offset,
            @Param("size") int size);

    long countStudioVideos(@Param("studioId") Long studioId);

    /** 索引用：全部「视频 / 工作室名」对（[{videoId, name}]，hidden 的工作室不输出） */
    List<Map<String, Object>> selectVideoStudioList();

    /** 详情用：某视频的工作室名（按名称升序） */
    List<String> selectStudioNamesByVideoId(@Param("videoId") String videoId);
}
