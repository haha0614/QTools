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
     * 校验并定位媒体根下的真实文件（默认根，等价于 {@code resolveMedia(rel, null)}）
     *
     * @param rel 相对媒体根的路径（客户端传入的 p 参数）
     * @return realpath 之后的文件路径
     * @throws com.qtools.media.exception.MediaException 400 缺参 / 403 越权 / 404 不存在
     */
    default Path resolveMedia(String rel)
    {
        return resolveMedia(rel, null);
    }

    /**
     * 校验并定位某个库根下的真实文件（多库根）
     *
     * <p><b>多根语义</b>：</p>
     * <ul>
     *   <li>{@code rootId} 非空：只在该 {@code media_root.id} 指向的根里找，越出这个根一律 403；</li>
     *   <li>{@code rootId} 为 null（{@code root_id} 列为 NULL 的存量行）：先按配置
     *       {@code qtools.media.rootPath} 找，找不到再依次试其它 {@code kind='root'} 的根
     *       ——每个候选根都走同一套「绝对路径/UNC 拒绝 + realpath 后仍在根内」校验，
     *       因此多根不会放宽越权边界；找不到才 404。</li>
     * </ul>
     *
     * @param rel    相对所属库根的路径
     * @param rootId 库根 id（{@code media_root.id}），null = 配置默认根 + 多根兜底
     * @return realpath 之后的文件路径
     * @throws com.qtools.media.exception.MediaException 400 缺参 / 403 越权 / 404 不存在
     */
    Path resolveMedia(String rel, Integer rootId);

    /**
     * 校验并定位媒体根下的真实目录（相册软删要移动整个目录）
     *
     * @param rel 相对媒体根的目录路径
     * @return realpath 之后的目录路径
     * @throws com.qtools.media.exception.MediaException 400 缺参 / 403 越权 / 404 不存在
     */
    default Path resolveMediaDir(String rel)
    {
        return resolveMediaDir(rel, null);
    }

    /**
     * 校验并定位某个库根下的真实目录（多根语义同 {@link #resolveMedia(String, Integer)}）
     *
     * @param rel    相对所属库根的目录路径
     * @param rootId 库根 id，null = 配置默认根 + 多根兜底
     * @return realpath 之后的目录路径
     * @throws com.qtools.media.exception.MediaException 400 缺参 / 403 越权 / 404 不存在
     */
    Path resolveMediaDir(String rel, Integer rootId);

    /**
     * 校验一个「写入目标」路径（默认根，等价于 {@code resolveWriteTarget(rel, null)}）
     *
     * @param rel 相对媒体根的路径
     * @return 规范化后的绝对路径（文件可以还不存在）
     */
    default Path resolveWriteTarget(String rel)
    {
        return resolveWriteTarget(rel, null);
    }

    /**
     * 校验某个库根下的「写入目标」路径：允许尚不存在，但规则与读取一致
     *
     * <p>用于软删后的恢复：rel 来自数据库的 rel_path / dir，仍须拒绝绝对路径、
     * 盘符、UNC、越出<b>所属库根</b>与站内目录 {@code _媒体站}；最近的已存在祖先还要
     * 过一次 realpath（防符号链接把写入点带出根外）。</p>
     *
     * @param rel    相对所属库根的路径
     * @param rootId 库根 id，null = 配置 {@code qtools.media.rootPath}
     * @return 规范化后的绝对路径（文件可以还不存在）
     */
    Path resolveWriteTarget(String rel, Integer rootId);

    /** 媒体根 realpath（默认根） */
    default Path mediaRoot()
    {
        return mediaRoot(null);
    }

    /**
     * 某个库根的 realpath
     *
     * @param rootId 库根 id，null = 配置 {@code qtools.media.rootPath}
     * @return realpath 之后的根目录
     * @throws com.qtools.media.exception.MediaException 500 配置缺失 / 404 根不存在或不可访问
     */
    Path mediaRoot(Integer rootId);

    /** 回收站根目录（{@code _媒体站\trash}），不创建、不校验存在 */
    Path trashRoot();

    /**
     * 回收站下按 kind 分类的目录（默认根的老结构，{@code _媒体站\trash\<kind>}），不存在时创建
     *
     * @param kind video / album / photo
     * @return 该分类的回收站目录
     */
    default Path resolveTrashDir(String kind)
    {
        return resolveTrashDir(kind, null);
    }

    /**
     * 某个库根在回收站里的分类目录（按 {@code root_id} 分桶）
     *
     * <p>回收站只有一个物理根（{@code _媒体站\trash}，缺省位于默认媒体根下），桶层级只负责
     * 记住「这份文件原属哪个<b>库根</b>」——否则第二根的文件会被搬进默认根的桶里，
     * 恢复时又写回默认根，位置就错位了：</p>
     * <ul>
     *   <li>{@code rootId} 非空 → {@code trash/<kind>/<rootId>/}；</li>
     *   <li>{@code rootId} 为 null（{@code root_id} 列为 NULL 的存量行）→ {@code trash/<kind>/}
     *       ——既有结构，老回收站条目照旧可读、可恢复。</li>
     * </ul>
     *
     * @param kind   video / album / photo
     * @param rootId 库根 id（{@code media_root.id}），null = 默认根老结构
     * @return 该分类（该根）的回收站目录
     */
    Path resolveTrashDir(String kind, Integer rootId);

    /**
     * 某个真实路径属于哪个库根
     *
     * <p>相册表没有 {@code root_id} 列，软删相册时靠它把「这个目录其实在第二根」推断出来；
     * 视频 / 图片优先用记录里的 {@code root_id}，不经过这里。</p>
     *
     * @param path 媒体库内的真实路径
     * @return {@code media_root.id}；落在默认根（配置 {@code qtools.media.rootPath}）里、
     *         已不存在或无法判断时返回 null
     */
    Integer rootIdOf(Path path);

    /** 把媒体根内的绝对路径转成相对路径（正斜杠，便于回给前端） */
    String relativize(Path path);

    /**
     * 定位视频缩略图：<b>内容寻址优先</b> thumbs/v/{content_hash}.jpg，
     * 命中不了再<b>回退</b>老命名 thumbs/v/{id}.jpg
     *
     * <p>对外 URL 始终是 /api/media/thumb/v/{id}，换文件只发生在服务端内部。</p>
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
