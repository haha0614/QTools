package com.qtools.media.service;

import java.util.List;
import java.util.Map;
import com.qtools.media.domain.MediaVideoMarker;
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

    /**
     * 标签改名 / 改色（管理端 {@code PATCH /api/media/admin/tags/{id}}）
     *
     * <p>{@code name} 为空 = 不改名；{@code colorPresent=false} = 不改色，
     * {@code colorPresent=true} 且 color 为 null = <b>清空颜色</b>。改名会同步刷新
     * {@code media_video_tag.name} 那一列冗余名，否则用户端索引里还是旧名字。</p>
     *
     * @return 改完的 {@code {id,name,color,useCount}}
     */
    Map<String, Object> updateTag(Long id, String name, String color, boolean colorPresent);

    /** 单条元数据 {@code {rating,tags:[{id,name,color}],markers:[{id,pos,label,color}]}} */
    Map<String, Object> videoMeta(String videoId);

    /** 打补丁：全量 / 增量两种写法见 {@link MediaMetaPatch}；返回补丁后的元数据 */
    Map<String, Object> updateVideoMeta(String videoId, MediaMetaPatch patch);

    /**
     * 新增<b>单个</b>标记（管理端 {@code POST /api/media/admin/video/{id}/markers}）
     *
     * <p>与 {@link #updateVideoMeta} 的 {@code markers} 全量替换相反，这里只追加一条，
     * 前端「此刻打个点」不必回传整张表、也不会覆盖别人刚打的点。
     * pos 必须 {@code 0 <= pos <= dur}（dur 为 NULL 时跳过上限），单视频标记总数上限同全量接口。</p>
     *
     * @return 新建的 {@code {id,pos,label,color}}
     */
    Map<String, Object> addMarker(String videoId, MediaVideoMarker marker);

    /** 批量补丁，返回 {@code {updated,...}}（updated = 命中并处理到的现存视频条数） */
    Map<String, Object> batchMeta(MediaMetaPatch patch);
}
