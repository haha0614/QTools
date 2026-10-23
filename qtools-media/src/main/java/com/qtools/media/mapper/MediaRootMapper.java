package com.qtools.media.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaRoot;

/**
 * 媒体库根 media_root 数据层（多库根，只读）
 *
 * <p>包名符合 framework 中 {@code @MapperScan("com.qtools.**.mapper")}，无需 {@code @Mapper} 注解。
 * 本期只读：根的增删改仍由 SQL 脚本 / 运维手工维护。</p>
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
}
