package com.qtools.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户端账号配置（前缀 {@code qtools.app}）
 *
 * <p>与若依后台配置（{@code token.*}、{@code user.password.*}）完全分离：本模块只读 {@code qtools.app.*}，
 * 后台的 token 密钥、锁定时长对用户端无效。</p>
 *
 * @author qtools
 */
@Component
@ConfigurationProperties(prefix = "qtools.app")
public class AppProperties
{
    /** 用户端 JWT */
    private Jwt jwt = new Jwt();

    /** 注册开关 */
    private Register register = new Register();

    /** 登录开关 */
    private Login login = new Login();

    /** 验证码通道 */
    private Code code = new Code();

    /** 登录安全 */
    private Security security = new Security();

    public Jwt getJwt()
    {
        return jwt;
    }

    public void setJwt(Jwt jwt)
    {
        this.jwt = jwt;
    }

    public Register getRegister()
    {
        return register;
    }

    public void setRegister(Register register)
    {
        this.register = register;
    }

    public Login getLogin()
    {
        return login;
    }

    public void setLogin(Login login)
    {
        this.login = login;
    }

    public Code getCode()
    {
        return code;
    }

    public void setCode(Code code)
    {
        this.code = code;
    }

    public Security getSecurity()
    {
        return security;
    }

    public void setSecurity(Security security)
    {
        this.security = security;
    }

    /**
     * 用户端 JWT 配置
     *
     * <p>{@code secret} 只允许出现在 secret.yml（不入库、不入仓、不打进 jar），
     * 且必须与后台 {@code token.secret} 不同——两套身份互不承认是硬约束。</p>
     */
    public static class Jwt
    {
        /** 签名密钥（HS256，至少 32 字节） */
        private String secret;

        /** 签发者，必须为 qtools-app */
        private String issuer = "qtools-app";

        /** 有效期（天） */
        private int expireDays = 7;

        public String getSecret()
        {
            return secret;
        }

        public void setSecret(String secret)
        {
            this.secret = secret;
        }

        public String getIssuer()
        {
            return issuer;
        }

        public void setIssuer(String issuer)
        {
            this.issuer = issuer;
        }

        public int getExpireDays()
        {
            return expireDays;
        }

        public void setExpireDays(int expireDays)
        {
            this.expireDays = expireDays;
        }
    }

    /** 注册：{@code invite} 邀请码 / {@code open} 开放注册 */
    public static class Register
    {
        private String mode = "invite";

        /** 预留：邮箱注册 */
        private boolean email = false;

        /** 预留：手机号注册 */
        private boolean phone = false;

        public String getMode()
        {
            return mode;
        }

        public void setMode(String mode)
        {
            this.mode = mode;
        }

        public boolean isEmail()
        {
            return email;
        }

        public void setEmail(boolean email)
        {
            this.email = email;
        }

        public boolean isPhone()
        {
            return phone;
        }

        public void setPhone(boolean phone)
        {
            this.phone = phone;
        }

        /** 是否为邀请码注册模式 */
        public boolean isInviteMode()
        {
            return "invite".equalsIgnoreCase(mode);
        }
    }

    /** 登录方式开关 */
    public static class Login
    {
        /** 用户名 + 密码（主方式） */
        private boolean password = true;

        /** 预留：邮箱 + 密码 */
        private boolean emailPassword = false;

        /** 预留：手机号 + 验证码 */
        private boolean phoneCode = false;

        public boolean isPassword()
        {
            return password;
        }

        public void setPassword(boolean password)
        {
            this.password = password;
        }

        public boolean isEmailPassword()
        {
            return emailPassword;
        }

        public void setEmailPassword(boolean emailPassword)
        {
            this.emailPassword = emailPassword;
        }

        public boolean isPhoneCode()
        {
            return phoneCode;
        }

        public void setPhoneCode(boolean phoneCode)
        {
            this.phoneCode = phoneCode;
        }
    }

    /** 验证码通道（M6 才接入短信/邮件服务商，现在关闭） */
    public static class Code
    {
        private boolean enabled = false;

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }
    }

    /** 登录失败限流：同 IP + 标识，窗口内最多失败次数 */
    public static class Security
    {
        private int loginMaxAttempts = 5;

        private int loginWindowMinutes = 10;

        public int getLoginMaxAttempts()
        {
            return loginMaxAttempts;
        }

        public void setLoginMaxAttempts(int loginMaxAttempts)
        {
            this.loginMaxAttempts = loginMaxAttempts;
        }

        public int getLoginWindowMinutes()
        {
            return loginWindowMinutes;
        }

        public void setLoginWindowMinutes(int loginWindowMinutes)
        {
            this.loginWindowMinutes = loginWindowMinutes;
        }
    }
}
