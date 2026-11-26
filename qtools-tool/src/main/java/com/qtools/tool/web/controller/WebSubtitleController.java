package com.qtools.tool.web.controller;

import com.qtools.common.annotation.Anonymous;
import com.qtools.tool.web.subtitle.WebSubtitleService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频双语字幕接口（docs/视频字幕-实施交接.md §T7）
 *
 * <p>前端约定：JSON 接口一律 HTTP 200 + {@code {code:200,data}}，失败原因在 {@code data.status/err}；
 * 只有成品 {@code .vtt} 走真正的 404（还没生成好），因为它要直接喂给 {@code <track>}。</p>
 *
 * <p>不加鉴权（{@link Anonymous}）：{@code <track src>} 不会带 token，加了就 403。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/tool/web/subtitle")
public class WebSubtitleController
{
    private final WebSubtitleService service;

    public WebSubtitleController(WebSubtitleService service)
    {
        this.service = service;
    }

    /** 当前状态 */
    @GetMapping("/{postId}")
    public Map<String, Object> get(@PathVariable("postId") Long postId,
            @RequestParam(value = "seg", defaultValue = "0") int seg)
    {
        return ok(service.get(postId, seg));
    }

    /** 触发构建（force=1 强制重建） */
    @PostMapping("/{postId}/build")
    public Map<String, Object> build(@PathVariable("postId") Long postId,
            @RequestParam(value = "seg", defaultValue = "0") int seg,
            @RequestParam(value = "force", defaultValue = "0") int force)
    {
        return ok(service.build(postId, seg, force == 1));
    }

    /** 成品 VTT：生成中给 404，生成好缓存一天 */
    @GetMapping("/{postId}/{seg}.vtt")
    public ResponseEntity<String> vtt(@PathVariable("postId") Long postId, @PathVariable("seg") int seg)
    {
        String text = service.vtt(postId, seg);
        if (text == null)
        {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/vtt;charset=utf-8"))
                .cacheControl(CacheControl.maxAge(86400, TimeUnit.SECONDS).cachePublic())
                .body(text);
    }

    /** 删掉这条帖子的字幕 */
    @DeleteMapping("/{postId}")
    public Map<String, Object> delete(@PathVariable("postId") Long postId,
            @RequestParam(value = "seg", defaultValue = "0") int seg)
    {
        return ok(service.remove(postId, seg));
    }

    private static Map<String, Object> ok(Map<String, Object> data)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("data", data);
        return body;
    }
}
