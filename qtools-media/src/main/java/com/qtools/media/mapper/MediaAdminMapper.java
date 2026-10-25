package com.qtools.media.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaAlbum;
import com.qtools.media.domain.MediaPhoto;
import com.qtools.media.domain.MediaVideo;

/**
 * 管理端媒体库 数据层
 *
 * <p>与只读的 M1 mapper 分开：这里的查询不过滤 {@code hidden}（管理端必须能看到被隐藏的行），
 * 并提供隐藏位、人物归属的写语句。分页交给 PageHelper，因此不写 count 语句。</p>
 *
 * @author qtools
 */
public interface MediaAdminMapper
{
    // ------------------------------------------------------------------ 列表

    /**
     * 视频分页查询
     *
     * @param keyword 标题 / 路径模糊匹配
     * @param folder  一级目录（分类）精确匹配
     * @param person  演员名精确匹配（media_video_actor）
     * @param vcodec  编码模糊匹配
     * @param hidden  null=全部，'0'=未隐藏，'1'=已隐藏
     * @param sort    size-desc / dur-desc / mtime-desc / title，其余走默认排序
     * @param tagIds  标签 id 集合（调用方已按层级展开成「自身 + 全部后代」）；null / 空表 = 不按标签过滤
     */
    List<MediaVideo> selectVideoPage(@Param("keyword") String keyword, @Param("folder") String folder,
            @Param("person") String person, @Param("vcodec") String vcodec, @Param("hidden") String hidden,
            @Param("sort") String sort, @Param("tagIds") List<Long> tagIds);

    /** 相册分页查询（personId 为 null 时不按人物过滤） */
    List<MediaAlbum> selectAlbumPage(@Param("keyword") String keyword, @Param("personId") Long personId,
            @Param("hidden") String hidden, @Param("sort") String sort);

    /** 某册图片分页查询（不过滤 hidden，含隐藏行） */
    List<MediaPhoto> selectPhotoPage(@Param("albumId") String albumId);

    /** 某册全部图片（只读库，不列磁盘）：缺失行排在最后；content_hash 一并带出 */
    List<MediaPhoto> selectAlbumPhotos(@Param("albumId") String albumId);

    /** 缺失图片分页（missing = 1）；albumId 非空时只查该册 */
    List<MediaPhoto> selectMissingPhotoPage(@Param("albumId") String albumId);

    /** 缺失视频分页（missing = 1） */
    List<MediaVideo> selectMissingVideoPage();

    /**
     * 真删缺失图片行
     *
     * @param ids 要删的 photo id；为空 = 全部 missing = 1 的行（调用方负责 all:true 的语义）
     * @return 实际删除行数
     */
    int deleteMissingPhotos(@Param("ids") List<Long> ids);

    /** 真删缺失视频行（ids 为空 = 全部 missing = 1 的行） */
    int deleteMissingVideos(@Param("ids") List<String> ids);

    /** 视频标签行（跟着视频一起删，别留孤儿） */
    int deleteVideoTagsByVideoIds(@Param("ids") List<String> ids);

    /** 视频演员行 */
    int deleteVideoActorsByVideoIds(@Param("ids") List<String> ids);

    /** 视频标记行 */
    int deleteVideoMarkersByVideoIds(@Param("ids") List<String> ids);

    /** 当前全部 missing = 1 的视频 id（删附属行用） */
    List<String> selectMissingVideoIds();

    // ------------------------------------------------------------------ 单行（含隐藏）

    /** 按 id 查询视频，不过滤 hidden */
    MediaVideo selectVideoAnyById(@Param("id") String id);

    /** 按 id 查询相册，不过滤 hidden */
    MediaAlbum selectAlbumAnyById(@Param("id") String id);

    /** 按 id 查询图片，不过滤 hidden */
    MediaPhoto selectPhotoAnyById(@Param("id") Long id);

    /** 按相册 + 册内序号查询图片，不过滤 hidden */
    MediaPhoto selectPhotoByAlbumSeq(@Param("albumId") String albumId, @Param("seq") Integer seq);

    /** 某册前 N 张图（含隐藏），seq 升序 */
    List<MediaPhoto> selectPhotoHeadByAlbumId(@Param("albumId") String albumId, @Param("limit") int limit);

    /** 与本册共用同一物理目录的其它相册数量（念念 144 册共享目录，单独软删会毁掉其它册） */
    int countAlbumByDir(@Param("dir") String dir, @Param("excludeId") String excludeId);

    /**
     * 相册所属库根：相册表没有 {@code root_id} 列，按册内图片的 {@code root_id} 推断
     *
     * @param albumId 相册 id
     * @return 册内第一个非空 {@code root_id}；全为 NULL（存量数据）时返回 null = 默认根
     */
    Integer selectAlbumRootId(@Param("albumId") String albumId);

    // ------------------------------------------------------------------ 隐藏位

    int updateVideoHidden(@Param("id") String id, @Param("hidden") Integer hidden);

    int updateAlbumHidden(@Param("id") String id, @Param("hidden") Integer hidden);

    int updatePhotoHidden(@Param("id") Long id, @Param("hidden") Integer hidden);

    /** 人物归属：personId 为 null 表示未归类 */
    int updateAlbumPerson(@Param("id") String id, @Param("personId") Long personId);

    // ------------------------------------------------------------------ 统计

    /** 一次性取各表条数、隐藏条数与字节合计 */
    Map<String, Object> selectCounts();

    /** 按一级目录统计视频条数与字节 */
    List<Map<String, Object>> selectFolderStats();

    /** 按人物统计相册数与图片数（含 0 的人物） */
    List<Map<String, Object>> selectPersonStats();

    // ------------------------------------------------------------------ 配置自检

    /**
     * 抽样取若干条视频的相对路径，用于 {@code GET /api/media/admin/config} 判断「库根是否配错」
     *
     * <p>只取 {@code rel_path}，不取整行：自检接口要能在库很大时也很快返回。</p>
     */
    List<String> selectSampleRelPaths(@Param("limit") int limit);
}
