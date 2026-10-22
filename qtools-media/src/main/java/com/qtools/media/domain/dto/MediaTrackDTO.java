package com.qtools.media.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 媒体站事件埋点 上报入参
 *
 * <p>请求体形如：{@code {"eventType":"play","targetType":"video","targetKey":"a1b2c3d4e5f6",
 * "ownerKey":"ms-xxxx","valueNum":1234}}</p>
 *
 * <p>播放结束时额外带上进度：{@code {"eventType":"play_end","targetType":"video",
 * "targetKey":"a1b2c3d4e5f6","ownerKey":"ms-xxxx","valueNum":15300,"progress":100}}。</p>
 *
 * <p>本 DTO 的约束只用于声明契约：校验失败不会把错误抛给前端，控制器内部有
 * {@code @ExceptionHandler} 统一回 {@code AjaxResult.success()}（埋点失败绝不能影响页面）。</p>
 *
 * @author qtools
 */
public class MediaTrackDTO
{
    /** 事件类型：play|play_end|photo_click|album_view|page_view|dwell|fav|unfav */
    @NotBlank(message = "eventType 不能为空")
    @Size(max = 32, message = "eventType 长度不能超过 32")
    private String eventType;

    /** 目标类型：video|photo|album|page */
    @NotBlank(message = "targetType 不能为空")
    @Size(max = 16, message = "targetType 长度不能超过 16")
    private String targetType;

    /** 目标标识：video=media_video.id；photo=albumId:seq；album=album_id；page=路径 */
    private String targetKey;

    /** 设备/账号标识（与 media_owner.owner_key 一致） */
    private String ownerKey;

    /** 数值：播放时长ms / 停留时长ms / 其他数值，缺省 0 */
    private Long valueNum;

    /** 播放进度百分比 0-100（只有 play_end 有意义；服务端夹取到该区间，非 play_end 一律落 0） */
    private Integer progress;

    public String getEventType()
    {
        return eventType;
    }

    public void setEventType(String eventType)
    {
        this.eventType = eventType;
    }

    public String getTargetType()
    {
        return targetType;
    }

    public void setTargetType(String targetType)
    {
        this.targetType = targetType;
    }

    public String getTargetKey()
    {
        return targetKey;
    }

    public void setTargetKey(String targetKey)
    {
        this.targetKey = targetKey;
    }

    public String getOwnerKey()
    {
        return ownerKey;
    }

    public void setOwnerKey(String ownerKey)
    {
        this.ownerKey = ownerKey;
    }

    public Long getValueNum()
    {
        return valueNum;
    }

    public void setValueNum(Long valueNum)
    {
        this.valueNum = valueNum;
    }

    public Integer getProgress()
    {
        return progress;
    }

    public void setProgress(Integer progress)
    {
        this.progress = progress;
    }

    @Override
    public String toString()
    {
        return "MediaTrackDTO [eventType=" + eventType + ", targetType=" + targetType + ", targetKey=" + targetKey
                + ", ownerKey=" + ownerKey + ", valueNum=" + valueNum + ", progress=" + progress + "]";
    }
}
