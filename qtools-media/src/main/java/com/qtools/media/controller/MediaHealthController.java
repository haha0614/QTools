package com.qtools.media.controller;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Log;
import com.qtools.common.enums.BusinessType;
import com.qtools.media.service.IMediaHealthService;
import com.qtools.media.support.MediaBodyParser;

/**
 * 管理端「库健康」接口（前缀 /api/media/admin/health）
 *
 * <p>为什么单独一个控制器：体检是<b>横切</b>的（相册 / 图片 / 视频 / 库根一起看），
 * 挂在任何单个资源控制器下都会让「体检」看起来只属于那一类。</p>
 *
 * <p>权限：查看 {@code media:admin:list}，修复 {@code media:admin:edit}；修复动作都写 {@code sys_oper_log}。</p>
 *
 * @author qtools
 */
@RestController
@RequestMapping("/media/admin/health")
public class MediaHealthController
{
    private static final Logger log = LoggerFactory.getLogger(MediaHealthController.class);

    @Autowired
    private IMediaHealthService mediaHealthService;

    /** 体检报告（只读） */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping
    public Map<String, Object> health()
    {
        return mediaHealthService.report();
    }

    /**
     * 执行修复：body {@code {"kind":"albumCounters"|"emptyAlbums"}}
     *
     * <p>只有这两种，且都是「不改媒体源、删的只是 0 图空壳」的安全动作；
     * 其余检查一律只报数，人工判断后再动。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-库健康修复", businessType = BusinessType.UPDATE)
    @PostMapping("/fix")
    public Map<String, Object> fix(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = MediaBodyParser.parseObject(rawBody, true);
        String kind = MediaBodyParser.stringValue(body.get("kind"));
        log.info("库健康修复 kind={}", kind);
        return mediaHealthService.fix(kind);
    }
}
