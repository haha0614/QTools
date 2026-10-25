package com.qtools.media.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
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
    /**
     * 查询全部未隐藏视频，按分类/标题升序
     *
     * @param includeMissing 默认 true（缺失行照旧带出，前端自行过滤）；false 时服务端加 missing = 0
     */
    List<MediaVideo> selectVideoList(@Param("includeMissing") Boolean includeMissing);

    /** 按 id 查询单个未隐藏视频 */
    MediaVideo selectVideoById(String id);

    /**
     * 视频内容哈希（<b>不过滤 hidden</b>），供取消收藏 / 删除最近浏览时按稳定身份匹配
     *
     * <p>删除路径要能在视频已隐藏（软删）之后仍然把收藏行按哈希认出来，因此这里不能像
     * {@link #selectVideoById} 那样过滤 hidden。哈希为 null 表示扫描脚本还没回填，
     * 调用方回退老的 target_id。详见 {@code doc/媒体库可移植性方案.md}。</p>
     */
    String selectContentHashById(@Param("id") String id);

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
