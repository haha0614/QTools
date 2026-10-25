package com.qtools.media.service;

import java.util.Map;
import com.qtools.media.domain.MediaIndexSnapshot;

/**
 * 媒体索引聚合 服务层
 *
 * @author qtools
 */
public interface IMediaIndexService
{
    /**
     * 取索引快照（缓存 60s，含已序列化字节体与 ETag）
     *
     * @return 快照
     */
    MediaIndexSnapshot indexSnapshot();

    /**
     * 取索引快照（同上），但可以要求服务端过滤缺失文件
     *
     * @param includeMissing true（默认，老行为）= 缺失行照旧带出，由前端自行过滤；
     *                       false = 服务端把 {@code media_photo/missing=1}、{@code media_video/missing=1}
     *                       的行从 videos / albums[].photos 里滤掉
     * @return 快照（两种取值各自缓存，ETag 不同）
     */
    MediaIndexSnapshot indexSnapshot(boolean includeMissing);

    /**
     * 单片详情（含 tags/actors）
     *
     * @param id 视频 id
     * @return 与 index.videos[] 同构的节点
     */
    Map<String, Object> selectVideoDetail(String id);

    /**
     * 单册详情（含 photos，seq 升序）
     *
     * @param id 相册 id
     * @return 与 index.albums[] 同构的节点
     */
    Map<String, Object> selectAlbumDetail(String id);

    /**
     * 单册详情（同上），可要求服务端过滤缺失的图片行
     *
     * @param id             相册 id
     * @param includeMissing true（默认）= 带出缺失行；false = 只给文件还在的图片
     * @return 与 index.albums[] 同构的节点
     */
    Map<String, Object> selectAlbumDetail(String id, boolean includeMissing);

    /**
     * 丢弃索引快照缓存
     *
     * <p>管理端改了 hidden / 人物归属或跑完扫描后调用：索引本身按 {@code hidden = 0} 过滤，
     * 若不清缓存，用户端最多 60s 内仍能看到刚隐藏的对象。</p>
     */
    void invalidateSnapshot();
}
