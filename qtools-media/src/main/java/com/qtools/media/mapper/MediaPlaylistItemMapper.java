package com.qtools.media.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaPlaylistItem;

/**
 * 播放列表条目 media_playlist_item 数据层（P5-B）
 *
 * <p>顺序即语义：所有读都 {@code order by seq}，所有写都以「先清空、再按新顺序整段插入」为主
 * （全量替换），只有追加 / 删除单条走增量。删除中间一条后必须把后面的 seq 前移
 * （{@link #updateSeq}），否则 PK {@code (playlist_id, seq)} 的「连续」这个隐含约定就断了，
 * 下一次追加会插到超出末尾很远的位置。</p>
 *
 * @author qtools
 */
public interface MediaPlaylistItemMapper
{
    /** 详情：join media_video 取 title / dur（视频不在了 title / dur 为 null，条目保留） */
    List<MediaPlaylistItem> selectByPlaylistId(@Param("playlistId") Long playlistId);

    /** 只取有序的 videoId（删除后重排 seq 用，不必 join） */
    List<String> selectVideoIds(@Param("playlistId") Long playlistId);

    /** 该视频是否已在列表里（追加时的 no-op 判定） */
    int countByPlaylistAndVideo(@Param("playlistId") Long playlistId, @Param("videoId") String videoId);

    /** 当前最大 seq；空列表返回 null */
    Integer selectMaxSeq(@Param("playlistId") Long playlistId);

    /** 单条追加 */
    int insertItem(MediaPlaylistItem item);

    /** 全量替换 / 重排：清空该列表全部条目 */
    int deleteByPlaylistId(@Param("playlistId") Long playlistId);

    /** 删除单条 */
    int deleteItem(@Param("playlistId") Long playlistId, @Param("videoId") String videoId);

    /** 重排：把该列表里某条移到指定 seq（调用方按 seq 升序处理，目标 seq 恒小于当前 seq，不会撞主键） */
    int updateSeq(@Param("playlistId") Long playlistId, @Param("videoId") String videoId, @Param("seq") Integer seq);
}
