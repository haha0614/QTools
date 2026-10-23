package com.qtools.media.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.common.utils.ClientAbortUtils;
import com.qtools.media.domain.dto.MediaTrackDTO;
import com.qtools.media.service.IMediaTrackService;

/**
 * 媒体站事件埋点 匿名上报接口（唯一入口 {@code POST /api/media/track}）
 *
 * <p>方法上标注 {@link Anonymous}，framework 的 PermitAllUrlProperties 会把
 * {@code /api/media/track} 加入 Security 的 permitAll 白名单，无需改动 SecurityConfig。
 * 必须标注：SecurityConfig 规定非 GET 的 {@code /api/**} 一律需要认证。</p>
 *
 * <p><b>前端不可被埋点拖累</b>：本控制器对任何异常都返回 {@code AjaxResult.success()}，
 * 而不是让 {@code GlobalExceptionHandler} / {@code MediaExceptionHandler} 回 400/500：
 * <ul>
 *   <li>入参为空 / JSON 解析失败 / 校验不通过 —— 控制器内 {@code @ExceptionHandler} 吞掉，记 debug；</li>
 *   <li>写库失败 —— service 内 try/catch 吞掉，记 warn；</li>
 *   <li>任何未预料的异常 —— 控制器兜底 {@code @ExceptionHandler(Exception.class)}，记 warn。</li>
 * </ul>
 * 因此前端用 fetch 直接 post、不处理返回值也是安全的。</p>
 *
 * @author qtools
 */
@RestController
@RequestMapping("/api/media")
public class MediaTrackController
{
    private static final Logger log = LoggerFactory.getLogger(MediaTrackController.class);

    @Autowired
    private IMediaTrackService mediaTrackService;

    /**
     * 记录一条事件：{@code {"eventType":"play","targetType":"video","targetKey":"<video id>","valueNum":0}}
     *
     * @param dto 埋点入参
     * @return 恒为成功
     */
    @Anonymous
    @PostMapping("/track")
    public AjaxResult track(@Validated @RequestBody MediaTrackDTO dto)
    {
        log.debug("媒体埋点上报 {}", dto);
        mediaTrackService.track(dto);
        return AjaxResult.success();
    }

    /** 入参不合法（缺 body / JSON 非法 / 校验失败）：不落库、不报错，只记 debug */
    @ExceptionHandler({ BindException.class, HttpMessageNotReadableException.class })
    public AjaxResult handleBadRequest(Exception e)
    {
        log.debug("媒体埋点入参不合法，已忽略: {}", e.getMessage());
        return AjaxResult.success();
    }

    /**
     * 兜底：埋点链路上任何未预料的异常都不外泄给前端。
     *
     * <p>埋点是 sendBeacon / keepalive 发出的「发了就不管」请求，页面刷新或关闭时被浏览器取消很常见，
     * 这类「客户端已断开」的异常按 DEBUG 记录（复用全局异常处理器里的同一个判断），
     * 其余异常仍按 WARN 记录。</p>
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleUnexpected(Exception e)
    {
        if (ClientAbortUtils.isClientAbort(e))
        {
            log.debug("媒体埋点：客户端已断开，忽略（{}）", e.getClass().getSimpleName());
        }
        else
        {
            log.warn("媒体埋点处理异常（已忽略）: {}", e.getMessage());
        }
        return AjaxResult.success();
    }
}
