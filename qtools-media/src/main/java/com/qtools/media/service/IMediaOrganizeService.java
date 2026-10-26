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
    /**
     * 标签字典全量（含 use_count、层级）
     *
     * <p>每个节点 {@code {id,name,parentId,color,useCount,path}}：{@code parentId} 为 null = 顶层标签，
     * {@code path} 是从顶层到自身的名字链（如 {@code 演员/日本/XX}），由本方法沿 parent 链现算。</p>
     */
    List<Map<String, Object>> tagList();

    /**
     * 某个标签的全部后代 id（<b>含自身</b>），供「选父标签 = 命中其所有后代」使用
     *
     * <p>站端筛选在前端做（索引接口没有服务端筛选参数），因此这个集合主要给管理端
     * {@code GET /admin/videos?tagId=} 用；前端也可以拿 {@link #tagList()} 的 parentId 自行展开。</p>
     *
     * @param id 标签 id；不存在抛 404
     */
    List<Long> descendantTagIds(Long id);

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
     * <p>{@code parentPresent=true} 时改父标签：{@code parentId == null} = <b>置顶</b>。
     * 自引用、成环、深度超过 20 层一律 400（MySQL 8 的 CHECK 不能跨行查表，因此约束在服务层）。</p>
     *
     * @return 改完的 {@code {id,name,parentId,color,useCount,path}}
     */
    Map<String, Object> updateTag(Long id, String name, String color, boolean colorPresent, Long parentId,
            boolean parentPresent);

    /** 单条元数据 {@code {rating,tags:[{id,name,color}],markers:[{id,pos,endPos,label,color}]}}（endPos 为 null = 单点标记） */
    Map<String, Object> videoMeta(String videoId);

    /** 打补丁：全量 / 增量两种写法见 {@link MediaMetaPatch}；返回补丁后的元数据 */
    Map<String, Object> updateVideoMeta(String videoId, MediaMetaPatch patch);

    /**
     * 单片作品简介 / 剧情梗概（P5.38）：{@code {id, synopsis}}
     *
     * <p>{@code synopsis} 为 null = 没有简介（**不是**空串：服务层把纯空白归一化成 null）。
     * 换行原样返回；视频不存在 404。读接口<b>不看编辑策略</b>：off 档站端仍要能看到已有简介。</p>
     */
    Map<String, Object> videoSynopsis(String videoId);

    /**
     * 写入作品简介（P5.38）：{@code {id, synopsis}}，返回落库后的规范化值
     *
     * <p>校验：去首尾空白后按<b>字符</b>计数（{@link String#codePointCount}，CJK / emoji 都算 1 个字），
     * 超过 8000 字符 400；纯空白（含全角空格 / 换行）视为<b>清空</b>，落库 NULL。
     * 换行<b>保留</b> —— 梗概是多行正文，前端用 {@code white-space: pre-wrap} 渲染。
     * 写完 {@code invalidateSnapshot()}：索引里已经有该片的条目（标签 / 人物 / 评分），
     * 虽然索引条目不输出 synopsis，但「同一个视频的数据在同一个快照里」这条不变量要保持。</p>
     */
    Map<String, Object> updateVideoSynopsis(String videoId, String synopsis);

    /**
     * 新增<b>单个</b>标记（管理端 {@code POST /api/media/admin/video/{id}/markers}）
     *
     * <p>与 {@link #updateVideoMeta} 的 {@code markers} 全量替换相反，这里只追加一条，
     * 前端「此刻打个点」不必回传整张表、也不会覆盖别人刚打的点。
     * pos 必须 {@code 0 <= pos <= dur}（dur 为 NULL 时跳过上限），单视频标记总数上限同全量接口。
     * {@code endPos} 可选：null = 单点标记，非 null 必须 {@code pos < endPos <= dur}。</p>
     *
     * @return 新建的 {@code {id,pos,endPos,label,color}}
     */
    Map<String, Object> addMarker(String videoId, MediaVideoMarker marker);

    /** 批量补丁，返回 {@code {updated,...}}（updated = 命中并处理到的现存视频条数） */
    Map<String, Object> batchMeta(MediaMetaPatch patch);
}
