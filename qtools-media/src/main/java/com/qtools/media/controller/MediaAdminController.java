package com.qtools.media.controller;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Log;
import com.qtools.common.core.controller.BaseController;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.common.core.page.TableDataInfo;
import com.qtools.common.enums.BusinessType;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.domain.MediaVideoMarker;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaAdminService;
import com.qtools.media.service.IMediaEditPolicyService;
import com.qtools.media.service.IMediaOrganizeService;
import com.qtools.media.service.IMediaScanService;
import static com.qtools.media.support.MediaBodyParser.booleanValue;
import static com.qtools.media.support.MediaBodyParser.boolValue;
import static com.qtools.media.support.MediaBodyParser.decimalValue;
import static com.qtools.media.support.MediaBodyParser.intValue;
import static com.qtools.media.support.MediaBodyParser.integerValue;
import static com.qtools.media.support.MediaBodyParser.longValue;
import static com.qtools.media.support.MediaBodyParser.metaPatch;
import static com.qtools.media.support.MediaBodyParser.parseObject;
import static com.qtools.media.support.MediaBodyParser.stringValue;

/**
 * 管理端媒体库接口（前缀 /api/media/admin）
 *
 * <p>与用户端不同：本控制器<b>不加</b> {@code @Anonymous}，因此走 Security 的
 * {@code anyRequest().authenticated()}（无 token → 若依原生 401），并在每个方法上叠加
 * {@code @PreAuthorize("@ss.hasPermi('media:admin:*')")}（有 token 无权限 → 403）。</p>
 *
 * <p>错误体沿用 {@link com.qtools.media.exception.MediaExceptionHandler} 的 {@code {"error":"..."}}；
 * POST/PATCH 的 body 手工解析，保证非法 JSON 回 400 而不是 500。</p>
 *
 * <p>文件删除一律软删：只 MOVED 到 {@code _媒体站/trash}，没有任何真删路径。</p>
 *
 * @author qtools
 */
@RestController
@RequestMapping("/media/admin")
public class MediaAdminController extends BaseController
{
    private static final Logger log = LoggerFactory.getLogger(MediaAdminController.class);

    @Autowired
    private IMediaAdminService mediaAdminService;

    @Autowired
    private IMediaScanService mediaScanService;

    @Autowired
    private IMediaOrganizeService mediaOrganizeService;

    /** 站端编辑策略（off / local / full）：管理端「编辑策略」面板读它、改它 */
    @Autowired
    private IMediaEditPolicyService mediaEditPolicyService;

    // ------------------------------------------------------------------ 列表

    /** 视频分页列表 */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/videos")
    public TableDataInfo videos(@RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "folder", required = false) String folder,
            @RequestParam(value = "person", required = false) String person,
            @RequestParam(value = "vcodec", required = false) String vcodec,
            @RequestParam(value = "hidden", required = false) String hidden,
            @RequestParam(value = "sort", required = false) String sort)
    {
        startPage();
        return mediaAdminService.selectVideoPage(keyword, folder, person, vcodec, hidden, sort);
    }

    /** 相册分页列表 */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/albums")
    public TableDataInfo albums(@RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "personId", required = false) Long personId,
            @RequestParam(value = "hidden", required = false) String hidden,
            @RequestParam(value = "sort", required = false) String sort)
    {
        startPage();
        return mediaAdminService.selectAlbumPage(keyword, personId, hidden, sort);
    }

    /** 某册图片分页列表 */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/photos")
    public TableDataInfo photos(@RequestParam(value = "albumId", required = false) String albumId)
    {
        startPage();
        return mediaAdminService.selectPhotoPage(albumId);
    }

    /** 详情：kind ∈ video / album / photo */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/detail/{kind}/{id}")
    public Map<String, Object> detail(@PathVariable("kind") String kind, @PathVariable("id") String id)
    {
        log.debug("管理端查询媒体详情 kind={} id={}", kind, id);
        return mediaAdminService.selectDetail(kind, id);
    }

    /** 人物下拉（id/name/albumCount/photoCount） */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/people")
    public List<Map<String, Object>> people()
    {
        return mediaAdminService.people();
    }

    /** 占用统计 */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/stats")
    public Map<String, Object> stats()
    {
        return mediaAdminService.stats();
    }

    /**
     * 配置自检：把项目搬到自己的环境后，先用它确认路径 / ffmpeg / node / 脚本都配对了
     *
     * <p>返回 {@code data} 内 {home, media:{rootPath:{value,exists,readable,writable,fileCount},
     * thumbsPath, trashPath, ffmpeg:{value,exists,executable}, nodePath, scanScript, thumbScript,
     * scanOutPath:{value,exists,writable}, maxTranscode, scanArgs},
     * counts:{videos,albums,photos,trashEntries}, warnings:[...]}。</p>
     *
     * <p>不返回任何密钥 / 密码，只回路径；单项检查失败只进 warnings，本接口自身不会 500。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/config")
    public AjaxResult config()
    {
        return AjaxResult.success(mediaAdminService.config());
    }

    // ------------------------------------------------------------------ 隐藏位

    /** 隐藏 / 取消隐藏：body {hidden:true|false}；photo 可用 body 的 albumId+seq 或路径上的 photoId */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-隐藏", businessType = BusinessType.UPDATE)
    @PatchMapping("/hidden/{kind}/{id}")
    public Map<String, Object> hidden(@PathVariable("kind") String kind, @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        Boolean flag = boolValue(body.get("hidden"));
        if (flag == null)
        {
            throw MediaException.badRequest("missing hidden");
        }
        return mediaAdminService.updateHidden(kind, id, flag, stringValue(body.get("albumId")),
                intValue(body.get("seq")));
    }

    /** 改相册人物归属：body {personId}（null = 未归类） */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-人物归属", businessType = BusinessType.UPDATE)
    @PatchMapping("/album/{id}/person")
    public Map<String, Object> albumPerson(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        if (!body.containsKey("personId"))
        {
            throw MediaException.badRequest("missing personId");
        }
        Long personId = longValue(body.get("personId"));
        return mediaAdminService.updateAlbumPerson(id, personId);
    }

    // ------------------------------------------------------------------ 软删 / 恢复

    /** 软删：移动到回收站并置 hidden=1（绝不真删） */
    @PreAuthorize("@ss.hasPermi('media:admin:remove')")
    @Log(title = "媒体库-软删", businessType = BusinessType.DELETE)
    @DeleteMapping("/{kind}/{id}")
    public Map<String, Object> remove(@PathVariable("kind") String kind, @PathVariable("id") String id,
            @RequestParam(value = "albumId", required = false) String albumId,
            @RequestParam(value = "seq", required = false) Integer seq)
    {
        String target = StringUtils.isNotEmpty(albumId) && seq != null ? albumId + ":" + seq : id;
        return mediaAdminService.delete(kind, target);
    }

    /** 从回收站恢复并置 hidden=0 */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-恢复", businessType = BusinessType.UPDATE)
    @PostMapping("/restore/{kind}/{id}")
    public Map<String, Object> restore(@PathVariable("kind") String kind, @PathVariable("id") String id,
            @RequestParam(value = "albumId", required = false) String albumId,
            @RequestParam(value = "seq", required = false) Integer seq)
    {
        String target = StringUtils.isNotEmpty(albumId) && seq != null ? albumId + ":" + seq : id;
        return mediaAdminService.restore(kind, target);
    }

    // ------------------------------------------------------------------ 回收站

    /** 回收站分页列表（以磁盘为准；同 id 多份软删各占一行） */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/trash")
    public TableDataInfo trash(@RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", required = false, defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", required = false, defaultValue = "20") int pageSize)
    {
        // 磁盘枚举后内存分页，不能走 PageHelper（startPage 只对 SQL 生效）
        return mediaAdminService.selectTrashPage(kind, keyword, pageNum, pageSize);
    }

    /** 回收站统计（count / bytes / byKind） */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/trash/stats")
    public Map<String, Object> trashStats()
    {
        return mediaAdminService.trashStats();
    }

    /** 从回收站恢复：body {kind,id}，复用既有 restore（按内嵌时间戳取最近一次软删） */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-回收站恢复", businessType = BusinessType.UPDATE)
    @PostMapping("/trash/restore")
    public Map<String, Object> trashRestore(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        String kind = stringValue(body.get("kind"));
        String id = stringValue(body.get("id"));
        if (StringUtils.isEmpty(kind) || StringUtils.isEmpty(id))
        {
            throw MediaException.badRequest("missing kind / id");
        }
        return mediaAdminService.restore(kind, id);
    }

    /** 彻底删除（真删，不可恢复）：只允许 trash 根下的对象，越界一律 403 */
    @PreAuthorize("@ss.hasPermi('media:admin:remove')")
    @Log(title = "媒体库-彻底删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/trash/{kind}/{id}")
    public Map<String, Object> trashPurge(@PathVariable("kind") String kind, @PathVariable("id") String id,
            @RequestParam(value = "trashRel", required = false) String trashRel)
    {
        return mediaAdminService.purgeTrash(kind, id, trashRel);
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 触发一次扫描 / 生成类任务（异步，同一时刻只允许 1 个）
     *
     * <p>body 可选字段：{@code mode} / {@code confirm} / {@code limit}；{@code type} 是旧参数，
     * mode 缺省时按 type 兜底（full → scan，thumb → thumb），既有调用行为不变。</p>
     *
     * <table border="1">
     *   <caption>mode → 子进程命令</caption>
     *   <tr><th>mode</th><th>命令</th></tr>
     *   <tr><td>scan</td><td>{@code node scanScript --out=…} + scanArgs（= 旧 type=full）</td></tr>
     *   <tr><td>thumb</td><td>{@code node thumbScript} + thumbArgs（= 旧 type=thumb）</td></tr>
     *   <tr><td>sprite</td><td>thumb 命令 + {@code --sprite}</td></tr>
     *   <tr><td>preview</td><td>thumb 命令 + {@code --preview}</td></tr>
     *   <tr><td>sprite,preview</td><td>thumb 命令 + {@code --sprite --preview}（组合模式只接受逗号分隔）</td></tr>
     *   <tr><td>gc</td><td>thumb 命令 + {@code --gc --yes}；必须 {@code confirm=true}，否则 400</td></tr>
     * </table>
     *
     * <p>生成 / 删除开关全部由后端按 mode 追加，前端<b>不能</b>传任意子进程参数；
     * {@code limit} 只对逐条生成的 mode（thumb / sprite / preview）生效，scan 与 gc 传了直接 400。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:scan')")
    @Log(title = "媒体库-重建索引", businessType = BusinessType.OTHER)
    @PostMapping("/scan")
    public Map<String, Object> scan(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, false);
        return mediaScanService.start(stringValue(body.get("type")), stringValue(body.get("mode")),
                booleanValue(body.get("confirm")), integerValue(body.get("limit")));
    }

    /** 最近一次扫描任务 */
    @PreAuthorize("@ss.hasPermi('media:admin:scan')")
    @GetMapping("/scan/latest")
    public Map<String, Object> scanLatest()
    {
        return mediaScanService.latest();
    }

    /** 最近 10 个扫描任务 */
    @PreAuthorize("@ss.hasPermi('media:admin:scan')")
    @GetMapping("/scan/list")
    public List<Map<String, Object>> scanList(@RequestParam(value = "limit", required = false) Integer limit)
    {
        return mediaScanService.recent(limit == null ? 10 : limit);
    }

    /** 指定扫描任务（含日志尾部，最多 200 行） */
    @PreAuthorize("@ss.hasPermi('media:admin:scan')")
    @GetMapping("/scan/{jobId}")
    public Map<String, Object> scanJob(@PathVariable("jobId") Long jobId)
    {
        return mediaScanService.getJob(jobId);
    }

    // ------------------------------------------------------------------ 组织能力（M3-A：标签 / 评分 / 标记）

    /**
     * 标签字典（含引用次数），按 useCount 降序
     *
     * <p>返回 {@code [{id,name,color,useCount}]}；前端据此渲染标签选择器，
     * 打标签时把 {@code id} 交给 {@code PUT /video/{id}/meta} 或批量接口。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/tags")
    public List<Map<String, Object>> tags()
    {
        return mediaOrganizeService.tagList();
    }

    /**
     * 新建标签：body {@code {name, color?}}
     *
     * <p><b>重名复用</b>：name 唯一，已存在则直接返回既有行（不报 409、不改颜色），
     * 响应里的 {@code created} 标识这次是新建成还是复用。name 去首尾空格后 1-32 字符。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-新建标签", businessType = BusinessType.INSERT)
    @PostMapping("/tags")
    public Map<String, Object> createTag(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaOrganizeService.createTag(stringValue(body.get("name")), stringValue(body.get("color")));
    }

    /** 删除标签字典项：同时清掉它在 media_video_tag 的全部关联（返回 unlinked 行数） */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-删除标签", businessType = BusinessType.DELETE)
    @DeleteMapping("/tags/{id}")
    public Map<String, Object> deleteTag(@PathVariable("id") Long id)
    {
        return mediaOrganizeService.deleteTag(id);
    }

    /**
     * 单条元数据：{@code {rating, tags:[{id,name,color}], markers:[{id,pos,label,color}]}}
     *
     * <p>{@code rating} 为 0-10 的整数，{@code null} = 未评分；{@code markers} 按 pos 升序。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/video/{id}/meta")
    public Map<String, Object> videoMeta(@PathVariable("id") String id)
    {
        return mediaOrganizeService.videoMeta(id);
    }

    /**
     * 给单条视频打补丁：body 支持全量与增量两种写法
     *
     * <table border="1">
     *   <caption>body 字段 → 语义</caption>
     *   <tr><th>字段</th><th>语义</th></tr>
     *   <tr><td>{@code rating}</td><td>0-10 整数；出现即覆盖，{@code null} = 清空评分</td></tr>
     *   <tr><td>{@code tagIds}</td><td><b>全量替换</b>：出现即把该视频的字典标签整体换成这一组（{@code []} = 清空）</td></tr>
     *   <tr><td>{@code removeTagIds}</td><td><b>增量摘除</b>（不存在的关联静默忽略，天然幂等）</td></tr>
     *   <tr><td>{@code addTagIds}</td><td><b>增量追加</b>（重复打同一个标签不会产生第二行）</td></tr>
     *   <tr><td>{@code markers}</td><td><b>全量替换</b>标记列表（{@code []} = 清空；pos 必须 0 &lt;= pos &lt;= dur，dur 为空则跳过上限）</td></tr>
     * </table>
     *
     * <p>合并顺序：{@code tagIds} 全量替换 → {@code removeTagIds} → {@code addTagIds}，
     * 同一 tagId 同时出现在 remove 与 add 时以 add 为准。返回补丁后的完整元数据。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-组织属性", businessType = BusinessType.UPDATE)
    @PutMapping("/video/{id}/meta")
    public Map<String, Object> updateVideoMeta(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        return mediaOrganizeService.updateVideoMeta(id, metaPatch(parseObject(rawBody, true), false));
    }

    /**
     * 批量打补丁：body {@code {ids:[...], addTagIds?, removeTagIds?, rating?, markerDelta?}}
     *
     * <p>ids 上限 500（去重后计算）。字段语义与单条一致，但标签只提供<b>增量</b>写法
     * （批量全量替换会把每个视频的其它标签一起清掉，风险大于收益，因此不提供）。</p>
     *
     * <p>{@code markerDelta} = {@code {"add":[{pos,label,color}],"remove":[markerId]}}：
     * add 的每条标记会广播到本批<b>每个</b>视频；remove 只删本批视频名下的标记 id。
     * 返回 {@code {updated, tagsAdded, tagsRemoved, markersAdded, markersRemoved}}，
     * 其中 {@code updated} = ids 里实际存在的视频条数（不存在的 id 静默跳过）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-批量组织属性", businessType = BusinessType.UPDATE)
    @PostMapping("/videos/batch-meta")
    public Map<String, Object> batchMeta(@RequestBody(required = false) String rawBody)
    {
        return mediaOrganizeService.batchMeta(metaPatch(parseObject(rawBody, true), true));
    }

    // ------------------------------------------------------------------ 站端编辑策略

    /** 站端编辑策略：{@code {level}}，level ∈ off / local / full */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/edit-policy")
    public Map<String, Object> editPolicy()
    {
        return Map.of("level", mediaEditPolicyService.getLevel());
    }

    /** 保存站端编辑策略：body {@code {level}}，非法值 400；保存后立即失效本地缓存 */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-编辑策略", businessType = BusinessType.UPDATE)
    @PutMapping("/edit-policy")
    public Map<String, Object> updateEditPolicy(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        mediaEditPolicyService.setLevel(stringValue(body.get("level")));
        return Map.of("level", mediaEditPolicyService.getLevel());
    }

    // ------------------------------------------------------------------ 组织能力补充（标签改名 / 单条标记）

    /**
     * 标签改名 / 改色：body {@code {name?, color?}}
     *
     * <p>{@code name} 缺省 = 不改名（撞别的字典项 409）；{@code color} 缺省 = 不改色，
     * JSON 里显式给 {@code "color": null} = <b>清空颜色</b>。改名会同步
     * {@code media_video_tag.name}，用户端索引立刻显示新名字。</p>
     *
     * <p>站端也允许改标签时按策略放开，但<b>本期只在管理端</b>提供。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-修改标签", businessType = BusinessType.UPDATE)
    @PatchMapping("/tags/{id}")
    public Map<String, Object> updateTag(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaOrganizeService.updateTag(id, stringValue(body.get("name")),
                stringValue(body.get("color")), body.containsKey("color"));
    }

    /**
     * 新增单个标记：body {@code {pos, label?, color?}}
     *
     * <p>与 {@code PUT /video/{id}/meta} 的 {@code markers} 全量替换互补：本接口只追加一条，
     * pos 必须 {@code 0 <= pos <= dur}（dur 为 NULL 时跳过上限），返回 {@code {id,pos,label,color}}。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-新增标记", businessType = BusinessType.INSERT)
    @PostMapping("/video/{id}/markers")
    public Map<String, Object> addMarker(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        MediaVideoMarker marker = new MediaVideoMarker();
        marker.setPos(decimalValue(body.get("pos"), "pos"));
        marker.setLabel(stringValue(body.get("label")));
        marker.setColor(stringValue(body.get("color")));
        return mediaOrganizeService.addMarker(id, marker);
    }
}
