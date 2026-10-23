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
    /** 查询全部未隐藏图片，按相册 + 册内序号升序 */
    List<MediaPhoto> selectPhotoList();

    /** 查询单册图片，按 seq 升序 */
    List<MediaPhoto> selectPhotoListByAlbumId(String albumId);

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
}
