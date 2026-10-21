package com.qtools.media.mapper;

import java.util.Date;
import java.util.List;
import com.qtools.media.domain.MediaVideo;
import com.qtools.media.domain.MediaVideoActor;
import com.qtools.media.domain.MediaVideoTag;

/**
 * 媒体视频 数据层
 *
 * <p>包名符合 framework 中 @MapperScan("com.qtools.**.mapper")，无需 @Mapper 注解。</p>
 *
 * @author qtools
 */
public interface MediaVideoMapper
{
    /** 查询全部未隐藏视频，按分类/标题升序 */
    List<MediaVideo> selectVideoList();

    /** 按 id 查询单个未隐藏视频 */
    MediaVideo selectVideoById(String id);

    /** 查询全部未隐藏视频的标签，按 video_id 排序，供调用方分组成 map */
    List<MediaVideoTag> selectTagList();

    /** 查询全部未隐藏视频的演员 */
    List<MediaVideoActor> selectActorList();

    /** 查询单个视频的标签 */
    List<MediaVideoTag> selectTagListByVideoId(String videoId);

    /** 查询单个视频的演员 */
    List<MediaVideoActor> selectActorListByVideoId(String videoId);

    /** 未隐藏视频的最大修改时间，用于生成稳定的数据版本号 */
    Date selectMaxMtime();
}
