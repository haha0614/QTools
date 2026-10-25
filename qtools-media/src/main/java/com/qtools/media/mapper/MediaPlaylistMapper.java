package com.qtools.media.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaPlaylist;

/**
 * 播放列表 media_playlist 数据层（P5-B）
 *
 * <p>可见性口径与 {@code media_saved_filter} 一致：站端读「自己的 + 全局的」，写只碰自己的
 * （{@code owner_key} 相等的行），全局条目只能由管理端清理。</p>
 *
 * <p>写路径同样刻意不用 {@code ON DUPLICATE KEY}：服务层先按 {@code (name, owner_key)} 查一次，
 * 命中就复用、没命中才 insert，这样「新建 / 同名复用」两条路径的返回值都确定（{@code created} 标志、id）。</p>
 *
 * @author qtools
 */
public interface MediaPlaylistMapper
{
    /** 当前 owner 可见的列表：自己的 + 全局的（带条目数，新→旧） */
    List<MediaPlaylist> selectVisibleList(@Param("ownerKey") String ownerKey);

    /** 管理端全量清单（带条目数、归属与时间；全局条目排最前） */
    List<MediaPlaylist> selectAll();

    MediaPlaylist selectById(@Param("id") Long id);

    /** 按 (name, owner_key) 精确匹配；ownerKey 为 null 时匹配全局条目 */
    MediaPlaylist selectByNameAndOwner(@Param("name") String name, @Param("ownerKey") String ownerKey);

    /** 新增，自增 id 回填到 {@code playlist.id} */
    int insert(MediaPlaylist playlist);

    /** 改名（同时刷新 update_time） */
    int updateNameById(@Param("id") Long id, @Param("name") String name);

    /** 条目变化时只刷新 update_time（列表按它排序，加一首歌要能冒到最前） */
    int touchById(@Param("id") Long id);

    /** 站端删除：只能删自己名下的（ownerKey 为 null 时匹配全局条目） */
    int deleteByIdAndOwner(@Param("id") Long id, @Param("ownerKey") String ownerKey);

    /** 管理端删除：不看归属 */
    int deleteById(@Param("id") Long id);
}
