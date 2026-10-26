package com.qtools.media.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaEditPolicyService;
import com.qtools.media.service.IMediaOrganizeService;
import com.qtools.media.service.IMediaPersonService;
import static com.qtools.media.support.MediaBodyParser.longsValue;
import static com.qtools.media.support.MediaBodyParser.metaPatch;
import static com.qtools.media.support.MediaBodyParser.parseObject;
import static com.qtools.media.support.MediaBodyParser.rolesValue;
import static com.qtools.media.support.MediaBodyParser.stringValue;
import static com.qtools.media.support.MediaBodyParser.textValue;

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
 * <h2>写接口清单</h2>
 * <ul>
 *   <li>{@code POST /media/tags} 现场新建标签（同名复用）</li>
 *   <li>{@code POST /media/persons} 现场新建演员（同名复用，P5.3-A）</li>
 *   <li>{@code PUT /media/video/{id}/meta} 单条元数据补丁</li>
 *   <li>{@code PUT /media/video/{id}/persons} 演员与角色全量替换（P5.3-A）</li>
 *   <li>{@code POST /media/videos/batch-meta} 批量补丁</li>
 *   <li>{@code PUT /media/video/{id}/synopsis} 作品简介 / 剧情梗概（P5.38）</li>
 * </ul>
 * <p>人物只读四件套（清单 / 详情 / 名下视频 / 名下相册）仍在 {@link MediaPersonController}，
 * 那里类级说明写着「只读」——所以人物<b>写</b>接口故意放在本类，与闸门同处一个文件。</p>
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

    /** 站端演员（人物）绑定与现场建人：业务实现全部在 {@link IMediaPersonService}，本类只加闸门（P5.3-A） */
    @Autowired
    private IMediaPersonService mediaPersonService;

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

    /**
     * 站端读作品简介：{@code {id, synopsis}}（{@code synopsis} 为 null = 暂无简介）
     *
     * <p>读接口<b>不看策略</b>（与 {@code GET /edit-policy}、{@code GET /tags} 同档）：
     * off 档站端也是「只读」而不是「什么都不给看」，信息栏要能显示已有简介。
     * 换行原样返回，前端用 {@code white-space: pre-wrap} 渲染。</p>
     */
    @GetMapping("/video/{id}/synopsis")
    public Map<String, Object> videoSynopsis(@PathVariable("id") String id)
    {
        return mediaOrganizeService.videoSynopsis(id);
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
     *
     * <p>{@code markers[]} 每项支持 {@code endPos}（P4-A）：缺省 / null = 单点标记，
     * 给值 = 区间标记（{@code pos < endPos <= dur}）。解析与校验全部复用管理端同一段实现，
     * 本接口只多一道策略闸门。</p>
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

    /**
     * 站端现场新建演员：body {@code {name, alias?}}，返回 {@code {id,name,created}}
     *
     * <p>同名<b>复用</b>（{@code created=false}），不像管理端那样 409 —— 站端是「边编辑边造人」，
     * 撞名再报错只会逼用户先去管理端查一遍。详见
     * {@link IMediaPersonService#ensurePerson(String, String)}。</p>
     *
     * <p>为什么放在这个控制器而不是 {@link MediaPersonController}：那边是类级说明里写死的
     * 「站端只读四件套」，写接口必须和 {@code POST /media/tags} 一样先过
     * {@link IMediaEditPolicyService#requireWritable()}，把「哪些站端接口会写库」收在同一个类里
     * 才不会漏掉闸门。</p>
     */
    @PostMapping("/persons")
    public Map<String, Object> createPerson(@RequestBody(required = false) String rawBody)
    {
        mediaEditPolicyService.requireWritable();
        Map<String, Object> body = parseObject(rawBody, true);
        Map<String, Object> out = mediaPersonService.ensurePerson(stringValue(body.get("name")),
                stringValue(body.get("alias")));
        log.debug("站端新建演员 name={} created={}", body.get("name"), out.get("created"));
        return out;
    }

    /**
     * 站端替换某视频的演员与角色：body {@code {personIds:[], roles:{personId:role}}}
     *
     * <p>业务实现<b>完全复用</b>管理端 {@code PUT /api/media/admin/videos/{id}/persons} 的
     * {@link IMediaPersonService#replaceVideoPersons(String, List, Map)}（同一段 SQL、同一套校验：
     * 视频不存在 404、personIds 去重后上限 50、未知 personId 400、roles 键必须是 personIds 子集
     * 且值 &lt;= 32 字符）；{@code personIds: []} = 解除全部绑定。历史 {@code person_id IS NULL}
     * 的扫描行不受影响。写完服务层已经 {@code invalidateSnapshot()}，索引立刻能看到新演员。</p>
     *
     * <p>回包形状是站端的 {@code {id, persons:[{id,name,role}]}}（管理端那份是
     * {@code {updated,id,persons:[{personId,...}]}}，前端已在用，不动它）；
     * 这里只做字段改名，不复制任何业务逻辑。</p>
     */
    @PutMapping("/video/{id}/persons")
    public Map<String, Object> replaceVideoPersons(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        mediaEditPolicyService.requireWritable();
        Map<String, Object> body = parseObject(rawBody, true);
        List<Long> personIds = longsValue(body.get("personIds"), "personIds");
        if (personIds == null)
        {
            throw MediaException.badRequest("personIds 必填（[] = 解除全部绑定）");
        }
        return personBindingNode(id, mediaPersonService.replaceVideoPersons(id, personIds,
                rolesValue(body.get("roles"), "roles")));
    }

    /**
     * 站端写作品简介：body {@code {synopsis}}（null 或空串 = 清空）→ {@code {id, synopsis}}
     *
     * <p>业务全部复用 {@link IMediaOrganizeService#updateVideoSynopsis(String, String)}：长度上限 8000
     * <b>字符</b>（超限 400）、纯空白归一化成 NULL、换行保留、写完 {@code invalidateSnapshot()}。
     * 闸门在第一行：非 full 档位下连「body 解析」都不会发生，公网匿名访客拿不到参数校验细节。</p>
     *
     * <p>为什么单独一个 {@code /synopsis} 接口而不是塞进 {@code PUT /video/{id}/meta}：
     * meta 是「评分 / 标签 / 标记」的补丁口径（每次都会回整份元数据），简介是 8KB 级正文，
     * 两件事的读频率、体积、失败重试语义都不一样；拆开后 meta 的回包体量与老前端行为一字不变。</p>
     */
    @PutMapping("/video/{id}/synopsis")
    public Map<String, Object> updateVideoSynopsis(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        mediaEditPolicyService.requireWritable();
        Map<String, Object> body = parseObject(rawBody, true);
        if (!body.containsKey("synopsis"))
        {
            throw MediaException.badRequest("synopsis 必填（null 或空串 = 清空）");
        }
        return mediaOrganizeService.updateVideoSynopsis(id, textValue(body.get("synopsis"), "synopsis"));
    }

    /**
     * 服务层 {@code replaceVideoPersons} 的回包 → 站端形状 {@code {id, persons:[{id,name,role}]}}
     *
     * <p>{@code role} 允许为 null（未指定角色）；{@code persons} 顺序沿用服务层
     * （有角色的在前、按角色 / 名字排序），前端直接渲染即可。</p>
     */
    private static Map<String, Object> personBindingNode(String videoId, Map<String, Object> replaced)
    {
        List<Map<String, Object>> persons = new ArrayList<>();
        Object raw = replaced == null ? null : replaced.get("persons");
        if (raw instanceof List<?> rows)
        {
            for (Object row : rows)
            {
                if (!(row instanceof Map<?, ?> map))
                {
                    continue;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", map.get("personId"));
                node.put("name", map.get("name"));
                node.put("role", map.get("role"));
                persons.add(node);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", videoId);
        out.put("persons", persons);
        return out;
    }
}
