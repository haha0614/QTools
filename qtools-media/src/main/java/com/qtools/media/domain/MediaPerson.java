package com.qtools.media.domain;

import java.util.Date;

/**
 * 媒体人物 media_person
 *
 * <p>P3-A 起人物是一等实体：既有列（id / name / sort / hidden / created_at）之外补了
 * alias / note / avatar_path / gender / birthday / use_count，并作为
 * {@code media_video_actor.person_id} 与 {@code media_album_person.person_id} 的参照目标。</p>
 *
 * <p>{@code useCount} 是冗余计数（引用它的 media_video_actor 行数），写入时按实际行数重算；
 * {@code videoCount / albumCount} 只在管理端列表 / 详情里回填，不落库。</p>
 *
 * @author qtools
 */
public class MediaPerson
{
    private Long id;

    /** 人物名（去首尾空格后 1-64 字符，库内唯一 —— 列宽 120 留了余量） */
    private String name;

    /** 别名（可空，<= 128 字符） */
    private String alias;

    /** 备注（可空，<= 512 字符） */
    private String note;

    /** 头像路径（可空，<= 400；站内相对路径或 http(s) URL） */
    private String avatarPath;

    /** 性别（可空，<= 16；自由文本，建议 女 / 男 / 未知） */
    private String gender;

    /** 生日（可空） */
    private Date birthday;

    /** 引用次数（media_video_actor 中 person_id = 本 id 的行数，写入时重算） */
    private Integer useCount;

    private Integer sort;

    /** 只读回填：该人物名下的视频数（不落库） */
    private Long videoCount;

    /** 只读回填：该人物名下的相册数（不落库） */
    private Long albumCount;

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

    public String getAlias()
    {
        return alias;
    }

    public void setAlias(String alias)
    {
        this.alias = alias;
    }

    public String getNote()
    {
        return note;
    }

    public void setNote(String note)
    {
        this.note = note;
    }

    public String getAvatarPath()
    {
        return avatarPath;
    }

    public void setAvatarPath(String avatarPath)
    {
        this.avatarPath = avatarPath;
    }

    public String getGender()
    {
        return gender;
    }

    public void setGender(String gender)
    {
        this.gender = gender;
    }

    public Date getBirthday()
    {
        return birthday;
    }

    public void setBirthday(Date birthday)
    {
        this.birthday = birthday;
    }

    public Integer getUseCount()
    {
        return useCount;
    }

    public void setUseCount(Integer useCount)
    {
        this.useCount = useCount;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
    }

    public Long getVideoCount()
    {
        return videoCount;
    }

    public void setVideoCount(Long videoCount)
    {
        this.videoCount = videoCount;
    }

    public Long getAlbumCount()
    {
        return albumCount;
    }

    public void setAlbumCount(Long albumCount)
    {
        this.albumCount = albumCount;
    }
}
