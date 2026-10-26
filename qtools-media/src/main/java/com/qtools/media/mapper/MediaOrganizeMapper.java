package com.qtools.media.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaTag;
import com.qtools.media.domain.MediaVideoMarker;

/**
 * 媒体库组织能力（M3-A）数据层：标签字典 / 评分 / 时间点标记
 *
 * <p>与 {@link MediaAdminMapper} 分文件：那边是「看得到就得改得到」的行级操作，
 * 这里是纯组织属性的读写，写语句全部幂等（INSERT IGNORE / 覆盖式 UPDATE），
 * 由 {@code MediaOrganizeServiceImpl} 在同一事务里组合。</p>
 *
 * <p>包名符合 framework 的 {@code @MapperScan("com.qtools.**.mapper")}，无需 {@code @Mapper}。</p>
 *
 * @author qtools
 */
public interface MediaOrganizeMapper
{
    // ------------------------------------------------------------------ 标签字典

    /** 标签字典全量（引用次数降序，同次数按名称） */
    List<MediaTag> selectTagList();

    MediaTag selectTagById(@Param("id") Long id);

    /** 按名称查（新建标签时按它做「重名复用」） */
    MediaTag selectTagByName(@Param("name") String name);

    /** 新建字典项；主键回填到入参对象的 id 上（useGeneratedKeys） */
    int insertTag(MediaTag tag);

    int deleteTag(@Param("id") Long id);

    /** 置父标签；{@code parentId} 传 null = 置顶（自引用 / 环 / 深度由服务层校验） */
    int updateTagParent(@Param("id") Long id, @Param("parentId") Long parentId);

    /** 标签改名（不改其它列） */
    int updateTagName(@Param("id") Long id, @Param("name") String name);

    /** 标签改色；color 传 null = 清空颜色 */
    int updateTagColor(@Param("id") Long id, @Param("color") String color);

    /** 改名时同步 media_video_tag.name 那一列冗余名（只动 tag_id = 本 id 的字典行） */
    int updateVideoTagNameByTagId(@Param("tagId") Long tagId, @Param("name") String name);

    /** 删字典项时清掉它的全部关联行 */
    int deleteVideoTagByTagId(@Param("tagId") Long tagId);

    /** 入参里实际存在的标签数，用于「未知 tagId 直接 400」 */
    int countTagsByIds(@Param("tagIds") List<Long> tagIds);

    /**
     * 按 media_video_tag 实际行数重算 use_count
     *
     * <p>不做 +1/-1 增量：批量增删、删字典项、重复打标签都可能让增量漂移，
     * 重算只扫几十行字典，代价可忽略。</p>
     */
    int recomputeUseCount(@Param("tagIds") List<Long> tagIds);

    // ------------------------------------------------------------------ 视频 ↔ 字典标签

    /** 单个视频挂的字典标签（不含扫描脚本写的 facet 标签） */
    List<MediaTag> selectTagsByVideoId(@Param("videoId") String videoId);

    /** 清掉某个视频的全部字典标签（全量替换用；不动 tag_id IS NULL 的历史行） */
    int deleteVideoTagsByVideoId(@Param("videoId") String videoId);

    int deleteVideoTagsByVideoIdAndTagIds(@Param("videoId") String videoId, @Param("tagIds") List<Long> tagIds);

    /** 单个视频批量挂标签（INSERT IGNORE，同视频同标签不会重复） */
    int insertVideoTags(@Param("videoId") String videoId, @Param("tagIds") List<Long> tagIds);

    /** 多个视频 × 多个标签一次性挂上（批量打标签） */
    int batchInsertVideoTags(@Param("videoIds") List<String> videoIds, @Param("tagIds") List<Long> tagIds);

    /** 多个视频 × 多个标签一次性摘掉 */
    int batchDeleteVideoTags(@Param("videoIds") List<String> videoIds, @Param("tagIds") List<Long> tagIds);

    /** 某批视频当前挂着的全部字典标签 id（批量改完用来重算 use_count） */
    List<Long> selectTagIdsByVideoIds(@Param("videoIds") List<String> videoIds);

    // ------------------------------------------------------------------ 评分

    /** rating 为 null = 清空评分 */
    int updateRating(@Param("id") String id, @Param("rating") Integer rating);

    /** 批量覆盖评分（同一分值）；rating 为 null = 批量清空 */
    int batchUpdateRating(@Param("ids") List<String> ids, @Param("rating") Integer rating);

    // ------------------------------------------------------------------ 作品简介（P5.38）

    /**
     * 单片作品简介（{@code synopsis} 列；<b>不过滤 hidden</b>，与服务层 requireVideo 口径一致）
     *
     * <p>为什么不复用 {@code MediaVideoMapper.selectVideoById}：那条 SQL 带 {@code hidden = 0}，
     * 简介的读接口要和管理端一样能读到软删行的正文（管理员改完再删也不该读不到自己写的字）。</p>
     */
    String selectSynopsisById(@Param("id") String id);

    /** 覆盖写入简介；{@code synopsis} 传 null = 清空（纯空白在服务层已归一化成 null） */
    int updateSynopsis(@Param("id") String id, @Param("synopsis") String synopsis);

    // ------------------------------------------------------------------ 标记

    List<MediaVideoMarker> selectMarkersByVideoId(@Param("videoId") String videoId);

    /** 清掉某视频全部标记（全量替换用） */
    int deleteMarkersByVideoId(@Param("videoId") String videoId);

    /**
     * 插入一条标记（全量替换接口用）
     *
     * @param endPos 结束时间；null = 单点标记（区间合法性由服务层校验）
     */
    int insertMarker(@Param("videoId") String videoId, @Param("pos") BigDecimal pos,
            @Param("endPos") BigDecimal endPos, @Param("label") String label, @Param("color") String color);

    /**
     * 插入单条标记并把自增主键回填到入参对象的 id 上（单条新增接口要回 {"id":...}）
     *
     * <p>入参用「单一对象」而不是多个 {@code @Param}：MyBatis 的 useGeneratedKeys
     * 只对单一参数对象做属性回填，包装成 ParamMap 后 keyProperty 解析不到。</p>
     */
    int insertMarkerWithKey(MediaVideoMarker marker);

    /** 同一条标记广播到多个视频（批量 markerDelta.add 用） */
    int batchInsertMarker(@Param("videoIds") List<String> videoIds, @Param("pos") BigDecimal pos,
            @Param("label") String label, @Param("color") String color);

    /** 只删这些视频名下的这些标记 id，越界 id 静默忽略 */
    int batchDeleteMarkersByIds(@Param("videoIds") List<String> videoIds, @Param("markerIds") List<Long> markerIds);

    // ------------------------------------------------------------------ 视频存在性 / 时长

    /** [{id, dur}]：marker pos 上限校验要按各视频自己的 dur 判断，dur 为 NULL 则跳过 */
    List<Map<String, Object>> selectVideoDurList(@Param("ids") List<String> ids);

    /** 入参里实际存在的视频数（批量返回 updated 用） */
    int countVideosByIds(@Param("ids") List<String> ids);
}
