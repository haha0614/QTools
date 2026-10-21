package com.qtools.media.service;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 实时转码（分片 MP4） 服务层
 *
 * @author qtools
 */
public interface IMediaStreamService
{
    /** 当前占用的转码名额 */
    int getActive();

    /** 并发上限 */
    int getMax();

    /** 支持的清晰度档位 */
    List<String> getPresets();

    /** ffmpeg 是否可用 */
    boolean isFfmpegAvailable();

    /**
     * 把源文件实时转码成分片 MP4 写入响应
     *
     * @param p        相对媒体根的视频路径
     * @param q        清晰度档位 720/480/360
     * @param t        起播秒数（快速 seek）
     * @param request  用于识别 HEAD
     * @param response 响应
     * @throws IOException 写响应失败
     */
    void transcode(String p, String q, String t, HttpServletRequest request, HttpServletResponse response)
            throws IOException;
}
