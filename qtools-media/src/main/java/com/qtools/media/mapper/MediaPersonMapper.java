package com.qtools.media.mapper;

import java.util.List;
import com.qtools.media.domain.MediaPerson;

/**
 * 媒体人物 数据层
 *
 * @author qtools
 */
public interface MediaPersonMapper
{
    /** 查询全部未隐藏人物，按 sort/id 升序 */
    List<MediaPerson> selectPersonList();
}
