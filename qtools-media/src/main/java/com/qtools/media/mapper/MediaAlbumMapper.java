package com.qtools.media.mapper;

import java.util.Date;
import java.util.List;
import com.qtools.media.domain.MediaAlbum;

/**
 * 媒体相册 数据层
 *
 * @author qtools
 */
public interface MediaAlbumMapper
{
    /** 查询全部未隐藏相册，按 sort/id 升序 */
    List<MediaAlbum> selectAlbumList();

    /** 按 id 查询单个未隐藏相册 */
    MediaAlbum selectAlbumById(String id);

    /** 未隐藏相册的最大修改时间 */
    Date selectMaxMtime();
}
