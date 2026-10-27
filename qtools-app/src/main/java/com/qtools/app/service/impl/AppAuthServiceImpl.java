package com.qtools.app.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qtools.app.config.AppProperties;
import com.qtools.app.domain.AppUser;
import com.qtools.app.domain.AppUserIdentity;
import com.qtools.app.domain.AppUserToken;
import com.qtools.app.domain.AppVerifyCode;
import com.qtools.app.exception.AppException;
import com.qtools.app.mapper.AppInviteCodeMapper;
import com.qtools.app.mapper.AppUserIdentityMapper;
import com.qtools.app.mapper.AppUserMapper;
import com.qtools.app.mapper.AppUserTokenMapper;
import com.qtools.app.mapper.AppVerifyCodeMapper;
import com.qtools.app.security.AppJwtService;
import com.qtools.app.security.AppUserGuard;
import com.qtools.app.security.LoginAttemptGuard;
import com.qtools.app.service.IAppAuthService;
import com.qtools.common.utils.ip.IpUtils;

/**
 * 用户端账号 业务实现
 *
 * <p>密码哈希用 {@code org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}（cost=10，
 * 与 app_schema.sql 的注释一致）：spring-security-crypto 由 qtools-common 的
 * spring-boot-starter-security 传递提供，不新增依赖。</p>
 *
 * <p>刻意不复用若依的 {@code SysLoginService} / {@code TokenService} / {@code user.password.*}：
 * 后台与用户端是两套身份体系（表、密钥、过滤器、限流全部独立）。</p>
 *
 * @author qtools
 */
@Service
public class AppAuthServiceImpl implements IAppAuthService
{
    private static final Logger log = LoggerFactory.getLogger(AppAuthServiceImpl.class);

    /** 用户名：3-32 位字母、数字、下划线 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");

    private static final Pattern LETTER_PATTERN = Pattern.compile("[A-Za-z]");

    private static final Pattern DIGIT_PATTERN = Pattern.compile("[0-9]");

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private static final int PASSWORD_MIN = 8;

    /** BCrypt 只取前 72 字节，超长直接拒绝而不是悄悄截断 */
    private static final int PASSWORD_MAX = 64;

    private static final int NICKNAME_MAX = 64;

    private static final int IDENTIFIER_MAX = 128;

    private static final int USER_AGENT_MAX = 255;

    private static final int VERIFY_CODE_MAX_ATTEMPTS = 5;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 统一失败文案：不区分「用户不存在」与「密码错误」，避免枚举账号 */
    private static final String LOGIN_FAILED = "用户名或密码错误";

    /** 注册成功但待审核的提示（前端原样显示） */
    private static final String REGISTER_PENDING_MESSAGE = "注册成功，等待站长审核";

    /** cost=10，与 app_user.password_hash 的列注释一致 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    @Autowired
    private AppProperties properties;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private AppUserIdentityMapper appUserIdentityMapper;

    @Autowired
    private AppUserTokenMapper appUserTokenMapper;

    @Autowired
    private AppInviteCodeMapper appInviteCodeMapper;

    @Autowired
    private AppVerifyCodeMapper appVerifyCodeMapper;

    @Autowired
    private AppJwtService appJwtService;

    @Autowired
    private LoginAttemptGuard loginAttemptGuard;

    /** 审核状态判定（P5.79）：注册写 pending、登录拦 pending/rejected 都走它，口径只一处 */
    @Autowired
    private AppUserGuard appUserGuard;

    // ------------------------------------------------------------------ 注册

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> register(Map<String, Object> body, HttpServletRequest request)
    {
        String username = text(body.get("username"));
        String password = text(body.get("password"));
        String nickname = text(body.get("nickname"));
        String inviteCode = text(body.get("inviteCode"));

        // 预留通道：现在一律 501，避免"提交了邮箱看起来成功、其实没绑上"
        if (text(body.get("email")) != null || text(body.get("phone")) != null)
        {
            throw AppException.notImplemented("邮箱/手机号注册尚未启用，请使用用户名注册");
        }
        if (username == null || !USERNAME_PATTERN.matcher(username).matches())
        {
            throw AppException.badRequest("用户名需为 3-32 位字母、数字或下划线");
        }
        checkPasswordStrength(password);
        if (nickname == null)
        {
            nickname = username;
        }
        else if (nickname.length() > NICKNAME_MAX)
        {
            throw AppException.badRequest("昵称最长 " + NICKNAME_MAX + " 个字符");
        }

        boolean inviteMode = properties.getRegister().isInviteMode();
        if (inviteMode)
        {
            if (inviteCode == null)
            {
                throw AppException.badRequest("邀请码不能为空");
            }
            if (inviteCode.length() > 32)
            {
                throw AppException.badRequest("邀请码无效或已用尽");
            }
            // 原子占用：不存在 / 已过期 / 次数已满 都在 SQL 的 where 里判定
            if (appInviteCodeMapper.increaseUsedCount(inviteCode) <= 0)
            {
                throw AppException.badRequest("邀请码无效或已用尽");
            }
        }
        else
        {
            inviteCode = null;
        }

        if (appUserMapper.selectByUsername(username) != null)
        {
            throw AppException.conflict("用户名已存在");
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPasswordHash(passwordEncoder.encode(password));
        // P5.79：邀请码只挡垃圾注册，不再等于「放行」——注册一律先进待审核队列
        user.setStatus(AppUserGuard.STATUS_PENDING);
        user.setInviteCode(inviteCode);
        user.setRegisterIp(trim(IpUtils.getIpAddr(request), 64));
        try
        {
            appUserMapper.insert(user);
        }
        catch (DuplicateKeyException e)
        {
            // 并发注册同名：唯一键兜底
            throw AppException.conflict("用户名已存在");
        }

        AppUserIdentity identity = new AppUserIdentity();
        identity.setUserId(user.getId());
        identity.setType(AppUserIdentity.TYPE_USERNAME);
        identity.setIdentifier(username);
        identity.setVerified(1);
        appUserIdentityMapper.insert(identity);

        log.info("用户端注册成功（待审核） userId={} username={} invite={} ip={}", user.getId(), username,
                inviteMode ? "yes" : "no", user.getRegisterIp());

        // 注册成功**不发 token**：账号还没通过审核，任何登录态都不该被创建（前端据此提示等待审核）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", userView(user));
        result.put("pending", true);
        result.put("message", REGISTER_PENDING_MESSAGE);
        return result;
    }

    // ------------------------------------------------------------------ 登录

    @Override
    public Map<String, Object> login(Map<String, Object> body, HttpServletRequest request)
    {
        String identifier = text(body.get("identifier"));
        String password = text(body.get("password"));
        if (identifier == null || password == null)
        {
            throw AppException.badRequest("用户名和密码不能为空");
        }
        if (identifier.length() > IDENTIFIER_MAX || password.length() > PASSWORD_MAX)
        {
            throw AppException.badRequest("用户名或密码不合法");
        }
        String ip = IpUtils.getIpAddr(request);
        String attemptKey = LoginAttemptGuard.key(ip, identifier);
        if (loginAttemptGuard.isBlocked(attemptKey))
        {
            throw AppException.tooManyRequests("登录失败次数过多，请稍后再试");
        }
        if (!properties.getLogin().isPassword())
        {
            throw AppException.notImplemented("密码登录未启用");
        }

        AppUser user = appUserMapper.selectByUsername(identifier);
        if (user == null)
        {
            // 依次尝试 email / phone 身份；对应开关关闭时视作登录失败（不泄露该标识是否被绑定）
            AppUser byEmail = appUserMapper.selectByIdentity(AppUserIdentity.TYPE_EMAIL, identifier);
            if (byEmail != null && properties.getLogin().isEmailPassword())
            {
                user = byEmail;
            }
            else if (byEmail == null)
            {
                AppUser byPhone = appUserMapper.selectByIdentity(AppUserIdentity.TYPE_PHONE, identifier);
                if (byPhone != null && properties.getLogin().isPhoneCode())
                {
                    user = byPhone;
                }
            }
        }
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash()))
        {
            loginAttemptGuard.recordFailure(attemptKey);
            log.warn("用户端登录失败 identifier={} ip={}", identifier, ip);
            throw AppException.unauthorized(LOGIN_FAILED);
        }
        // P5.79：密码正确之后才看审核状态——顺序不能反，否则等于向未持密码的人泄露账号状态
        String blocked = appUserGuard.blockReason(user);
        if (blocked != null)
        {
            // 不算一次「密码错误」：密码是对的，只是账号没过审核。记失败会让待审核用户
            // 反复尝试后被 429「登录失败次数过多」盖住真正的「待审核」文案。
            loginAttemptGuard.reset(attemptKey);
            log.info("用户端登录被审核状态拒绝 userId={} status={} ip={}", user.getId(), user.getStatus(), ip);
            throw new AppException(403, blocked);
        }

        AppJwtService.Issued issued = appJwtService.issue(user.getId());
        AppUserToken token = new AppUserToken();
        token.setUserId(user.getId());
        token.setTokenId(issued.getJti());
        token.setUserAgent(trim(request.getHeader("User-Agent"), USER_AGENT_MAX));
        token.setIp(trim(ip, 64));
        token.setExpiresAt(issued.getExpiresAt());
        appUserTokenMapper.insert(token);
        appUserMapper.updateLastLogin(user.getId());
        loginAttemptGuard.reset(attemptKey);

        log.info("用户端登录成功 userId={} username={} ip={}", user.getId(), user.getUsername(), ip);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", issued.getToken());
        result.put("expiresIn", issued.getExpiresInSeconds());
        result.put("user", userView(user));
        return result;
    }

    // ------------------------------------------------------------------ 登出

    @Override
    public Map<String, Object> logout(Long userId, String jti)
    {
        int revoked = appUserTokenMapper.revoke(jti);
        log.info("用户端登出 userId={} revoked={}", userId, revoked);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }

    // ------------------------------------------------------------------ 当前用户

    @Override
    public Map<String, Object> me(Long userId)
    {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null)
        {
            throw AppException.unauthorized("账号不存在或已停用");
        }
        List<Map<String, Object>> identities = new ArrayList<>();
        for (AppUserIdentity identity : appUserIdentityMapper.selectByUserId(userId))
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", identity.getType());
            row.put("identifier", identity.getIdentifier());
            row.put("verified", identity.getVerified() != null && identity.getVerified() == 1);
            identities.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("avatar", user.getAvatar());
        result.put("identities", identities);
        result.put("createdAt", format(user.getCreatedAt()));
        result.put("lastLoginAt", format(user.getLastLoginAt()));
        return result;
    }

    // ------------------------------------------------------------------ 绑定 / 验证码

    @Override
    public Map<String, Object> bind(Long userId, String type, Map<String, Object> body)
    {
        if (!properties.getCode().isEnabled())
        {
            throw AppException.notImplemented("验证码通道未启用");
        }
        boolean email = AppUserIdentity.TYPE_EMAIL.equals(type);
        String channel = email ? AppVerifyCode.CHANNEL_EMAIL : AppVerifyCode.CHANNEL_SMS;
        String identifier = text(body.get("identifier"));
        String code = text(body.get("code"));
        if (identifier == null || !(email ? EMAIL_PATTERN : PHONE_PATTERN).matcher(identifier).matches())
        {
            throw AppException.badRequest(email ? "邮箱格式不正确" : "手机号格式不正确");
        }
        if (code == null)
        {
            throw AppException.badRequest("验证码不能为空");
        }
        AppVerifyCode verifyCode = appVerifyCodeMapper.selectLatest(channel, identifier,
                AppVerifyCode.PURPOSE_BIND);
        if (verifyCode == null || verifyCode.getExpiresAt() == null
                || verifyCode.getExpiresAt().isBefore(LocalDateTime.now())
                || (verifyCode.getAttempts() != null && verifyCode.getAttempts() >= VERIFY_CODE_MAX_ATTEMPTS)
                || !passwordEncoder.matches(code, verifyCode.getCodeHash()))
        {
            if (verifyCode != null)
            {
                appVerifyCodeMapper.increaseAttempts(verifyCode.getId());
            }
            throw AppException.badRequest("验证码无效或已过期");
        }
        appVerifyCodeMapper.markUsed(verifyCode.getId());

        AppUserIdentity identity = new AppUserIdentity();
        identity.setUserId(userId);
        identity.setType(type);
        identity.setIdentifier(identifier);
        identity.setVerified(1);
        try
        {
            appUserIdentityMapper.insert(identity);
        }
        catch (DuplicateKeyException e)
        {
            throw AppException.conflict(email ? "该邮箱已被其他账号绑定" : "该手机号已被其他账号绑定");
        }
        log.info("用户端绑定身份成功 userId={} type={}", userId, type);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("type", type);
        result.put("identifier", identifier);
        return result;
    }

    @Override
    public Map<String, Object> sendCode(Map<String, Object> body)
    {
        if (!properties.getCode().isEnabled())
        {
            throw AppException.notImplemented("验证码通道未启用");
        }
        // 开关打开但还没有短信/邮件服务商：M6 接。
        log.warn("验证码通道已开启但尚未接入服务商，/api/app/auth/code 仍返回 501");
        throw AppException.notImplemented("验证码通道已开启但尚未接入服务商（M6）");
    }

    // ------------------------------------------------------------------ 内部工具

    /** 密码策略：≥8 位且同时包含字母和数字 */
    private static void checkPasswordStrength(String password)
    {
        if (password == null || password.length() < PASSWORD_MIN)
        {
            throw AppException.badRequest("密码至少 " + PASSWORD_MIN + " 位");
        }
        if (password.length() > PASSWORD_MAX)
        {
            throw AppException.badRequest("密码最长 " + PASSWORD_MAX + " 位");
        }
        if (!LETTER_PATTERN.matcher(password).find() || !DIGIT_PATTERN.matcher(password).find())
        {
            throw AppException.badRequest("密码需同时包含字母和数字");
        }
    }

    /** 对外的用户视图：绝不包含 passwordHash / registerIp / status */
    static Map<String, Object> userView(AppUser user)
    {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("nickname", user.getNickname());
        view.put("avatar", user.getAvatar());
        return view;
    }

    private static String format(LocalDateTime value)
    {
        return value == null ? null : value.format(DATE_TIME);
    }

    private static String trim(String value, int max)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty())
        {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 去空白；空串视为未提供 */
    private static String text(Object value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = String.valueOf(value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
