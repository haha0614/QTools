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

    /**
     * 手动头像来源类型（P5.48，可空）：{@code photo} = 人物名下相册里的一张图 / {@code url} = 外部图片地址
     *
     * <p>与 {@link #avatarPath} 并存：老列照旧可读写（管理端 {@code POST /admin/persons/{id}/avatar}），
     * 手动头像是<b>更高优先级</b>的一层，解析顺序见 {@code MediaPersonServiceImpl#avatarUrlOf}。</p>
     */
    private String avatarKind;

    /**
     * 手动头像来源标识（P5.48，可空，<= 512）：<b>不存二进制</b>
     *
     * <ul>
     *   <li>{@code avatarKind = photo} → {@code "<albumId>:<seq>"}（如 {@code a164:0}）；
     *       用自然键而不是 {@code media_photo.id}，重扫后 id 会变、自然键不会</li>
     *   <li>{@code avatarKind = url} → {@code https://…} / {@code http://…} / {@code data:image/…}</li>
     * </ul>
     */
    private String avatarRef;

    /** 性别（可空，<= 16；自由文本，建议 女 / 男 / 未知） */
    private String gender;

    /** 生日（可空） */
    private Date birthday;

    /** 三围 / 尺寸等自由文本（可空，<= 128 字符；P5-A 资料字段） */
    private String measurements;

    /**
     * 人物卡背景缩放（P5.89，可空）：{@code 1} = 自动几何，值域 0.5–3
     *
     * <p>站端人物墙的卡片背景调整原本只存在 P5.88 的前端 localStorage。P5.89 起站长（role=owner）
     * 的调整落服务端这三列，渲染优先级：服务端值 &gt; localStorage &gt; 自动几何。</p>
     */
    private java.math.BigDecimal bgScale;

    /** 人物卡背景水平偏移（P5.89，可空）：卡片**宽度**的比例，±2 = ±200%，4 位小数 */
    private java.math.BigDecimal bgDx;

    /** 人物卡背景垂直偏移（P5.89，可空）：卡片**高度**的比例，±2 = ±200%，4 位小数 */
    private java.math.BigDecimal bgDy;

    /** 出道年份（可空，1900-2100；与 careerEnd 同时非空时要求 end >= start） */
    private Integer careerStart;

    /** 退役 / 结束年份（可空，1900-2100） */
    private Integer careerEnd;

    /** 纹身（可空，<= 255 字符） */
    private String tattoos;

    /** 穿孔 / 饰钉（可空，<= 255 字符） */
    private String piercings;

    /** 族裔（可空，<= 32 字符） */
    private String ethnicity;

    /** 发色（可空，<= 32 字符） */
    private String hairColor;

    /** 瞳色（可空，<= 32 字符） */
    private String eyeColor;

    /** 身高 cm（可空，100-250） */
    private Integer heightCm;

    /** 体重 kg（可空，30-200） */
    private Integer weightKg;

    /** 是否隆胸（可空 = 未标注；TINYINT(1) NULL 三态） */
    private Boolean fakeBoobs;

    /** 国籍（可空，<= 64 字符） */
    private String nationality;

    /** X / Twitter 账号或链接（可空，<= 128 字符） */
    private String twitter;

    /** Instagram 账号或链接（可空，<= 128 字符） */
    private String instagram;

    /** 引用次数（media_video_actor 中 person_id = 本 id 的行数，写入时重算） */
    private Integer useCount;

    private Integer sort;

    /** 只读回填：该人物名下的视频数（不落库） */
    private Long videoCount;

    /** 只读回填：该人物名下的相册数（不落库） */
    private Long albumCount;

    /** 只读回填：该人物名下**可见**相册的相片总数（P5.65，不落库；0 也输出） */
    private Long photoCount;

    /** 只读回填：名下第一个相册的封面相对路径（P5.65，不落库；无封面时 null = 整键不输出） */
    private String coverPath;

    /** 只读回填：名下第一个可见视频的 id（P5.65，不落库；无视频时 null = 整键不输出） */
    private String coverVideoId;

    public String getMeasurements()
    {
        return measurements;
    }

    public void setMeasurements(String measurements)
    {
        this.measurements = measurements;
    }

    public Integer getCareerStart()
    {
        return careerStart;
    }

    public void setCareerStart(Integer careerStart)
    {
        this.careerStart = careerStart;
    }

    public Integer getCareerEnd()
    {
        return careerEnd;
    }

    public void setCareerEnd(Integer careerEnd)
    {
        this.careerEnd = careerEnd;
    }

    public String getTattoos()
    {
        return tattoos;
    }

    public void setTattoos(String tattoos)
    {
        this.tattoos = tattoos;
    }

    public String getPiercings()
    {
        return piercings;
    }

    public void setPiercings(String piercings)
    {
        this.piercings = piercings;
    }

    public String getEthnicity()
    {
        return ethnicity;
    }

    public void setEthnicity(String ethnicity)
    {
        this.ethnicity = ethnicity;
    }

    public String getHairColor()
    {
        return hairColor;
    }

    public void setHairColor(String hairColor)
    {
        this.hairColor = hairColor;
    }

    public String getEyeColor()
    {
        return eyeColor;
    }

    public void setEyeColor(String eyeColor)
    {
        this.eyeColor = eyeColor;
    }

    public Integer getHeightCm()
    {
        return heightCm;
    }

    public void setHeightCm(Integer heightCm)
    {
        this.heightCm = heightCm;
    }

    public Integer getWeightKg()
    {
        return weightKg;
    }

    public void setWeightKg(Integer weightKg)
    {
        this.weightKg = weightKg;
    }

    public Boolean getFakeBoobs()
    {
        return fakeBoobs;
    }

    public void setFakeBoobs(Boolean fakeBoobs)
    {
        this.fakeBoobs = fakeBoobs;
    }

    public String getNationality()
    {
        return nationality;
    }

    public void setNationality(String nationality)
    {
        this.nationality = nationality;
    }

    public String getTwitter()
    {
        return twitter;
    }

    public void setTwitter(String twitter)
    {
        this.twitter = twitter;
    }

    public String getInstagram()
    {
        return instagram;
    }

    public void setInstagram(String instagram)
    {
        this.instagram = instagram;
    }

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

    public String getAvatarKind()
    {
        return avatarKind;
    }

    public void setAvatarKind(String avatarKind)
    {
        this.avatarKind = avatarKind;
    }

    public String getAvatarRef()
    {
        return avatarRef;
    }

    public void setAvatarRef(String avatarRef)
    {
        this.avatarRef = avatarRef;
    }

    public java.math.BigDecimal getBgScale()
    {
        return bgScale;
    }

    public void setBgScale(java.math.BigDecimal bgScale)
    {
        this.bgScale = bgScale;
    }

    public java.math.BigDecimal getBgDx()
    {
        return bgDx;
    }

    public void setBgDx(java.math.BigDecimal bgDx)
    {
        this.bgDx = bgDx;
    }

    public java.math.BigDecimal getBgDy()
    {
        return bgDy;
    }

    public void setBgDy(java.math.BigDecimal bgDy)
    {
        this.bgDy = bgDy;
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

    public Long getPhotoCount()
    {
        return photoCount;
    }

    public void setPhotoCount(Long photoCount)
    {
        this.photoCount = photoCount;
    }

    public String getCoverPath()
    {
        return coverPath;
    }

    public void setCoverPath(String coverPath)
    {
        this.coverPath = coverPath;
    }

    public String getCoverVideoId()
    {
        return coverVideoId;
    }

    public void setCoverVideoId(String coverVideoId)
    {
        this.coverVideoId = coverVideoId;
    }
}
