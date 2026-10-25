package com.qtools.media.service;

import java.util.List;
import java.util.Map;
import com.qtools.media.domain.dto.MediaStudioPatch;

/**
 * 媒体工作室 服务（P5-A）
 *
 * <p>管理端：字典 CRUD（{@code /api/media/admin/studios}）+ 视频绑定全量替换
 * （{@code PUT /api/media/admin/videos/{id}/studios}）。
 * 站端：只读清单 / 详情 / 名下视频分页（{@code /api/media/studios}）。</p>
 *
 * <p>删除带引用的工作室一律 400 并给出引用计数，不做级联删除。</p>
 *
 * @author qtools
 */
public interface IMediaStudioService
{
    // ------------------------------------------------------------------ 管理端：字典

    /** 工作室清单：{@code [{id,name,alias,note,avatarPath,useCount,sort,hidden,videoCount}]}（含 hidden） */
    List<Map<String, Object>> studioList();

    /** 新建：name 必填且唯一（重名 409），其余字段可缺省 */
    Map<String, Object> createStudio(MediaStudioPatch patch);

    /** 改任意字段：出现即覆盖，显式 null = 清空；改名撞别人 409 */
    Map<String, Object> updateStudio(Long id, MediaStudioPatch patch);

    /** 删除：仍被视频引用时 400（message 带 videoCount） */
    Map<String, Object> deleteStudio(Long id);

    // ------------------------------------------------------------------ 管理端：绑定

    /** 全量替换某视频的工作室（{@code []} = 解除全部绑定），返回 {updated,id,studios} */
    Map<String, Object> replaceVideoStudios(String videoId, List<Long> studioIds);

    // ------------------------------------------------------------------ 站端（只读）

    /** 站端清单（只出 hidden = 0）：{@code [{id,name,alias,useCount,videoCount}]} */
    List<Map<String, Object>> studioBriefList();

    /** 站端详情；hidden = 1 视同不存在（404） */
    Map<String, Object> studioDetail(Long id);

    /** 站端名下视频分页：{@code {rows:[{id,title,folder,rel,size,dur,w,h,poster}],total,page,size}} */
    Map<String, Object> studioVideos(Long id, int page, int size);
}
