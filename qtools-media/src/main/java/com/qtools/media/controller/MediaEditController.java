package com.qtools.media.controller;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.media.service.IMediaEditPolicyService;
import com.qtools.media.service.IMediaOrganizeService;
import static com.qtools.media.support.MediaBodyParser.metaPatch;
import static com.qtools.media.support.MediaBodyParser.parseObject;
import static com.qtools.media.support.MediaBodyParser.stringValue;

/**
 * 媒体站「站端编辑」接口（前缀 /api/media，全部免登录）
 *
 * <p>站点不是若依后台：访客没有管理员 token，而组织属性（标签 / 评分 / 标记）原本只挂在
 * {@code /api/media/admin/**} 后面，于是站端一律 401。这里为站端单独开一组匿名接口，
 * 业务实现<b>完全复用</b> {@link IMediaOrganizeService}，本类只做三件事：免登录、
 * 编辑策略闸门、body 解析（解析规则与管理端共用 {@code MediaBodyParser}）。</p>
 *
 * <h2>编辑策略（三档）</h2>
 * <table border="1">
 *   <caption>level → 本控制器写接口行为</caption>
 *   <tr><th>level</th><th>语义</th><th>写接口</th></tr>
 *   <tr><td>{@code off}</td><td>站端禁止编辑</td><td>403</td></tr>
 *   <tr><td>{@code local}</td><td>站端只在浏览器本地改</td><td>403（前端可存 localStorage）</td></tr>
 *   <tr><td>{@code full}</td><td>完全编辑（默认）</td><td>正常落库</td></tr>
 * </table>
 *
 * <p><b>闸门位置</b>：写接口第一行就是 {@link IMediaEditPolicyService#requireWritable()}，
 * 先于 body 解析与任何业务调用——非 full 档位下，非法 body 也只会拿到 403，
 * 不会把参数校验细节透给公网匿名访客。</p>
 *
 * <p>读接口（{@code GET /edit-policy}、{@code GET /tags}）不受策略影响：
 * 站端选择器在任何档位下都要能列出标签字典。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/media")
public class MediaEditController
{
    private static final Logger log = LoggerFactory.getLogger(MediaEditController.class);

    @Autowired
    private IMediaEditPolicyService mediaEditPolicyService;

    @Autowired
    private IMediaOrganizeService mediaOrganizeService;

    // ------------------------------------------------------------------ 读

    /** 当前站端编辑策略：{@code {level}}，前端据此决定「可编辑 / 只改本地 / 只读」 */
    @GetMapping("/edit-policy")
    public Map<String, Object> editPolicy()
    {
        return Map.of("level", mediaEditPolicyService.getLevel());
    }

    /** 标签字典（只读，供站端标签选择器）：{@code [{id,name,color,useCount}]} */
    @GetMapping("/tags")
    public List<Map<String, Object>> tags()
    {
        return mediaOrganizeService.tagList();
    }

    // ------------------------------------------------------------------ 写（策略 = full 才放行）

    /**
     * 站端现场新建标签：body {@code {name, color?}}
     *
     * <p>重名复用（已存在直接返回既有行，{@code created=false}），与
     * {@code POST /api/media/admin/tags} 语义完全一致。</p>
     */
    @PostMapping("/tags")
    public Map<String, Object> createTag(@RequestBody(required = false) String rawBody)
    {
        mediaEditPolicyService.requireWritable();
        Map<String, Object> body = parseObject(rawBody, true);
        Map<String, Object> out = mediaOrganizeService.createTag(stringValue(body.get("name")),
                stringValue(body.get("color")));
        log.debug("站端新建标签 name={} created={}", body.get("name"), out.get("created"));
        return out;
    }

    /**
     * 站端单条元数据补丁：body 同 {@code PUT /api/media/admin/video/{id}/meta}
     * （{@code rating / tagIds / addTagIds / removeTagIds / markers}），返回补丁后的完整元数据
     */
    @PutMapping("/video/{id}/meta")
    public Map<String, Object> updateVideoMeta(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        mediaEditPolicyService.requireWritable();
        return mediaOrganizeService.updateVideoMeta(id, metaPatch(parseObject(rawBody, true), false));
    }

    /**
     * 站端批量补丁：body 同 {@code POST /api/media/admin/videos/batch-meta}
     * （{@code {ids:[...], addTagIds?, removeTagIds?, rating?, markerDelta?}}），ids 上限 500 仍由服务层兜底
     */
    @PostMapping("/videos/batch-meta")
    public Map<String, Object> batchMeta(@RequestBody(required = false) String rawBody)
    {
        mediaEditPolicyService.requireWritable();
        return mediaOrganizeService.batchMeta(metaPatch(parseObject(rawBody, true), true));
    }
}
