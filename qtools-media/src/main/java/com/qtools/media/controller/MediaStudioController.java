package com.qtools.media.controller;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.media.service.IMediaStudioService;

/**
 * 媒体站「工作室」只读接口（前缀 /api/media，全部免登录）
 *
 * <p>管理端的工作室 CRUD 在 {@code /api/media/admin/studios}（需 {@code media:admin:*} 权限）；
 * 这里只给用户端开只读三件套：工作室清单（筛选器 / 首页）、工作室详情、
 * 工作室名下视频分页。为什么单独一个控制器而不是往 {@link MediaAdminController} 里加
 * {@code @Anonymous} 方法：类级 {@code @Anonymous} 会被 framework 的 PermitAllUrlProperties
 * 收进白名单，混在管理端控制器里容易把「加一个方法」变成「顺手对外网开了个匿名接口」。</p>
 *
 * <p>索引接口 {@code /api/media/index} 的 {@code videos[].studios} 已带工作室名（非空才输出），
 * 前端做纯前端筛选时用那份；这里的接口给「工作室页 / 需要分页的深链接」用，两者同一批表。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/media")
public class MediaStudioController
{
    private static final Logger log = LoggerFactory.getLogger(MediaStudioController.class);

    @Autowired
    private IMediaStudioService mediaStudioService;

    /** 工作室清单（只出未隐藏的）：{@code [{id,name,alias,useCount,videoCount}]}，sort 升序 */
    @GetMapping("/studios")
    public List<Map<String, Object>> studios()
    {
        return mediaStudioService.studioBriefList();
    }

    /** 工作室详情：{@code {id,name,alias,note,avatarPath,useCount,videoCount}}；hidden = 1 视同 404 */
    @GetMapping("/studio/{id}")
    public Map<String, Object> studio(@PathVariable("id") Long id)
    {
        log.debug("查询媒体工作室详情 id={}", id);
        return mediaStudioService.studioDetail(id);
    }

    /** 工作室名下视频分页：{@code {rows:[{id,title,folder,rel,size,dur,w,h,poster}],total,page,size}} */
    @GetMapping("/studio/{id}/videos")
    public Map<String, Object> studioVideos(@PathVariable("id") Long id,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size)
    {
        return mediaStudioService.studioVideos(id, page, size);
    }
}
