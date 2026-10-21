package com.qtools.media.controller;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.common.annotation.Anonymous;
import com.qtools.media.domain.MediaOwnerSession;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaOwnerService;
import com.qtools.media.service.IMediaUserService;

/**
 * 媒体站 用户数据接口（收藏 / 最近浏览 / 资料）
 *
 * <p>全部免登录（类上 {@link Anonymous}），但数据严格按 owner_key 隔离：
 * owner_key 来自请求头 {@code X-Ms-Owner} 或 cookie {@code ms_owner}，都没有时由服务端
 * 生成并随响应下发（Set-Cookie + 响应体 ownerKey 字段）。</p>
 *
 * <p>POST 接口的 body 手工解析（而不是直接 @RequestBody Map），是为了在
 * 非法 JSON / 非对象 body 时回 {@code {"error":"..."}} + 400，而不是落到 500。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/api/media")
public class MediaUserController
{
    private static final Logger log = LoggerFactory.getLogger(MediaUserController.class);

    /** 只用于解析请求体，不参与响应序列化 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private IMediaOwnerService mediaOwnerService;

    @Autowired
    private IMediaUserService mediaUserService;

    /** 完整用户对象 */
    @GetMapping("/user")
    public Map<String, Object> user(HttpServletRequest request, HttpServletResponse response)
    {
        return mediaUserService.selectUser(mediaOwnerService.resolve(request, response));
    }

    /** 合并式更新：profile / favorites / recentVideos / recentPhotos（兼容 Node 的 fav / recent 单条形态） */
    @PostMapping("/user")
    public Map<String, Object> updateUser(HttpServletRequest request, HttpServletResponse response,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        MediaOwnerSession session = mediaOwnerService.resolve(request, response);
        log.debug("合并更新用户数据 owner={} keys={}", session.getOwnerKey(), body.keySet());
        return mediaUserService.mergeUser(session, body);
    }

    /** 单一收藏（幂等）；参数取自 body，缺省时回落到 query 参数 */
    @PostMapping("/fav")
    public Map<String, Object> addFavorite(HttpServletRequest request, HttpServletResponse response,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, false);
        MediaOwnerSession session = mediaOwnerService.resolve(request, response);
        return mediaUserService.addFavorite(session,
                param(request, body, "kind"),
                param(request, body, "id"),
                param(request, body, "album"),
                seqParam(request, body));
    }

    /** 取消收藏（幂等）：kind=video&id=<videoId> 或 kind=photo&album=<albumId>&seq=<n> */
    @DeleteMapping("/fav")
    public Map<String, Object> removeFavorite(HttpServletRequest request, HttpServletResponse response)
    {
        MediaOwnerSession session = mediaOwnerService.resolve(request, response);
        return mediaUserService.removeFavorite(session,
                trimToNull(request.getParameter("kind")),
                trimToNull(request.getParameter("id")),
                trimToNull(request.getParameter("album")),
                seqParam(request, Map.of()));
    }

    /** 收藏列表 */
    @GetMapping("/fav/list")
    public List<Map<String, Object>> favoriteList(HttpServletRequest request, HttpServletResponse response)
    {
        return mediaUserService.selectFavoriteList(mediaOwnerService.resolve(request, response));
    }

    /** 记录浏览：video -> {kind,id,pos,dur}；photo -> {kind,album,seq} */
    @PostMapping("/recent")
    public Map<String, Object> addRecent(HttpServletRequest request, HttpServletResponse response,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, false);
        MediaOwnerSession session = mediaOwnerService.resolve(request, response);
        return mediaUserService.addRecent(session,
                param(request, body, "kind"),
                param(request, body, "id"),
                param(request, body, "album"),
                seqParam(request, body),
                doubleParam(request, body, "pos"),
                doubleParam(request, body, "dur"));
    }

    /** 最近浏览（视频/图片各最多 50）；kind=video|photo 可只看一类 */
    @GetMapping("/recent/list")
    public Map<String, Object> recentList(HttpServletRequest request, HttpServletResponse response)
    {
        String kind = trimToNull(request.getParameter("kind"));
        return mediaUserService.selectRecentList(mediaOwnerService.resolve(request, response), kind);
    }

    /** 身份 + 各类计数 */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request, HttpServletResponse response)
    {
        return mediaUserService.selectMe(mediaOwnerService.resolve(request, response));
    }

    // ------------------------------------------------------------------ 入参

    /** 解析 body；allowEmpty 为 true 时缺 body 直接 400（POST /user 必须有内容） */
    private static Map<String, Object> parseObject(String rawBody, boolean allowEmpty)
    {
        if (rawBody == null || rawBody.trim().isEmpty())
        {
            if (allowEmpty)
            {
                throw MediaException.badRequest("body 必须是 JSON 对象");
            }
            return Map.of();
        }
        Object parsed;
        try
        {
            parsed = MAPPER.readValue(rawBody, Object.class);
        }
        catch (Exception e)
        {
            throw MediaException.badRequest("body 不是合法 JSON");
        }
        if (!(parsed instanceof Map))
        {
            throw MediaException.badRequest("body 必须是 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    /** body 优先，其次 query 参数 */
    private static String param(HttpServletRequest request, Map<String, Object> body, String name)
    {
        Object value = body == null ? null : body.get(name);
        String text = value == null ? null : String.valueOf(value).trim();
        if (text != null && !text.isEmpty())
        {
            return text;
        }
        return trimToNull(request.getParameter(name));
    }

    /** seq / i 两种写法都收（Node 用 i，本模块契约用 seq） */
    private static Integer seqParam(HttpServletRequest request, Map<String, Object> body)
    {
        Object value = body == null ? null : (body.containsKey("seq") ? body.get("seq") : body.get("i"));
        if (value == null)
        {
            value = request.getParameter("seq");
        }
        if (value == null)
        {
            value = request.getParameter("i");
        }
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number)
        {
            double d = ((Number) value).doubleValue();
            return d == Math.floor(d) ? Integer.valueOf((int) d) : null;
        }
        String text = String.valueOf(value).trim();
        try
        {
            return Integer.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static Double doubleParam(HttpServletRequest request, Map<String, Object> body, String name)
    {
        Object value = body == null ? null : body.get(name);
        if (value == null)
        {
            String text = trimToNull(request.getParameter(name));
            if (text == null)
            {
                return null;
            }
            value = text;
        }
        if (value instanceof Number)
        {
            return ((Number) value).doubleValue();
        }
        try
        {
            return Double.valueOf(String.valueOf(value).trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
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
