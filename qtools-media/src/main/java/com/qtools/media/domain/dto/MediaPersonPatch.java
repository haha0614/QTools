package com.qtools.media.domain.dto;

import java.util.Date;

/**
 * 人物补丁（{@code POST /api/media/admin/persons}、{@code PUT /api/media/admin/persons/{id}}）
 *
 * <p>与 {@link MediaMetaPatch} 同一套写法：每个字段配一个 {@code xxxPresent} 标志，
 * 「出现即覆盖」——JSON 里显式给 {@code "alias": null} 是<b>有值且为 null</b>（清空该列），
 * 不能用 {@code getXxx() == null} 当成缺省。</p>
 *
 * <p>P5-A 追加 14 个资料字段（三围 / 出道年份 / 纹身 / 身高体重 / 社交账号 …），全部可空；
 * 数值范围（身高 100-250、体重 30-200、年份 1900-2100 且 end >= start）与长度上限
 * 由 {@code MediaPersonServiceImpl} 校验。</p>
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

    private boolean measurementsPresent;

    /** 三围 / 尺寸（可空，<= 128；null = 清空） */
    private String measurements;

    private boolean careerStartPresent;

    /** 出道年份（可空，1900-2100；null = 清空） */
    private Integer careerStart;

    private boolean careerEndPresent;

    /** 退役年份（可空，1900-2100 且 >= careerStart；null = 清空） */
    private Integer careerEnd;

    private boolean tattoosPresent;

    /** 纹身（可空，<= 255；null = 清空） */
    private String tattoos;

    private boolean piercingsPresent;

    /** 穿孔 / 饰钉（可空，<= 255；null = 清空） */
    private String piercings;

    private boolean ethnicityPresent;

    /** 族裔（可空，<= 32；null = 清空） */
    private String ethnicity;

    private boolean hairColorPresent;

    /** 发色（可空，<= 32；null = 清空） */
    private String hairColor;

    private boolean eyeColorPresent;

    /** 瞳色（可空，<= 32；null = 清空） */
    private String eyeColor;

    private boolean heightCmPresent;

    /** 身高 cm（可空，100-250；null = 清空） */
    private Integer heightCm;

    private boolean weightKgPresent;

    /** 体重 kg（可空，30-200；null = 清空） */
    private Integer weightKg;

    private boolean fakeBoobsPresent;

    /** 是否隆胸（可空 = 未标注；null = 清空） */
    private Boolean fakeBoobs;

    private boolean nationalityPresent;

    /** 国籍（可空，<= 64；null = 清空） */
    private String nationality;

    private boolean twitterPresent;

    /** X / Twitter（可空，<= 128；null = 清空） */
    private String twitter;

    private boolean instagramPresent;

    /** Instagram（可空，<= 128；null = 清空） */
    private String instagram;

    // ------------------------------------------------------------------ 访问器

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
    /** 是否有任意一个 P5-A 资料字段出现（服务层据此决定要不要跑资料 UPDATE） */
    public boolean isProfilePresent()
    {
        return measurementsPresent
                || careerStartPresent
                || careerEndPresent
                || tattoosPresent
                || piercingsPresent
                || ethnicityPresent
                || hairColorPresent
                || eyeColorPresent
                || heightCmPresent
                || weightKgPresent
                || fakeBoobsPresent
                || nationalityPresent
                || twitterPresent
                || instagramPresent;
    }

    public boolean isMeasurementsPresent()
    {
        return measurementsPresent;
    }

    public void setMeasurementsPresent(boolean measurementsPresent)
    {
        this.measurementsPresent = measurementsPresent;
    }

    public String getMeasurements()
    {
        return measurements;
    }

    public void setMeasurements(String measurements)
    {
        this.measurements = measurements;
    }

    public boolean isCareerStartPresent()
    {
        return careerStartPresent;
    }

    public void setCareerStartPresent(boolean careerStartPresent)
    {
        this.careerStartPresent = careerStartPresent;
    }

    public Integer getCareerStart()
    {
        return careerStart;
    }

    public void setCareerStart(Integer careerStart)
    {
        this.careerStart = careerStart;
    }

    public boolean isCareerEndPresent()
    {
        return careerEndPresent;
    }

    public void setCareerEndPresent(boolean careerEndPresent)
    {
        this.careerEndPresent = careerEndPresent;
    }

    public Integer getCareerEnd()
    {
        return careerEnd;
    }

    public void setCareerEnd(Integer careerEnd)
    {
        this.careerEnd = careerEnd;
    }

    public boolean isTattoosPresent()
    {
        return tattoosPresent;
    }

    public void setTattoosPresent(boolean tattoosPresent)
    {
        this.tattoosPresent = tattoosPresent;
    }

    public String getTattoos()
    {
        return tattoos;
    }

    public void setTattoos(String tattoos)
    {
        this.tattoos = tattoos;
    }

    public boolean isPiercingsPresent()
    {
        return piercingsPresent;
    }

    public void setPiercingsPresent(boolean piercingsPresent)
    {
        this.piercingsPresent = piercingsPresent;
    }

    public String getPiercings()
    {
        return piercings;
    }

    public void setPiercings(String piercings)
    {
        this.piercings = piercings;
    }

    public boolean isEthnicityPresent()
    {
        return ethnicityPresent;
    }

    public void setEthnicityPresent(boolean ethnicityPresent)
    {
        this.ethnicityPresent = ethnicityPresent;
    }

    public String getEthnicity()
    {
        return ethnicity;
    }

    public void setEthnicity(String ethnicity)
    {
        this.ethnicity = ethnicity;
    }

    public boolean isHairColorPresent()
    {
        return hairColorPresent;
    }

    public void setHairColorPresent(boolean hairColorPresent)
    {
        this.hairColorPresent = hairColorPresent;
    }

    public String getHairColor()
    {
        return hairColor;
    }

    public void setHairColor(String hairColor)
    {
        this.hairColor = hairColor;
    }

    public boolean isEyeColorPresent()
    {
        return eyeColorPresent;
    }

    public void setEyeColorPresent(boolean eyeColorPresent)
    {
        this.eyeColorPresent = eyeColorPresent;
    }

    public String getEyeColor()
    {
        return eyeColor;
    }

    public void setEyeColor(String eyeColor)
    {
        this.eyeColor = eyeColor;
    }

    public boolean isHeightCmPresent()
    {
        return heightCmPresent;
    }

    public void setHeightCmPresent(boolean heightCmPresent)
    {
        this.heightCmPresent = heightCmPresent;
    }

    public Integer getHeightCm()
    {
        return heightCm;
    }

    public void setHeightCm(Integer heightCm)
    {
        this.heightCm = heightCm;
    }

    public boolean isWeightKgPresent()
    {
        return weightKgPresent;
    }

    public void setWeightKgPresent(boolean weightKgPresent)
    {
        this.weightKgPresent = weightKgPresent;
    }

    public Integer getWeightKg()
    {
        return weightKg;
    }

    public void setWeightKg(Integer weightKg)
    {
        this.weightKg = weightKg;
    }

    public boolean isFakeBoobsPresent()
    {
        return fakeBoobsPresent;
    }

    public void setFakeBoobsPresent(boolean fakeBoobsPresent)
    {
        this.fakeBoobsPresent = fakeBoobsPresent;
    }

    public Boolean getFakeBoobs()
    {
        return fakeBoobs;
    }

    public void setFakeBoobs(Boolean fakeBoobs)
    {
        this.fakeBoobs = fakeBoobs;
    }

    public boolean isNationalityPresent()
    {
        return nationalityPresent;
    }

    public void setNationalityPresent(boolean nationalityPresent)
    {
        this.nationalityPresent = nationalityPresent;
    }

    public String getNationality()
    {
        return nationality;
    }

    public void setNationality(String nationality)
    {
        this.nationality = nationality;
    }

    public boolean isTwitterPresent()
    {
        return twitterPresent;
    }

    public void setTwitterPresent(boolean twitterPresent)
    {
        this.twitterPresent = twitterPresent;
    }

    public String getTwitter()
    {
        return twitter;
    }

    public void setTwitter(String twitter)
    {
        this.twitter = twitter;
    }

    public boolean isInstagramPresent()
    {
        return instagramPresent;
    }

    public void setInstagramPresent(boolean instagramPresent)
    {
        this.instagramPresent = instagramPresent;
    }

    public String getInstagram()
    {
        return instagram;
    }

    public void setInstagram(String instagram)
    {
        this.instagram = instagram;
    }
}
