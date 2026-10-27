package com.qtools.media.service;

import java.util.Map;
import com.qtools.media.domain.MediaIndexSnapshot;

/**
 * 媒体索引聚合 服务层
 *
 * @author qtools
 */
public interface IMediaIndexService
{
    /**
     * 取索引快照（缓存 60s，含已序列化字节体与 ETag）
     *
     * @return 快照
     */
    MediaIndexSnapshot indexSnapshot();

    /**
     * 取索引快照（同上），但可以要求服务端过滤缺失文件
     *
     * @param includeMissing true（默认，老行为）= 缺失行照旧带出，由前端自行过滤；
     *                       false = 服务端把 {@code media_photo/missing=1}、{@code media_video/missing=1}
     *                       的行从 videos / albums[].photos 里滤掉
     * @return 快照（两种取值各自缓存，ETag 不同）
     */
    MediaIndexSnapshot indexSnapshot(boolean includeMissing);

    /**
     * 取索引快照，并按「未登录可见性」筛掉收紧库根下的内容（P5.78）
     *
     * <p>{@code guest = true} 时：{@code media_root.guest_visible = 0} 的根下的视频 / 相册 / 图片
     * 整行不出现在 videos / albums / albums[].photos 里，{@code stats} 与 people 的计数
     * 也随之下降（人物节点由相册聚合而来，相册被筛掉后自然减少）。</p>
     *
     * <p>两种访客身份<b>各存一份缓存</b>：未登录与登录拿到的 ETag 不同，
     * 浏览器 / CDN 不会把「登录后那份」当成未登录那份的 304。</p>
     *
     * @param includeMissing 是否带出缺失行
     * @param guest          是否未登录访客（无有效 app token）
     * @return 快照
     */
    MediaIndexSnapshot indexSnapshot(boolean includeMissing, boolean guest);

    /**
     * 单片详情（含 tags/actors）
     *
     * @param id 视频 id
     * @return 与 index.videos[] 同构的节点
     */
    Map<String, Object> selectVideoDetail(String id);

    /**
     * 单册详情（含 photos，seq 升序）
     *
     * @param id 相册 id
     * @return 与 index.albums[] 同构的节点
     */
    Map<String, Object> selectAlbumDetail(String id);

    /**
     * 单册详情（同上），可要求服务端过滤缺失的图片行
     *
     * @param id             相册 id
     * @param includeMissing true（默认）= 带出缺失行；false = 只给文件还在的图片
     * @return 与 index.albums[] 同构的节点
     */
    Map<String, Object> selectAlbumDetail(String id, boolean includeMissing);

    /**
     * 站端匿名读视频元信息（P5.50）：{@code {id,rating,tags,persons,studios,markers,fps,dur}}
     *
     * <p>播放器信息栏原来只能靠管理端 {@code GET /admin/video/{id}/meta} 读标记，站端（匿名）
     * 永远拿不到，标记功能在站上一律降级成一句「标记需要管理员身份读取」。本方法复用
     * 索引侧的只读查询给站端一个匿名入口：<b>只读、不看编辑策略</b>。</p>
     *
     * <p>口径：{@code hidden = 1} 与不存在的 id 一样回 404（与 {@code GET /media/video/{id}} 同）；
     * 只输出渲染用字段，管理端专属列（missing / mtime / 相对路径 / 哈希 / 扫描状态）一概不出。</p>
     *
     * @param id 视频 id
     * @return 元信息节点；视频不存在或已 hidden 抛 404
     */
    Map<String, Object> selectVideoMeta(String id);

    /**
     * 丢弃索引快照缓存
     *
     * <p>管理端改了 hidden / 人物归属或跑完扫描后调用：索引本身按 {@code hidden = 0} 过滤，
     * 若不清缓存，用户端最多 60s 内仍能看到刚隐藏的对象。</p>
     */
    void invalidateSnapshot();
}
