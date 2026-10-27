package com.qtools.app.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.app.exception.AppException;
import com.qtools.app.security.AppUserGuard;
import com.qtools.app.service.IAppUserAdminService;
import com.qtools.common.core.page.TableDataInfo;
import com.qtools.common.utils.SecurityUtils;

/**
 * 管理端 · 用户端账号管理（前缀 {@code /api/app/admin/users}，P5.79 审核 → P5.81 用户管理）
 *
 * <p>为什么挂在 {@code /api/app/admin} 而不是另起前缀：接口语义上就是「用户端账号的管理」。
 * 但它的身份是<b>后台管理员</b>，不是用户端账号，所以：</p>
 * <ul>
 *   <li>不带 {@code @Anonymous}：走 Security 的 {@code anyRequest().authenticated()}，
 *       且 {@code SecurityConfig} 显式声明 {@code /api/app/admin/**} 为 authenticated
 *       （否则会被 {@code GET /**} 的 permitAll 吞掉）；</li>
 *   <li>每个方法上 {@code @PreAuthorize}：读 {@code app:user:list}、写 {@code app:user:edit}，
 *       有后台 token 但无权限 → 403；</li>
 *   <li>{@link com.qtools.app.security.AppJwtFilter} 对这段前缀直接放行（它只裁决用户端 token）。</li>
 * </ul>
 *
 * <p>错误体沿用 {@link com.qtools.app.exception.AppExceptionHandler} 的 {@code {"error":"..."}} +
 * 真实 HTTP 状态码；body 手工解析，非法 JSON 回 400 而不是 500。</p>
 *
 * @author qtools
 */
@RestController
@RequestMapping("/app/admin/users")
public class AppUserAdminController
{
    /** 只用于解析请求体 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private IAppUserAdminService appUserAdminService;

    /** 分页列表：status 空=全部 / pending / approved（正常，不含封禁）/ rejected / banned；keyword 匹配用户名/昵称 */
    @PreAuthorize("@ss.hasPermi('app:user:list')")
    @GetMapping
    public TableDataInfo list(@RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size)
    {
        // q 是给前端搜索框用的别名，二者都接受；keyword 优先
        return appUserAdminService.list(status, keyword != null ? keyword : q, page, size);
    }

    /** 统计（顶部卡片 / 待审核角标）：total / pending / approved / rejected / banned / newToday / activeRecently */
    @PreAuthorize("@ss.hasPermi('app:user:list')")
    @GetMapping("/stats")
    public Map<String, Object> stats()
    {
        return appUserAdminService.stats();
    }

    /** 单个账号详情（含审核记录 / 封禁字段 / 登录身份 / 收藏数） */
    @PreAuthorize("@ss.hasPermi('app:user:list')")
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable("id") Long id)
    {
        return appUserAdminService.detail(id);
    }

    /** 改资料：只改传入字段（nickname / avatar / reviewNote|note|remark） */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        return appUserAdminService.updateProfile(id, parseObject(rawBody), SecurityUtils.getUserId());
    }

    /** 重置密码：body 可空；带 {@code {"password":"..."}} 用手填，否则生成随机密码并一次性返回 */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody);
        return appUserAdminService.resetPassword(id, text(body.get("password")), SecurityUtils.getUserId());
    }

    /** 封禁（body 可选 {@code {"reason":"原因"}}）；独立于审核状态 */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @PostMapping("/{id}/ban")
    public Map<String, Object> ban(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody);
        return appUserAdminService.ban(id, text(body.get("reason")), SecurityUtils.getUserId());
    }

    /** 解封（幂等：未封禁时返回当前行 + changed:false） */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @PostMapping("/{id}/unban")
    public Map<String, Object> unban(@PathVariable("id") Long id)
    {
        return appUserAdminService.unban(id, SecurityUtils.getUserId());
    }

    /** 真删除：必须带 ?confirm=1，否则 400（防误删）；返回被删除的用户名 */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") Long id,
            @RequestParam(value = "confirm", required = false) String confirm)
    {
        return appUserAdminService.delete(id, confirm, SecurityUtils.getUserId());
    }

    /** 通过（幂等：已是 approved 时返回当前状态 + changed:false） */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable("id") Long id)
    {
        return appUserAdminService.review(id, AppUserGuard.STATUS_APPROVED, null, SecurityUtils.getUserId());
    }

    /** 拒绝（body 可选 {@code {"note":"原因"}}） */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody);
        return appUserAdminService.review(id, AppUserGuard.STATUS_REJECTED, text(body.get("note")),
                SecurityUtils.getUserId());
    }

    /** 通用改状态（撤销为待审核 / 其它场景） */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @PutMapping("/{id}/status")
    public Map<String, Object> updateStatus(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody);
        return appUserAdminService.review(id, text(body.get("status")), text(body.get("note")),
                SecurityUtils.getUserId());
    }

    /** 批量通过（多选行）：body {@code {"ids":[1,2,3]}} */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @PostMapping("/batch/approve")
    public Map<String, Object> batchApprove(@RequestBody(required = false) String rawBody)
    {
        return appUserAdminService.batchApprove(parseIds(rawBody), SecurityUtils.getUserId());
    }

    /** 批量封禁：body {@code {"ids":[...], "reason":"原因"}} */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @PostMapping("/batch/ban")
    public Map<String, Object> batchBan(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody);
        return appUserAdminService.batchBan(parseIds(body), text(body.get("reason")), SecurityUtils.getUserId());
    }

    /** 批量解封：body {@code {"ids":[...]}} */
    @PreAuthorize("@ss.hasPermi('app:user:edit')")
    @PostMapping("/batch/unban")
    public Map<String, Object> batchUnban(@RequestBody(required = false) String rawBody)
    {
        return appUserAdminService.batchUnban(parseIds(rawBody), SecurityUtils.getUserId());
    }

    /** 解析 body；缺 body 视为空对象（缺字段由业务层给 400） */
    private static Map<String, Object> parseObject(String rawBody)
    {
        if (rawBody == null || rawBody.trim().isEmpty())
        {
            return new LinkedHashMap<>();
        }
        Object parsed;
        try
        {
            parsed = MAPPER.readValue(rawBody, Object.class);
        }
        catch (Exception e)
        {
            throw AppException.badRequest("body 不是合法 JSON");
        }
        if (!(parsed instanceof Map))
        {
            throw AppException.badRequest("body 必须是 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    /** 从已解析的 body 里取 ids（字符串数字也接受） */
    private static List<Long> parseIds(Map<String, Object> body)
    {
        Object raw = body.get("ids");
        if (!(raw instanceof List))
        {
            throw AppException.badRequest("ids 必须是数组");
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : (List<?>) raw)
        {
            if (item instanceof Number)
            {
                ids.add(((Number) item).longValue());
            }
            else if (item != null)
            {
                try
                {
                    ids.add(Long.valueOf(String.valueOf(item).trim()));
                }
                catch (NumberFormatException e)
                {
                    throw AppException.badRequest("ids 里含非数字元素");
                }
            }
        }
        return ids;
    }

    /** 批量接口的原始 body 版本 */
    private static List<Long> parseIds(String rawBody)
    {
        return parseIds(parseObject(rawBody));
    }

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
