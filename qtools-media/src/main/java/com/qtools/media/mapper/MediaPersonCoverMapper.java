package com.qtools.media.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaPersonCover;

/**
 * 人物卡封面（P6.101）：按 (person_id, view, scope) 存取
 *
 * @author qtools
 */
public interface MediaPersonCoverMapper
{
    /**
     * 批量取多个人物的全部设置（一页人物一次查询）
     *
     * @param ids 人物 id；空集合返回空列表（调用方不需要自己判空）
     */
    List<MediaPersonCover> selectByPersonIds(@Param("ids") Collection<Long> ids);

    /** 单人单视图单作用域 */
    MediaPersonCover selectOne(@Param("personId") Long personId, @Param("view") String view,
            @Param("scope") String scope);

    /** 写入或覆盖（主键三列冲突时整行更新） */
    int upsert(MediaPersonCover cover);

    /** 删除一行（不存在也算成功） */
    int deleteOne(@Param("personId") Long personId, @Param("view") String view, @Param("scope") String scope);

    /** 删除一个人物的全部设置（人物被删时清理） */
    int deleteByPerson(@Param("personId") Long personId);
}
