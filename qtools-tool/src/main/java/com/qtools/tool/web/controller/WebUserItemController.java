package com.qtools.tool.web.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.tool.mapper.ToolWebPostMapper;

/**
 * 内容聚合的收藏 / 观看状态（P6.112）
 *
 * <p>归属键沿用媒体站的约定（{@code OwnerKeys}）：登录态 {@code u:<appUserId>}，匿名态是设备键
 * （前端 {@code localStorage['ms.owner']}），通过请求头 {@code X-Ms-Owner} 传。客户端不允许自称账号态。</p>
 *
 * <p>为什么不存"看过时间+进度"：这里的内容是整篇图文/视频，用户关心的是"看过没有"，
 * 进度记在浏览器就够；后台只保存有跨设备价值的两件事——收藏、看过。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/tool/web")
public class WebUserItemController
{
    private static final Logger log = LoggerFactory.getLogger(WebUserItemController.class);

    /** 单次回传上限：收藏/看过都是"回 id 让前端打标记"，几万条没有意义 */
    private static final int IDS_MAX = 5000;

    private static final String KIND_FAV = "fav";

    private static final String KIND_SEEN = "seen";

    @Autowired
    private ToolWebPostMapper toolWebPostMapper;

    /** 用户端 token 校验（qtools-common 的接口，实现由 qtools-app 提供）；容器里没有时按匿名处理 */
    @Autowired(required = false)
    private com.qtools.common.security.IAppTokenVerifier appTokenVerifier;

    /** 收藏 / 看过的 id 列表：{@code GET /api/tool/web/user-flags} */
    @GetMapping("/user-flags")
    public Map<String, Object> flags(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Ms-Owner", required = false) String ownerHeader,
            @RequestParam(value = "owner", required = false) String ownerParam)
    {
        String owner = resolveOwner(authorization, ownerHeader, ownerParam);
        Map<String, Object> out = new LinkedHashMap<>();
        if (owner == null)
        {
            out.put("fav", List.of());
            out.put("seen", List.of());
            out.put("owner", "");
            return out;
        }
        out.put("fav", toolWebPostMapper.selectUserItemIds(owner, KIND_FAV, IDS_MAX));
        out.put("seen", toolWebPostMapper.selectUserItemIds(owner, KIND_SEEN, IDS_MAX));
        out.put("owner", owner);
        return out;
    }

    /**
     * 我的收藏 / 浏览记录：{@code GET /api/tool/web/user-items?kind=fav&page=1&size=24}
     *
     * <p>kind=fav 收藏，kind=seen 浏览记录。返回结构和 /posts 一致，前端可以直接复用卡片渲染。</p>
     */
    @GetMapping("/user-items")
    public Map<String, Object> userItems(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Ms-Owner", required = false) String ownerHeader,
            @RequestParam(value = "owner", required = false) String ownerParam,
            @RequestParam(value = "kind", required = false, defaultValue = "fav") String kind,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "24") int size)
    {
        String owner = resolveOwner(authorization, ownerHeader, ownerParam);
        String cleanKind = KIND_SEEN.equals(kind) ? KIND_SEEN : KIND_FAV;
        int safePage = page < 1 ? 1 : page;
        int safeSize = size < 1 ? 24 : Math.min(size, 100);
        Map<String, Object> out = new LinkedHashMap<>();
        if (owner == null)
        {
            out.put("rows", List.of());
            out.put("total", 0);
            return out;
        }
        out.put("rows", toolWebPostMapper.selectUserItems(owner, cleanKind, (safePage - 1) * safeSize, safeSize));
        out.put("total", toolWebPostMapper.countUserItems(owner, cleanKind));
        out.put("kind", cleanKind);
        return out;
    }

    /** 切换收藏：{@code POST /api/tool/web/fav?postId=1&on=1} */
    @PostMapping("/fav")
    public Map<String, Object> fav(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Ms-Owner", required = false) String ownerHeader,
            @RequestParam(value = "owner", required = false) String ownerParam,
            @RequestParam("postId") Long postId,
            @RequestParam(value = "on", required = false, defaultValue = "1") int on)
    {
        String owner = resolveOwner(authorization, ownerHeader, ownerParam);
        Map<String, Object> out = new LinkedHashMap<>();
        if (owner == null || postId == null)
        {
            out.put("ok", false);
            out.put("error", "缺少归属键");
            return out;
        }
        if (on > 0)
        {
            toolWebPostMapper.addUserItem(owner, postId, KIND_FAV);
        }
        else
        {
            toolWebPostMapper.removeUserItem(owner, postId, KIND_FAV);
        }
        out.put("ok", true);
        out.put("fav", on > 0);
        return out;
    }

    /** 标记看过：{@code POST /api/tool/web/seen?postId=1} */
    @PostMapping("/seen")
    public Map<String, Object> seen(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Ms-Owner", required = false) String ownerHeader,
            @RequestParam(value = "owner", required = false) String ownerParam,
            @RequestParam("postId") Long postId)
    {
        String owner = resolveOwner(authorization, ownerHeader, ownerParam);
        if (owner != null && postId != null)
        {
            toolWebPostMapper.addUserItem(owner, postId, KIND_SEEN);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", owner != null);
        return out;
    }

    /**
     * 归属键：优先请求头（媒体站沿用），其次查询参数（无自定义头的场景），
     * 并拒绝客户端自称账号态（{@code u:} 前缀只能由服务端校验 token 后写入）。
     */
    private String resolveOwner(String header, String param)
    {
        return resolveOwner(null, header, param);
    }

    /**
     * 归属键优先级：{@code Authorization: Bearer <app token>} 有效 → {@code u:<userId>}（账号态，跨设备）；
     * 否则退回设备键。这样工具里的收藏/浏览记录与个人中心、媒体站归属同一个人。
     */
    private String resolveOwner(String authorization, String header, String param)
    {
        if (authorization != null && authorization.startsWith("Bearer ") && appTokenVerifier != null)
        {
            try
            {
                Long userId = appTokenVerifier.verifyUserId(authorization.substring(7).trim());
                if (userId != null)
                {
                    return "u:" + userId;
                }
            }
            catch (RuntimeException e)
            {
                log.debug("token 校验异常，按匿名处理: {}", e.toString());
            }
        }
        String raw = header != null && !header.trim().isEmpty() ? header.trim() : (param == null ? "" : param.trim());
        if (raw.isEmpty() || raw.startsWith("u:"))
        {
            return null;
        }
        // 设备键字符集约定 [A-Za-z0-9._-]，超范围直接丢弃，避免脏键写库
        if (!raw.matches("[A-Za-z0-9._-]{4,64}"))
        {
            log.debug("丢弃非法 owner_key: {}", raw);
            return null;
        }
        return raw;
    }
}
