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
}
