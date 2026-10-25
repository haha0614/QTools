package com.qtools.media.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.media.service.IMediaStreamService;

/**
 * 实时转码 对外公开接口（用户端免登录）
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/media")
public class MediaStreamController
{
    private static final Logger log = LoggerFactory.getLogger(MediaStreamController.class);

    @Autowired
    private IMediaStreamService mediaStreamService;

    /**
     * 按需转码出流（分片 MP4）
     *
     * @param p 相对媒体根的视频路径
     * @param q 清晰度 720/480/360
     * @param t 起播秒数
     */
    @GetMapping("/stream")
    public void stream(@RequestParam(value = "p", required = false) String p,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "t", required = false) String t, HttpServletRequest request,
            HttpServletResponse response) throws IOException
    {
        log.debug("媒体转码请求 p={} q={} t={}", p, q, t);
        mediaStreamService.transcode(p, q, t, request, response);
    }

    /**
     * 转码占用情况：{max, active, presets, ffmpegAvailable, encoder:{requested,effective,hardware,...}}
     *
     * <p>{@code encoder.effective} 是<b>真正在用</b>的编码器（nvenc / qsv / libx264）：
     * 配了 {@code auto} 又没显卡的机器看这里就是 libx264，不必翻日志。</p>
     */
    @GetMapping("/transcode/info")
    public Map<String, Object> transcodeInfo()
    {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("max", mediaStreamService.getMax());
        info.put("active", mediaStreamService.getActive());
        info.put("presets", mediaStreamService.getPresets());
        info.put("ffmpegAvailable", mediaStreamService.isFfmpegAvailable());
        info.put("encoder", mediaStreamService.getTranscoderInfo());
        return info;
    }
}
