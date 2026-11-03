package com.qtools.common.security;

/**
 * 用户端账号的可见性档案（P6.26）：角色 + 可见内容上限。
 *
 * <p>为什么单独一个类而不是两个方法：媒体模块每次请求都要判「这个人能看哪几档」，
 * 拆成两次查询会在每条图片 / 每个索引请求上多一次 SQL；一次取回 role + maxRating 最省。</p>
 *
 * @author qtools
 */
public class AppUserProfile
{
    /** user / owner（owner = 站长，不受 maxRating 限制） */
    private final String role;

    /** general / guidance / restricted；null 视同 guidance */
    private final String maxRating;

    public AppUserProfile(String role, String maxRating)
    {
        this.role = role;
        this.maxRating = maxRating;
    }

    public String getRole()
    {
        return role;
    }

    public String getMaxRating()
    {
        return maxRating;
    }

    public boolean isOwner()
    {
        return role != null && "owner".equalsIgnoreCase(role.trim());
    }
}
