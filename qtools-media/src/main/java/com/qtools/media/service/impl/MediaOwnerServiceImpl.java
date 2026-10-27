package com.qtools.media.service.impl;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.common.security.IAppTokenVerifier;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.domain.MediaOwner;
import com.qtools.media.domain.MediaOwnerSession;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaOwnerMapper;
import com.qtools.media.service.IMediaOwnerService;
import com.qtools.media.support.BearerTokens;
import com.qtools.media.support.OwnerKeys;

/**
 * 匿名所有者（owner_key）服务实现
 *
 * <p>传递方式两种：请求头 {@code X-Ms-Owner} 优先，其次 cookie {@code ms_owner}；
 * 都没有时生成 32 字节随机 hex 并随响应下发 cookie（不设 HttpOnly，前端 localStorage 也要用）。
 * 前端在 5173 端口访问 8080 时跨端口 cookie 不生效，故必须支持请求头通道。</p>
 *
 * <p>profile 全部落库：nick -> media_owner.display_name，avatar -> media_owner.avatar(MEDIUMTEXT)。</p>
 *
 * <p><b>P5.74 · 账号态优先</b>：本类是全站唯一的「所有者键解析器」。除了上面两条设备通道，
 * 它还会看 {@code Authorization: Bearer <app token>}——token 有效时数据归属键变成
 * {@code u:<appUserId>}（账号态，跨设备跟随账号），无效 / 缺失时退回设备键（匿名态，行为与 P5.74 之前一致）。
 * 因此<b>所有用到 owner 的代码都不该再自己读请求头</b>：播放列表、收藏、收藏夹、最近浏览、
 * 保存的筛选统一从 {@link MediaOwnerSession#getOwnerKey()} 取值，资料（昵称/头像）从
 * {@link MediaOwnerSession#getDeviceKey()} 取值。</p>
 *
 * <p>账号态的设备键仍然会被登记进 {@code media_owner}（昵称 / 头像跟着设备走），但<b>不会</b>
 * 写入 {@code u:<id>} 这种合成行——它只出现在业务数据表的 {@code owner_key} 列。</p>
 *
 * @author qtools
 */
@Service
public class MediaOwnerServiceImpl implements IMediaOwnerService
{
    private static final Logger log = LoggerFactory.getLogger(MediaOwnerServiceImpl.class);

    /** cookie 名与前端/Node 版保持一致 */
    public static final String COOKIE_NAME = "ms_owner";

    /** 请求头名（优先于 cookie） */
    public static final String HEADER_NAME = "X-Ms-Owner";

    /** 一年（秒） */
    private static final int COOKIE_MAX_AGE = 365 * 24 * 3600;

    /** 默认昵称，与 Node 版 defaultUser().profile.nick 一致 */
    private static final String DEFAULT_NICK = "我";

    /** 昵称最大字符数（对齐 Node NICK_MAX=24） */
    private static final int NICK_MAX = 24;

    /** 头像解码后上限 512KB（media_owner.avatar 是 MEDIUMTEXT，够放） */
    private static final int AVATAR_MAX_BYTES = 512 * 1024;

    /** base64 串长度上限（512KB / 3 * 4 + 余量） */
    private static final int AVATAR_MAX_B64 = (AVATAR_MAX_BYTES / 3 + 1) * 4 + 8;

    /** owner_key 允许的字符集：迁移用的 'legacy'、测试用的 'ownerA' 都落在这里面 */
    private static final Pattern OWNER_KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private static final Pattern AVATAR_PREFIX = Pattern.compile("^data:image/(?:png|jpeg|webp);base64,",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BASE64_BODY = Pattern.compile("^[A-Za-z0-9+/\\r\\n]+={0,2}$");

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final SecureRandom random = new SecureRandom();

    @Autowired
    private MediaOwnerMapper mediaOwnerMapper;

    /**
     * 用户端 token 校验（qtools-app 提供实现）；媒体模块单独部署 / 单测时没有该 bean，
     * {@code getIfAvailable()} 返回 null，一切照旧走匿名设备态，不会启动失败。
     */
    @Autowired
    private ObjectProvider<IAppTokenVerifier> appTokenVerifier;

    @Override
    public MediaOwnerSession resolve(HttpServletRequest request, HttpServletResponse response)
    {
        String header = trimToNull(request.getHeader(HEADER_NAME));
        String cookie = cookieValue(request, COOKIE_NAME);

        String deviceKey;
        boolean fresh = false;
        if (header != null)
        {
            // 账号态只能由 Authorization 决定：客户端自称 "u:<id>" 会让任何人都能读写别人的账号数据，一律 400
            if (header.startsWith(OwnerKeys.ACCOUNT_PREFIX))
            {
                throw MediaException.badRequest(HEADER_NAME + " 不接受 " + OwnerKeys.ACCOUNT_PREFIX
                        + " 前缀：账号态由 Authorization: Bearer <app token> 决定，客户端不能自称账号");
            }
            // 显式传了头却非法：宁可 400 也不静默改用别的 key（否则会悄悄把数据写到另一个身份下）
            if (!isValidKey(header))
            {
                throw MediaException.badRequest(HEADER_NAME + " 非法：只允许字母数字和 . _ - ，长度 1-64");
            }
            deviceKey = header;
        }
        else if (cookie != null && isValidKey(cookie))
        {
            deviceKey = cookie;
        }
        else
        {
            deviceKey = newKey();
            fresh = true;
        }

        // 登录态：有效 app token -> 账号态（数据跟随账号）；否则匿名设备态（不破坏匿名使用）
        Long userId = accountUserId(request);

        // 设备行照旧登记：昵称 / 头像仍按设备保存，账号态也不例外
        mediaOwnerMapper.insertIgnore(deviceKey);
        mediaOwnerMapper.updateLastSeen(deviceKey);

        MediaOwnerSession session = new MediaOwnerSession(deviceKey, userId, fresh);
        MediaOwner owner = mediaOwnerMapper.selectOwnerByKey(deviceKey);
        if (owner != null)
        {
            session.setNick(StringUtils.isEmpty(owner.getDisplayName()) ? DEFAULT_NICK : owner.getDisplayName());
            session.setAvatar(normalizeStoredAvatar(owner.getAvatar()));
        }
        else
        {
            session.setNick(DEFAULT_NICK);
        }

        if (!deviceKey.equals(cookie))
        {
            writeCookie(response, deviceKey);
        }
        if (fresh)
        {
            log.debug("下发新的 device_key={}", deviceKey);
        }
        if (session.isAccount())
        {
            log.debug("账号态 owner_key={} device_key={}", session.getOwnerKey(), deviceKey);
        }
        return session;
    }

    /**
     * 解析登录账号：{@code Authorization: Bearer <app token>} → app_user.id
     *
     * <p>任何失败都只是「没登录」：头缺失 / 不是 Bearer / token 失效 / 校验组件不存在 / 校验抛异常，
     * 一律返回 null 让调用方走匿名设备态——媒体站匿名必须永远可用，不能因为登录态判断失败就 401。</p>
     */
    private Long accountUserId(HttpServletRequest request)
    {
        IAppTokenVerifier verifier = appTokenVerifier == null ? null : appTokenVerifier.getIfAvailable();
        if (verifier == null)
        {
            return null;
        }
        String token = BearerTokens.of(request);
        if (token == null)
        {
            return null;
        }
        try
        {
            return verifier.verifyUserId(token);
        }
        catch (RuntimeException e)
        {
            log.debug("app token 校验异常，按未登录处理: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void applyProfile(MediaOwnerSession session, Map<String, Object> profile)
    {
        if (profile == null)
        {
            throw MediaException.badRequest("profile 必须是对象");
        }
        if (profile.containsKey("nick"))
        {
            Object raw = profile.get("nick");
            String nick = raw instanceof String ? ((String) raw).trim() : "";
            int len = nick.codePointCount(0, nick.length());
            if (len < 1 || len > NICK_MAX)
            {
                throw MediaException.badRequest("nick 需 1-" + NICK_MAX + " 字符");
            }
            // 昵称是设备级资料（media_owner 按设备键存），不跟着账号态走
            mediaOwnerMapper.updateDisplayName(session.getDeviceKey(), nick);
            session.setNick(nick);
        }
        if (profile.containsKey("avatar"))
        {
            Object raw = profile.get("avatar");
            String avatar = null;
            if (raw != null)
            {
                if (!(raw instanceof String))
                {
                    throw MediaException.badRequest("avatar 必须是字符串或 null");
                }
                // 前端可能用空串表示"不要头像"（Node 版 `p.avatar || null` 同样收敛成 null）
                String text = ((String) raw).trim();
                avatar = text.isEmpty() ? null : checkAvatar(text);
            }
            mediaOwnerMapper.updateAvatar(session.getDeviceKey(), avatar);
            session.setAvatar(avatar);
        }
    }

    /** 校验头像 data URI，通过则原样返回 */
    private static String checkAvatar(String value)
    {
        if (!AVATAR_PREFIX.matcher(value).find())
        {
            throw MediaException.badRequest("avatar 必须是 data:image/(png|jpeg|webp);base64,... 形式");
        }
        String body = value.substring(value.indexOf(',') + 1);
        if (body.isEmpty() || !BASE64_BODY.matcher(body).matches())
        {
            throw MediaException.badRequest("avatar 的 base64 非法");
        }
        if (body.length() > AVATAR_MAX_B64)
        {
            throw MediaException.badRequest("avatar 超过 512KB");
        }
        int bytes;
        try
        {
            bytes = Base64.getMimeDecoder().decode(body).length;
        }
        catch (IllegalArgumentException e)
        {
            throw MediaException.badRequest("avatar 的 base64 非法");
        }
        if (bytes > AVATAR_MAX_BYTES)
        {
            throw MediaException.badRequest("avatar 超过 512KB");
        }
        return value;
    }

    /** 库里读出的头像；空串或非法形状（历史脏数据）一律当没有，避免把坏值回给前端 */
    private static String normalizeStoredAvatar(String value)
    {
        if (StringUtils.isEmpty(value) || !AVATAR_PREFIX.matcher(value).find())
        {
            return null;
        }
        return value;
    }

    // ------------------------------------------------------------------ 工具

    /** 32 字节随机 -> 64 位 hex，正好等于 media_owner.owner_key 的 VARCHAR(64) */
    private String newKey()
    {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        char[] out = new char[64];
        for (int i = 0; i < buf.length; i++)
        {
            out[i * 2] = HEX[(buf[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[buf[i] & 0xF];
        }
        return new String(out);
    }

    private static boolean isValidKey(String key)
    {
        if (!OWNER_KEY.matcher(key).matches())
        {
            return false;
        }
        // '.' / '..' 不当作合法 key，避免以后按 key 拼路径时踩到上跳
        return !".".equals(key) && !"..".equals(key);
    }

    private static void writeCookie(HttpServletResponse response, String key)
    {
        // 不用 ResponseCookie：这里要精确控制属性顺序且不要 HttpOnly
        response.addHeader("Set-Cookie",
                COOKIE_NAME + "=" + key + "; Path=/; Max-Age=" + COOKIE_MAX_AGE + "; SameSite=Lax");
    }

    private static String cookieValue(HttpServletRequest request, String name)
    {
        Cookie[] cookies = request.getCookies();
        if (cookies == null)
        {
            return null;
        }
        for (Cookie cookie : cookies)
        {
            if (name.equals(cookie.getName()))
            {
                return trimToNull(cookie.getValue());
            }
        }
        return null;
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
