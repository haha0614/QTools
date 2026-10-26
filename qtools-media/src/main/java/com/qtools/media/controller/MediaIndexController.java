package com.qtools.media.controller;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.domain.MediaIndexSnapshot;
import com.qtools.media.service.IMediaIndexService;

/**
 * 媒体索引 对外公开接口（用户端免登录）
 *
 * <p>类上标注 {@link Anonymous}，framework 的 PermitAllUrlProperties 会自动把本控制器下的
 * 全部接口加入 Security 的 permitAll 白名单，无需改动 SecurityConfig。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/media")
public class MediaIndexController
{
    private static final Logger log = LoggerFactory.getLogger(MediaIndexController.class);

    private static final String JSON_UTF8 = "application/json; charset=utf-8";

    @Autowired
    private IMediaIndexService mediaIndexService;

    /**
     * 视频/相册/人物/统计聚合
     *
     * <p>带 ETag（数据版本号），命中 If-None-Match 时回 304；结果缓存 60s。</p>
     */
    @GetMapping("/index")
    public void index(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(value = "includeMissing", required = false, defaultValue = "true") boolean includeMissing)
            throws IOException
    {
        // includeMissing=false 时服务端滤掉 missing=1 的行；默认 true = 老行为（前端自行过滤）
        MediaIndexSnapshot snapshot = mediaIndexService.indexSnapshot(includeMissing);
        String etag = snapshot.getEtag();
        response.setHeader("ETag", etag);
        response.setHeader("Cache-Control", "no-cache");
        if (isNotModified(request.getHeader("If-None-Match"), etag))
        {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }
        byte[] body = snapshot.getBody();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(JSON_UTF8);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLength(body.length);
        if (!isHead(request))
        {
            response.getOutputStream().write(body);
        }
    }

    /** 单片详情（含 tags/actors） */
    @GetMapping("/video/{id}")
    public Map<String, Object> video(@PathVariable("id") String id)
    {
        log.debug("查询媒体视频详情 id={}", id);
        return mediaIndexService.selectVideoDetail(id);
    }

    /**
     * 单片元信息（P5.50，匿名只读）：{@code {id,rating,tags,persons,studios,markers,fps,dur}}
     *
     * <p>为什么需要它：信息栏的标记列表以前只有管理端 {@code GET /admin/video/{id}/meta}
     * 能读（要 media:admin 权限），站端匿名访客一律 401，于是标记在站上等于废掉，
     * 前端只能固定降级成「标记需要管理员身份读取」。本接口把同一份元信息放到免登录的
     * {@code /media/**} 下。</p>
     *
     * <p>只读，<b>不受编辑策略（off/local/full）影响</b>；不输出任何管理端专属列
     * （missing / mtime / 相对路径 / 哈希 / 扫描状态）。视频不存在或 {@code hidden = 1}
     * 一律 404 —— 与 {@code GET /media/video/{id}} 完全同口径，不泄露「这有个隐藏片」。</p>
     */
    @GetMapping("/video/{id}/meta")
    public Map<String, Object> videoMeta(@PathVariable("id") String id)
    {
        log.debug("查询视频元信息 id={}", id);
        return mediaIndexService.selectVideoMeta(id);
    }

    /** 单册详情（含 photos，seq 升序；photos[].hash = content_hash，供前端拼内容寻址缩略图） */
    @GetMapping("/album/{id}")
    public Map<String, Object> album(@PathVariable("id") String id,
            @RequestParam(value = "includeMissing", required = false, defaultValue = "true") boolean includeMissing)
    {
        log.debug("查询媒体相册详情 id={} includeMissing={}", id, includeMissing);
        return mediaIndexService.selectAlbumDetail(id, includeMissing);
    }

    /** If-None-Match 是否命中当前 ETag（支持 *、逗号列表、弱比较前缀） */
    private static boolean isNotModified(String header, String etag)
    {
        if (StringUtils.isEmpty(header))
        {
            return false;
        }
        String value = header.trim();
        if ("*".equals(value))
        {
            return true;
        }
        for (String token : value.split(","))
        {
            String candidate = token.trim();
            if (candidate.startsWith("W/"))
            {
                candidate = candidate.substring(2);
            }
            if (etag.equals(candidate))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isHead(HttpServletRequest request)
    {
        return "HEAD".equalsIgnoreCase(request.getMethod());
    }
}
