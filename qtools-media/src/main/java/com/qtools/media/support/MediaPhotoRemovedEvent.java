package com.qtools.media.support;

import org.springframework.context.ApplicationEvent;

/**
 * 照片行被删除（进回收站 + 删行）之后发布（P6.78.1）
 *
 * <p>为什么需要事件：{@code MediaDuplicateNearController} 把"相似分组"（O(n²) 并查集）缓存在内存里，
 * 而删照片的入口不止一个（重复文件页的「清理这份」、相似图片页、按图改归属的「只保留一份」、回收站…）。
 * 任何一个入口删了行，缓存里的分组就过期——表现就是"清理后那项还在列表里，再点一次报「照片不存在」"。
 * 让删除方发一个事件、缓存方自己清，比在每个调用点手动清缓存可靠。</p>
 */
public class MediaPhotoRemovedEvent extends ApplicationEvent
{
    private final long photoId;

    public MediaPhotoRemovedEvent(Object source, long photoId)
    {
        super(source);
        this.photoId = photoId;
    }

    public long getPhotoId()
    {
        return photoId;
    }
}
