package com.qtools.media.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaPhoto;

/**
 * 媒体图片 数据层
 *
 * @author qtools
 */
public interface MediaPhotoMapper
{
    /**
     * 查询全部未隐藏图片，按相册 + 册内序号升序
     *
     * @param includeMissing 默认 true（缺失行照旧带出，前端自行过滤）；false 时服务端加 missing = 0
     */
    List<MediaPhoto> selectPhotoList(@Param("includeMissing") Boolean includeMissing);

    /** 查询单册图片，按 seq 升序（includeMissing 语义同上） */
    List<MediaPhoto> selectPhotoListByAlbumId(@Param("albumId") String albumId,
            @Param("includeMissing") Boolean includeMissing);

    /** 单张图片是否存在（未隐藏且所属相册未隐藏），用于收藏/最近浏览的参数校验 */
    int countPhotoByAlbumAndSeq(@Param("albumId") String albumId, @Param("seq") Integer seq);

    /**
     * 单张图片的内容哈希，供收藏 / 最近浏览写入时绑定稳定身份
     *
     * <p>图片不存在、已隐藏，或扫描脚本还没回填哈希时返回 {@code null}——调用方据此回退
     * 老的 {@code (album_id, seq)}，不改变历史行为。详见 {@code doc/媒体库可移植性方案.md}。
     * 过滤条件与 {@link #countPhotoByAlbumAndSeq} 保持一致，避免"能收藏却查不到哈希"。</p>
     */
    String selectContentHashByAlbumAndSeq(@Param("albumId") String albumId, @Param("seq") Integer seq);

    /**
     * 单张图片的内容哈希（<b>不过滤 hidden</b>），供缩略图 / 预览图按内容寻址定位
     *
     * <p>与 {@link #selectContentHashByAlbumAndSeq} 的差别只有过滤条件：管理端要能预览已隐藏
     * 的行，隐藏不该让 {@code /media/thumb/p/...} 退化成 404；哈希已回填时该图直接命中
     * {@code thumbs/p/<content_hash>.jpg}。未回填（老库）返回 {@code null}，调用方回退
     * 老命名 {@code p/<albumId>/<seq4>.jpg}。</p>
     */
    String selectPhotoContentHashByAlbumSeq(@Param("albumId") String albumId, @Param("seq") Integer seq);
}
