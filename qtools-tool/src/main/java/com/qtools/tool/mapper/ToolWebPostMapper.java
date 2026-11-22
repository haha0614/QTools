package com.qtools.tool.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 外部站点条目索引（P6.110）
 *
 * <p>只做「本站承载页」需要的那几列：标题 / 分类 / 标签 / 封面 / 日期 / 源站 URL。
 * 播放地址不放这里 —— 它是签名 HLS，存下来第二天就失效，播放时由代理现取。</p>
 */
public interface ToolWebPostMapper
{
    /** 分页查询（q 命中标题或标签；category / tag 精确匹配） */
    List<Map<String, Object>> selectPage(@Param("source") String source, @Param("qLike") String qLike,
            @Param("ftQuery") String ftQuery, @Param("ftOr") Boolean ftOr,
            @Param("likeTokens") java.util.List<String> likeTokens,
            @Param("category") String category, @Param("tag") String tag,
            @Param("tags") java.util.List<String> tags, @Param("blockTags") java.util.List<String> blockTags,
            @Param("dateFrom") String dateFrom, @Param("dateTo") String dateTo,
            @Param("featured") String featured,
            @Param("videoType") String videoType, @Param("minVideos") Integer minVideos,
            @Param("orientation") String orientation,
            @Param("offset") int offset, @Param("limit") int limit);

    long countPage(@Param("source") String source, @Param("qLike") String qLike,
            @Param("ftQuery") String ftQuery, @Param("ftOr") Boolean ftOr,
            @Param("likeTokens") java.util.List<String> likeTokens,
            @Param("category") String category, @Param("tag") String tag,
            @Param("tags") java.util.List<String> tags, @Param("blockTags") java.util.List<String> blockTags,
            @Param("dateFrom") String dateFrom, @Param("dateTo") String dateTo,
            @Param("featured") String featured,
            @Param("videoType") String videoType, @Param("minVideos") Integer minVideos,
            @Param("orientation") String orientation);

    /** 方向分布：{@code [{o:'l',c:2453},{o:'p',c:2403}]}（只统计已判定方向的） */
    java.util.List<Map<String, Object>> selectOrientationCounts(@Param("source") String source);

    /** 找"该日期（含）之前最新的一条"，用于按日期跳转时直接定位到那条真实帖子 */
    Long selectIdByDate(@Param("source") String source, @Param("dateTo") String dateTo,
            @Param("category") String category, @Param("tag") String tag,
            @Param("blockTags") java.util.List<String> blockTags);

    /** 每天有多少条（自定义月历用） */
    java.util.List<Map<String, Object>> selectDayCounts(@Param("source") String source,
            @Param("from") String from, @Param("to") String to,
            @Param("category") String category, @Param("tag") String tag);

    /** 按日期定位：某日期之后还有多少条（用来算"这个日期落在第几页"） */
    long countAfterDate(@Param("source") String source, @Param("dateTo") String dateTo,
            @Param("category") String category, @Param("tag") String tag);

    Map<String, Object> selectOne(@Param("id") Long id);

    /** 条目对应的签名 m3u8（抓取时存下的；播放时代理转发，前端不接触源站） */
    String selectVideoUrl(@Param("id") Long id);

    /** 多视频：取第 index 个播放地址（video_urls 里没有就退回 video_url） */
    String selectVideoUrl(@Param("id") Long id, @Param("index") int index);

    /** 库里已有几段播放地址（重签结果变短时要靠它兜住） */
    Integer countVideoUrls(@Param("id") Long id);

    /** 源站详情页 URL（签名过期时交给重签 sidecar 用） */
    String selectSourceUrl(@Param("id") Long id);

    /** 重签成功后回写，下次播放直接用新的 */
    int updateVideoUrl(@Param("id") Long id, @Param("videoUrl") String videoUrl);

    /**
     * 重签后整组回写（多视频）。
     *
     * videoUrl 单独传参，不再用 SQL 的 json_extract 从字符串里取第一段——
     * 那样只要传进来的是"字符串字面量"，取出来就是引号里的第一个字符，
     * 曾把 video_url 写成过一个逗号，播放器直接 manifestLoadError。
     */
    int updateVideoUrls(@Param("id") Long id, @Param("videoUrl") String videoUrl,
            @Param("videoUrls") String videoUrls, @Param("videoCount") int videoCount);

    /** 分类计数（只列有内容的分类） */
    List<Map<String, Object>> selectCategoryCounts(@Param("source") String source);

    /** 全部标签原文（逗号分隔），由调用方切分统计 —— 标签是逗号串，SQL 里拆不划算 */
    List<String> selectAllTags(@Param("source") String source);

    /** 有内容的日期（yyyy-MM-dd + 条数）：前端用它把没有内容的日期置灰 */
    List<Map<String, Object>> selectDateCounts(@Param("source") String source);

    /** 定位：某条在当前筛选下排第几（0-based），用于"回到上次浏览位置"直接跳页 */
    long countBefore(@Param("postId") Long postId, @Param("source") String source,
            @Param("category") String category, @Param("tag") String tag,
            @Param("tags") java.util.List<String> tags, @Param("blockTags") java.util.List<String> blockTags,
            @Param("dateFrom") String dateFrom, @Param("dateTo") String dateTo,
            @Param("featured") String featured);

    /** 有真实日期的条数（日期筛选能覆盖到的部分） */
    long countDated(@Param("source") String source);

    long countAll(@Param("source") String source);

    /** 收藏 / 看过 写入（重复插入忽略） */
    int addUserItem(@Param("ownerKey") String ownerKey, @Param("postId") Long postId, @Param("kind") String kind);

    int removeUserItem(@Param("ownerKey") String ownerKey, @Param("postId") Long postId, @Param("kind") String kind);

    /** 下载记录 */
    int insertDownload(@Param("postId") Long postId, @Param("mode") String mode,
            @Param("state") String state, @Param("params") String params, @Param("kind") String kind);

    /** 上一条 insert 的自增 id（多参数 insert 无法用 keyProperty 回填） */
    long selectLastInsertId();

    int updateDownload(@Param("id") Long id, @Param("state") String state,
            @Param("percent") int percent, @Param("sizeBytes") Long sizeBytes, @Param("error") String error);

    Map<String, Object> selectDownload(@Param("id") Long id);

    List<Map<String, Object>> selectDownloads(@Param("limit") int limit);

    /** 标签黑名单 */
    List<String> selectBlockedTags(@Param("ownerKey") String ownerKey);

    int addBlockedTag(@Param("ownerKey") String ownerKey, @Param("tag") String tag);

    int removeBlockedTag(@Param("ownerKey") String ownerKey, @Param("tag") String tag);

    /** 收藏 / 浏览记录的分页行（连主表，直接可渲染） */
    List<Map<String, Object>> selectUserItems(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("offset") int offset, @Param("limit") int limit);

    long countUserItems(@Param("ownerKey") String ownerKey, @Param("kind") String kind);

    /** 某个 owner 的收藏 / 看过 id 列表（新的在前） */
    List<Long> selectUserItemIds(@Param("ownerKey") String ownerKey, @Param("kind") String kind,
            @Param("limit") int limit);
}
