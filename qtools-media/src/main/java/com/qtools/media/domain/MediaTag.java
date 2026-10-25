package com.qtools.media.domain;

import java.util.Date;

/**
 * 媒体标签字典 media_tag
 *
 * <p>与 {@link MediaVideoTag} 的区别：那个是「视频 ↔ 标签」的关联行，本类是标签本身的字典。
 * 关联行的 {@code facet} 固定为 {@code 标签}、{@code tag_id} 指向本表主键，
 * 因此历史 indices / 详情接口的 facet+name 读取路径不用改。</p>
 *
 * @author qtools
 */
public class MediaTag
{
    private Long id;

    /** 标签名（去首尾空格后 1-32 字符，库内唯一） */
    private String name;

    /** 父标签 id（可空；NULL = 顶层标签）。自引用 / 环 / 深度由服务层校验 */
    private Long parentId;

    /** 颜色（可空，建议 #RRGGBB） */
    private String color;

    /** 引用次数（关联行数，写入时重算） */
    private Integer useCount;

    private Date createTime;

    /**
     * 只读回填：从顶层到自身的路径，如 {@code 演员/日本/XX}（不落库）
     *
     * <p>由 {@code MediaOrganizeServiceImpl#tagList} 沿 parent 链现算，供前端直接展示层级
     * 而不必自己再拼一遍；环被截断成 {@code ...}。</p>
     */
    private String path;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public Long getParentId()
    {
        return parentId;
    }

    public void setParentId(Long parentId)
    {
        this.parentId = parentId;
    }

    public String getPath()
    {
        return path;
    }

    public void setPath(String path)
    {
        this.path = path;
    }

    public String getColor()
    {
        return color;
    }

    public void setColor(String color)
    {
        this.color = color;
    }

    public Integer getUseCount()
    {
        return useCount;
    }

    public void setUseCount(Integer useCount)
    {
        this.useCount = useCount;
    }

    public Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }
}
