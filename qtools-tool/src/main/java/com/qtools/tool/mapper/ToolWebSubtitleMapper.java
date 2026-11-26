package com.qtools.tool.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 视频双语字幕状态表（docs/视频字幕-实施交接.md §7.1 / §T1）
 *
 * <p>只存状态与元数据：字幕原文落在 {@code <qtools.home>/data/subtitles/{postId}/{seg}.vtt}，
 * 库里只留路径（隐私，见文档 §12）。所有写操作都按 {@code (post_id, seg)} 定位，行由
 * {@link #insertIfAbsent} 保证先存在。</p>
 *
 * @author qtools
 */
public interface ToolWebSubtitleMapper
{
    /** 取一行的全部列；{@code stale=1} 表示 status=1 但已超过 2 小时（僵死，可重建） */
    Map<String, Object> selectOne(@Param("postId") Long postId, @Param("seg") int seg);

    /** 行不存在才插；已存在时 {@code id = id} 是空更新，不覆盖任何状态 */
    int insertIfAbsent(@Param("postId") Long postId, @Param("seg") int seg);

    /** 开跑：status=1 + 进度 + 引擎 + 清掉上一次的 err */
    int markRunning(@Param("postId") Long postId, @Param("seg") int seg, @Param("progress") int progress,
            @Param("engine") String engine);

    /** 只推进度（不改状态，也不碰 err） */
    int updateProgress(@Param("postId") Long postId, @Param("seg") int seg, @Param("progress") int progress);

    /** 成功：status=2 + 成品信息 */
    int markDone(@Param("postId") Long postId, @Param("seg") int seg, @Param("durMs") long durMs,
            @Param("cueCount") int cueCount, @Param("filePath") String filePath, @Param("engine") String engine);

    /** 失败：status=3 + 可读原因（调用方负责截断到 err 列宽） */
    int markFailed(@Param("postId") Long postId, @Param("seg") int seg, @Param("err") String err);

    /** 成功但有小瑕疵（如「部分行未翻译」）：markDone 之后补一条备注 */
    int markErrNote(@Param("postId") Long postId, @Param("seg") int seg, @Param("err") String err);

    /** 删掉一条帖子的全部段（连同文件由调用方删） */
    int deleteByPost(@Param("postId") Long postId);
}
