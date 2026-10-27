package com.qtools.media.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaRoot;

/**
 * 媒体库根 media_root 数据层（多库根）
 *
 * <p>包名符合 framework 中 {@code @MapperScan("com.qtools.**.mapper")}，无需 {@code @Mapper} 注解。
 * 读路径（清单 / 默认根）供只读接口与配置自检使用；写路径（insert / update / delete）只由
 * {@code IMediaMaintenanceService} 调用，引用计数检查在服务层做，不在这里。</p>
 *
 * @author qtools
 */
public interface MediaRootMapper
{
    /** 全部库根，按 id 升序（kind 含 root / thumbs / trash） */
    List<MediaRoot> selectRootList();

    /** 按 id 取一个库根；不存在返回 null */
    MediaRoot selectRootById(@Param("id") Integer id);

    /**
     * 默认媒体根 id：{@code kind='root'} 的第一行；没有返回 null
     *
     * <p>与 scan-media.js 的 {@code --root-id} 缺省口径一致。</p>
     */
    Integer selectDefaultRootId();

    /** 按 path 精确匹配（{@code uk_media_root_path} 唯一键；新增 / 改路径时查重用） */
    MediaRoot selectRootByPath(@Param("path") String path);

    /** 新增库根，自增 id 回填到 {@code root.id} */
    int insertRoot(MediaRoot root);

    /** 改库根：只更新非空字段（name / path / kind / note） */
    int updateRoot(MediaRoot root);

    /** 删除库根（引用计数检查在服务层做） */
    int deleteRootById(@Param("id") Integer id);

    /**
     * 按 root_id 统计媒体条数
     *
     * <p>返回 {@code [{rootId,cnt}]}；{@code root_id} 为 NULL 的存量行自成一组（{@code rootId} 为 null），
     * 服务层把它并入默认根（{@code kind='root'} 的第一行）。</p>
     */
    List<Map<String, Object>> countVideosByRoot();

    List<Map<String, Object>> countPhotosByRoot();

    /**
     * 按 root_id 统计<b>相册</b>数（P5.78 管理端「库根管理」页的条目数一栏）
     *
     * <p>{@code media_album} 自己没有 {@code root_id}，只能由图片反推：
     * {@code count(distinct album_id)}，一个相册的图片跨两个根时会在两边各记一次。</p>
     */
    List<Map<String, Object>> countAlbumsByRoot();

    /** 某个根下还剩多少条视频引用（删除前的引用检查） */
    int countVideoByRootId(@Param("id") Integer id);

    /** 某个根下还剩多少条图片引用（删除前的引用检查） */
    int countPhotoByRootId(@Param("id") Integer id);
}
