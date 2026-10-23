package com.qtools.media.service;

import java.util.List;
import java.util.Map;
import com.qtools.media.domain.dto.MediaMetaPatch;

/**
 * 媒体库组织能力 服务层（M3-A）：标签字典 / 评分 / 时间点标记
 *
 * <p>三个属性都只由管理端写入，用户端通过 {@code /api/media/index} 与
 * {@code /api/media/video/{id}} 只读消费。所有写方法都带 {@code @Transactional}：
 * 一次补丁要么全部生效，要么全部回滚（例如「加了标签但标记越界」不会留下半个结果）。</p>
 *
 * @author qtools
 */
public interface IMediaOrganizeService
{
    /** 标签字典全量（含 use_count） */
    List<Map<String, Object>> tagList();

    /** 新建标签；重名直接复用（颜色不改），返回 {@code {id,name,color,useCount,created}} */
    Map<String, Object> createTag(String name, String color);

    /** 删除字典项并清掉全部关联，返回 {@code {deleted,unlinked}} */
    Map<String, Object> deleteTag(Long id);

    /** 单条元数据 {@code {rating,tags:[{id,name,color}],markers:[{id,pos,label,color}]}} */
    Map<String, Object> videoMeta(String videoId);

    /** 打补丁：全量 / 增量两种写法见 {@link MediaMetaPatch}；返回补丁后的元数据 */
    Map<String, Object> updateVideoMeta(String videoId, MediaMetaPatch patch);

    /** 批量补丁，返回 {@code {updated,...}}（updated = 命中并处理到的现存视频条数） */
    Map<String, Object> batchMeta(MediaMetaPatch patch);
}
