package com.qtools.media.domain.dto;

/**
 * 工作室补丁（{@code POST /api/media/admin/studios}、{@code PUT /api/media/admin/studios/{id}}）
 *
 * <p>与 {@link MediaPersonPatch} 同一套写法：每个字段配一个 {@code xxxPresent} 标志，
 * 「出现即覆盖」—— JSON 里显式给 {@code "alias": null} 是<b>有值且为 null</b>（清空该列），
 * 不能用 {@code getXxx() == null} 当成缺省。</p>
 *
 * @author qtools
 */
public class MediaStudioPatch
{
    private boolean namePresent;

    private String name;

    private boolean aliasPresent;

    private String alias;

    private boolean notePresent;

    private String note;

    private boolean avatarPresent;

    private String avatarPath;

    private boolean sortPresent;

    private Integer sort;

    private boolean hiddenPresent;

    private Boolean hidden;

    public boolean isNamePresent()
    {
        return namePresent;
    }

    public void setNamePresent(boolean namePresent)
    {
        this.namePresent = namePresent;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public boolean isAliasPresent()
    {
        return aliasPresent;
    }

    public void setAliasPresent(boolean aliasPresent)
    {
        this.aliasPresent = aliasPresent;
    }

    public String getAlias()
    {
        return alias;
    }

    public void setAlias(String alias)
    {
        this.alias = alias;
    }

    public boolean isNotePresent()
    {
        return notePresent;
    }

    public void setNotePresent(boolean notePresent)
    {
        this.notePresent = notePresent;
    }

    public String getNote()
    {
        return note;
    }

    public void setNote(String note)
    {
        this.note = note;
    }

    public boolean isAvatarPresent()
    {
        return avatarPresent;
    }

    public void setAvatarPresent(boolean avatarPresent)
    {
        this.avatarPresent = avatarPresent;
    }

    public String getAvatarPath()
    {
        return avatarPath;
    }

    public void setAvatarPath(String avatarPath)
    {
        this.avatarPath = avatarPath;
    }

    public boolean isSortPresent()
    {
        return sortPresent;
    }

    public void setSortPresent(boolean sortPresent)
    {
        this.sortPresent = sortPresent;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
    }

    public boolean isHiddenPresent()
    {
        return hiddenPresent;
    }

    public void setHiddenPresent(boolean hiddenPresent)
    {
        this.hiddenPresent = hiddenPresent;
    }

    public Boolean getHidden()
    {
        return hidden;
    }

    public void setHidden(Boolean hidden)
    {
        this.hidden = hidden;
    }
}
