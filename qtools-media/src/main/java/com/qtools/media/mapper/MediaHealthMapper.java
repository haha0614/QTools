package com.qtools.media.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 库健康检查（P6.06）的只读统计 + 两个安全修复
 *
 * <p>为什么单独一个 mapper：这些查询横跨 {@code media_album / media_photo / media_video / media_root}
 * 四张表，而且全是「数一下有多少条不对」的聚合——塞进任何业务 mapper 都会让那边多出一堆和业务无关的 SQL。</p>
 *
 * <p>「安全修复」只放两类：<b>计数重算</b>（纯派生值）和<b>删空壳相册且目录确实不存在</b>
 * （行都是 0，删掉不丢任何图；目录判断在服务层做，SQL 只按 id 删）。其余检查只报数不动手。</p>
 *
 * @author qtools
 */
public interface MediaHealthMapper
{
    /** 声明张数与实际行数不一致的相册数 */
    long countAlbumCounterMismatch();

    /** identity_key 为空（没跑过 doc/media_album_identity.sql 回填）的相册数 */
    long countAlbumIdentityMissing();

    /** 同一 (dir_key, identity_key) 出现多行的组数（正常应为 0，唯一索引建了就不会有） */
    long countAlbumIdentityConflict();

    /** media_photo 里被标记为缺失的行数 */
    long countMissingPhotos();

    /** media_video 里被标记为缺失的行数 */
    long countMissingVideos();

    /** 一张图都没有的空壳相册数 */
    long countEmptyAlbums();

    /** 找不到所属相册的图片行（应为 0，扫描器每次都会清） */
    long countOrphanPhotoRows();

    /** 没有封面路径的相册数 */
    long countAlbumsWithoutCover();

    /** 空壳相册清单（id + dir_key），服务层据此逐个判断目录是否还在磁盘上 */
    List<Map<String, Object>> selectEmptyAlbumList();

    /** 全部相册的 (id, dir_key)，用于「相册目录不存在」检查 */
    List<Map<String, Object>> selectAlbumDirList();

    /** 计数不一致的相册样例（管理端展示前 N 条） */
    List<Map<String, Object>> selectCounterMismatchRows(@Param("limit") int limit);

    /** 媒体根路径（media_root.kind='root'），拼相册目录时按根逐个试 */
    List<String> selectRootPaths();

    /** 删空壳相册的人物关联行 */
    int deleteAlbumPersonByAlbumIds(@Param("ids") List<String> ids);

    /**
     * 清掉指向这些相册的人物头像（{@code avatar_ref} 形如 {@code a163:0#crop=...}）
     *
     * <p>P6.07：相册被删后头像引用会悬空，前端只剩字母占位——2026-10-29 清 a163 时就把
     * Machi马吉 的头像弄丢了。删相册前先清引用，至少退化成「默认头像」而不是坏图。</p>
     */
    int clearAvatarRefsForAlbums(@Param("ids") List<String> ids);

    /** 删空壳相册行本身 */
    int deleteAlbumsByIds(@Param("ids") List<String> ids);

    /** 按 media_photo 实际行数重算 photo_count / bytes（只改不一致的行） */
    int recountAlbumCounters();
}
