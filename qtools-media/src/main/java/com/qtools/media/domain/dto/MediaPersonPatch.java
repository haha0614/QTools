package com.qtools.media.domain.dto;

import java.util.Date;

/**
 * 人物补丁（{@code POST /api/media/admin/persons}、{@code PUT /api/media/admin/persons/{id}}）
 *
 * <p>与 {@link MediaMetaPatch} 同一套写法：每个字段配一个 {@code xxxPresent} 标志，
 * 「出现即覆盖」——JSON 里显式给 {@code "alias": null} 是<b>有值且为 null</b>（清空该列），
 * 不能用 {@code getXxx() == null} 当成缺省。</p>
 *
 * @author qtools
 */
public class MediaPersonPatch
{
    private boolean namePresent;

    private String name;

    private boolean aliasPresent;

    private String alias;

    private boolean notePresent;

    private String note;

    private boolean genderPresent;

    private String gender;

    private boolean birthdayPresent;

    /** yyyy-MM-dd；null = 清空生日 */
    private Date birthday;

    private boolean avatarPresent;

    private String avatarPath;

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

    public boolean isGenderPresent()
    {
        return genderPresent;
    }

    public void setGenderPresent(boolean genderPresent)
    {
        this.genderPresent = genderPresent;
    }

    public String getGender()
    {
        return gender;
    }

    public void setGender(String gender)
    {
        this.gender = gender;
    }

    public boolean isBirthdayPresent()
    {
        return birthdayPresent;
    }

    public void setBirthdayPresent(boolean birthdayPresent)
    {
        this.birthdayPresent = birthdayPresent;
    }

    public Date getBirthday()
    {
        return birthday;
    }

    public void setBirthday(Date birthday)
    {
        this.birthday = birthday;
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
}
