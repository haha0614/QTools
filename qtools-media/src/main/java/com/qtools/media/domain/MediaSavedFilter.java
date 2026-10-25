package com.qtools.media.domain;

import java.util.Date;

/**
 * 站端保存的筛选条件 media_saved_filter（P2-A）
 *
 * <p>{@code params} 存的是筛选条件的<b>原始 JSON 文本</b>（长度上限 4KB，服务层校验）：
 * 站端的筛选组合（kind / 人物 / 标签 / 评分 / 排序 …）由前端自己解释，服务端不解析它的字段，
 * 只保证「是合法 JSON」与长度，这样前端加一个新筛选维度不需要后端跟着改表。</p>
 *
 * <p>{@code owner_key} 为匿名设备身份（见 {@code MediaOwnerService}）：NULL = 全局条目（所有访客可见，
 * 只能由管理端清理），非 NULL = 该设备私有。建表语句见 {@code doc/media_saved_filter.sql}。</p>
 *
 * @author qtools
 */
public class MediaSavedFilter
{
    /** 主键 */
    private Long id;

    /** 名称（同一 owner 下唯一，重复保存 = 覆盖）；1-64 字符 */
    private String name;

    /** 筛选条件原始 JSON 文本（&lt; 4KB） */
    private String params;

    /** 归属匿名设备；NULL = 全局 */
    private String ownerKey;

    private Date createTime;

    private Date updateTime;

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

    public String getParams()
    {
        return params;
    }

    public void setParams(String params)
    {
        this.params = params;
    }

    public String getOwnerKey()
    {
        return ownerKey;
    }

    public void setOwnerKey(String ownerKey)
    {
        this.ownerKey = ownerKey;
    }

    public Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }

    public Date getUpdateTime()
    {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime)
    {
        this.updateTime = updateTime;
    }
}
