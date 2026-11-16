package com.qtools.media.domain;

import java.math.BigDecimal;

/**
 * 人物卡封面：按「视图 × 作用域」保存的一份设置（P6.101）
 *
 * <p>为什么不是 media_person 上的几个列：用户要求「海报 / 大图 / 瀑布 / 紧凑四个视图
 * 各自独立，可以选不同的图，也可以同一张图取不同位置」，再加上管理端配的默认值，
 * 一个人物最多 4 × 2 行设置 —— 塞进人物行会变成 24 列且没法扩展视图。</p>
 *
 * @author qtools
 */
public class MediaPersonCover
{
    /** 人物 id */
    private Long personId;

    /** 视图：poster / hero / masonry / compact */
    private String view;

    /** 作用域：default（管理端）/ user（站端用户） */
    private String scope;

    /** 图源：auto（沿用头像三档）/ photo（名下照片）/ frame（视频帧） */
    private String sourceKind;

    /** photo = {@code <albumId>:<seq>[#crop=…]}；frame = {@code frame:<videoId>:<sec>[#crop=…]} */
    private String sourceRef;

    /** 缩放 0.5–3（NULL = 自动） */
    private BigDecimal bgScale;

    /** 水平偏移（卡片宽的比例，±2） */
    private BigDecimal bgDx;

    /** 垂直偏移（卡片高的比例，±2） */
    private BigDecimal bgDy;

    public Long getPersonId()
    {
        return personId;
    }

    public void setPersonId(Long personId)
    {
        this.personId = personId;
    }

    public String getView()
    {
        return view;
    }

    public void setView(String view)
    {
        this.view = view;
    }

    public String getScope()
    {
        return scope;
    }

    public void setScope(String scope)
    {
        this.scope = scope;
    }

    public String getSourceKind()
    {
        return sourceKind;
    }

    public void setSourceKind(String sourceKind)
    {
        this.sourceKind = sourceKind;
    }

    public String getSourceRef()
    {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef)
    {
        this.sourceRef = sourceRef;
    }

    public BigDecimal getBgScale()
    {
        return bgScale;
    }

    public void setBgScale(BigDecimal bgScale)
    {
        this.bgScale = bgScale;
    }

    public BigDecimal getBgDx()
    {
        return bgDx;
    }

    public void setBgDx(BigDecimal bgDx)
    {
        this.bgDx = bgDx;
    }

    public BigDecimal getBgDy()
    {
        return bgDy;
    }

    public void setBgDy(BigDecimal bgDy)
    {
        this.bgDy = bgDy;
    }
}
