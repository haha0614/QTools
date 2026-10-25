package com.qtools.media.controller;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.common.annotation.Anonymous;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaEditPolicyService;
import com.qtools.media.service.IMediaOwnerService;
import com.qtools.media.service.IMediaSavedFilterService;

/**
 * 媒体站 保存的筛选条件接口（{@code /api/media/filters}）
 *
 * <p>与收藏（{@code /api/media/fav}）同一套身形：类上 {@link Anonymous} 免登录，
 * 数据按 {@code owner_key} 隔离（读 = 自己的 + 全局的），写操作在进业务前调
 * {@link IMediaEditPolicyService#requireWritable()}——编辑策略为 off / local 时返回 403，
 * 站端连「保存筛选」都不可写，与其它站端写接口口径一致。</p>
 *
 * <p>body 手工解析（{@code @RequestBody String} + Jackson 树）：{@code params} 既接受 JSON 对象
 * {@code {"kind":"video","tag":7}}，也接受已序列化的 JSON 字符串（前端 double-encode 的情况）；
 * 非法 JSON / 非对象 body 一律 400，不落到 500。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/media")
public class MediaFilterController
{
    /** 只用于解析请求体，不参与响应序列化 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private IMediaOwnerService mediaOwnerService;

    @Autowired
    private IMediaSavedFilterService mediaSavedFilterService;

    @Autowired
    private IMediaEditPolicyService mediaEditPolicyService;

    /** 当前 owner 可见的筛选条件：自己的 + 全局的 */
    @GetMapping("/filters")
    public List<Map<String, Object>> list(HttpServletRequest request, HttpServletResponse response)
    {
        return mediaSavedFilterService.list(mediaOwnerService.resolve(request, response).getOwnerKey());
    }

    /** 保存：body {@code {name, params}}；同名同 owner 覆盖（不是 409），非法 name / params 400 */
    @PostMapping("/filters")
    public Map<String, Object> save(HttpServletRequest request, HttpServletResponse response,
            @RequestBody(required = false) String rawBody)
    {
        mediaEditPolicyService.requireWritable();
        JsonNode root = readObject(rawBody);
        JsonNode nameNode = root.get("name");
        String name = nameNode == null || nameNode.isNull() ? null : nameNode.asText();
        return mediaSavedFilterService.save(mediaOwnerService.resolve(request, response).getOwnerKey(), name,
                paramsOf(root));
    }

    /** 删除自己的条目（不存在 / 不属于自己 / 是全局条目一律 404） */
    @DeleteMapping("/filters/{id}")
    public Map<String, Object> delete(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("id") String id)
    {
        mediaEditPolicyService.requireWritable();
        return mediaSavedFilterService.delete(mediaOwnerService.resolve(request, response).getOwnerKey(), longId(id));
    }

    private static JsonNode readObject(String rawBody)
    {
        if (rawBody == null || rawBody.trim().isEmpty())
        {
            throw MediaException.badRequest("missing request body");
        }
        try
        {
            JsonNode root = MAPPER.readTree(rawBody);
            if (root == null || !root.isObject())
            {
                throw MediaException.badRequest("body 必须是 JSON 对象");
            }
            return root;
        }
        catch (MediaException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw MediaException.badRequest("body 不是合法 JSON");
        }
    }

    /** params 的两种写法都收：JSON 值（对象/数组）或 JSON 字符串；缺省返回 null 交给服务层报 400 */
    private static String paramsOf(JsonNode root)
    {
        JsonNode node = root.get("params");
        if (node == null || node.isNull())
        {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static Long longId(String id)
    {
        try
        {
            return Long.valueOf(id);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest("id 必须是数字");
        }
    }
}
