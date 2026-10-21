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
     * 丢弃索引快照缓存
     *
     * <p>管理端改了 hidden / 人物归属或跑完扫描后调用：索引本身按 {@code hidden = 0} 过滤，
     * 若不清缓存，用户端最多 60s 内仍能看到刚隐藏的对象。</p>
     */
    void invalidateSnapshot();
}
