package com.qtools.media.mapper;

import java.util.List;
import java.util.Map;

/**
 * 自动分级（P6.02）的<b>只读</b>取数
 *
 * <p>只取判定所需的最小列：标题 / 目录 / 相对路径 / 当前分级 / 标签名。写入不在这里 ——
 * 复用 {@link MediaRatingMapper} 的 {@code updateVideosRating / updateAlbumsRating}，
 * 让「谁在改分级」只有一处 SQL（与单条 / 批量接口同一条语句）。</p>
 *
 * <p>取数三张表分开取、<b>在服务层拼</b>：视频 239 行 + 标签 690 行 + 相册 170 行 + 照片约 1 万行，
 * 用 join 会把这些行数相乘（视频×标签、相册×照片），内存和 SQL 都白花。</p>
 *
 * @author qtools
 */
public interface MediaRatingClassifyMapper
{
    /** 待判定的视频：{@code [{id, title, folder, relPath, contentRating}]}（{@code hidden=1} 的软删条目不参与） */
    List<Map<String, Object>> selectVideoRows();

    /** 视频标签：{@code [{videoId, name}]}（标签名参与规则匹配，与站端筛选口径一致） */
    List<Map<String, Object>> selectVideoTagRows();

    /** 待判定的相册：{@code [{id, title, dir, contentRating}]} */
    List<Map<String, Object>> selectAlbumRows();

    /** 相册名下照片的相对路径：{@code [{albumId, relPath}]}（相册按「册里最严的一张照片」聚合） */
    List<Map<String, Object>> selectAlbumPhotoRows();
}
