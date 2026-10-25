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
import com.qtools.media.service.IMediaPersonService;

/**
 * 媒体站「人物」只读接口（前缀 /api/media，全部免登录）
 *
 * <p>管理端的人物 CRUD 在 {@code /api/media/admin/persons}（需 {@code media:admin:*} 权限）；
 * 这里只给用户端开<b>只读</b>四件套：人物清单（筛选器 / 人物页索引）、人物详情、
 * 人物名下视频、人物名下相册。为什么单独一个控制器而不是往
 * {@link MediaAdminController} 里加 {@code @Anonymous} 方法：类级 {@code @Anonymous}
 * 会被 framework 的 PermitAllUrlProperties 统一收进白名单，混在管理端控制器里
 * 容易让「某人给管理端加一个方法」变成「顺手对外网开了个匿名接口」。</p>
 *
 * <p>与 {@link MediaIndexController} 的分工：索引接口 {@code /api/media/index} 已经带
 * {@code videos[].persons} / {@code albums[].persons}（非空才输出），前端做纯前端筛选时用那份；
 * 这里的接口给「人物详情页 / 需要分页的深链接」用，两者数据同源（同一批表）。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/media")
public class MediaPersonController
{
    private static final Logger log = LoggerFactory.getLogger(MediaPersonController.class);

    @Autowired
    private IMediaPersonService mediaPersonService;

    /** 人物清单（供用户端人物页与筛选器）：{@code [{id,name,alias,useCount}]}，useCount 降序 */
    @GetMapping("/persons")
    public List<Map<String, Object>> persons()
    {
        return mediaPersonService.personBriefList();
    }

    /** 人物详情：{@code {id,name,alias,note,avatarPath,gender,birthday,useCount,videoCount,albumCount}} */
    @GetMapping("/person/{id}")
    public Map<String, Object> person(@PathVariable("id") Long id)
    {
        log.debug("查询媒体人物详情 id={}", id);
        return mediaPersonService.personDetail(id);
    }

    /** 人物名下视频分页：{@code {rows:[{id,title,folder,rel,size,dur,w,h,poster,role}],total,page,size}} */
    @GetMapping("/person/{id}/videos")
    public Map<String, Object> personVideos(@PathVariable("id") Long id,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size)
    {
        return mediaPersonService.personVideos(id, page, size);
    }

    /** 人物名下相册分页：{@code {rows:[{id,title,dir,count,cover,note}],total,page,size}} */
    @GetMapping("/person/{id}/albums")
    public Map<String, Object> personAlbums(@PathVariable("id") Long id,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size)
    {
        return mediaPersonService.personAlbums(id, page, size);
    }
}
