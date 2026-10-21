package com.qtools.media.service;

import java.nio.file.Path;

/**
 * 媒体文件定位与内容处理 服务层
 *
 * <p>所有路径都在这里集中做 resolve + realpath + 白名单校验，控制器不得自行拼接路径。</p>
 *
 * @author qtools
 */
public interface IMediaFileService
{
    /**
     * 校验并定位媒体根下的真实文件
     *
     * @param rel 相对媒体根的路径（客户端传入的 p 参数）
     * @return realpath 之后的文件路径
     * @throws com.qtools.media.exception.MediaException 400 缺参 / 403 越权 / 404 不存在
     */
    Path resolveMedia(String rel);

    /**
     * 校验并定位媒体根下的真实目录（相册软删要移动整个目录）
     *
     * @param rel 相对媒体根的目录路径
     * @return realpath 之后的目录路径
     * @throws com.qtools.media.exception.MediaException 400 缺参 / 403 越权 / 404 不存在
     */
    Path resolveMediaDir(String rel);

    /**
     * 校验一个「写入目标」路径：允许尚不存在，但规则与读取一致
     *
     * <p>用于软删后的恢复：rel 来自数据库的 rel_path / dir，仍须拒绝绝对路径、
     * 盘符、UNC、越出媒体根与站内目录 {@code _媒体站}。</p>
     *
     * @param rel 相对媒体根的路径
     * @return 规范化后的绝对路径（未做 realpath，文件可以还不存在）
     */
    Path resolveWriteTarget(String rel);

    /** 媒体根 realpath */
    Path mediaRoot();

    /** 回收站根目录（{@code _媒体站\trash}），不创建、不校验存在 */
    Path trashRoot();

    /**
     * 回收站下按 kind 分类的目录（{@code _媒体站\trash\<kind>}），不存在时创建
     *
     * @param kind video / album / photo
     * @return 该分类的回收站目录
     */
    Path resolveTrashDir(String kind);

    /** 把媒体根内的绝对路径转成相对路径（正斜杠，便于回给前端） */
    String relativize(Path path);

    /**
     * 定位视频缩略图 thumbs/v/{id}.jpg
     *
     * @param id 视频 id
     * @return 缩略图路径
     */
    Path resolveThumbVideo(String id);

    /**
     * 定位相册缩略图（p）或预览图（pv）
     *
     * @param albumId 相册 id，如 a001
     * @param index   册内序号字符串，如 0000
     * @param preview true=预览图（pv），false=缩略图（p）
     * @return 图片路径
     */
    Path resolveThumbPhoto(String albumId, String index, boolean preview);

    /** 按扩展名返回 Content-Type */
    String contentType(Path path);

    /** SRT/VTT 文本转标准 WebVTT */
    String toVtt(String text, boolean srt);
}
