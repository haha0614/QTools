package com.qtools.media.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import com.qtools.media.domain.dto.MediaScanIngestRequest;
import com.qtools.media.domain.dto.MediaScanRequest;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaAdminService;
import com.qtools.media.service.IMediaDeriveStatusService;
import com.qtools.media.service.IMediaEditPolicyService;
import com.qtools.media.service.IMediaMaintenanceService;
import com.qtools.media.service.IMediaOrganizeService;
import com.qtools.media.service.IMediaPersonCardBgService;
import com.qtools.media.service.IMediaPersonService;
import com.qtools.media.service.IMediaPlaylistService;
import com.qtools.media.service.IMediaRatingService;
import com.qtools.media.service.IMediaRootArtifactService;
import com.qtools.media.service.IMediaSavedFilterService;
import com.qtools.media.service.IMediaScanIngestService;
import com.qtools.media.service.IMediaScanService;
import com.qtools.media.service.IMediaStudioService;
import com.qtools.media.service.IMediaSynopsisService;
import com.qtools.media.service.IMediaWatchService;
import static com.qtools.media.support.MediaBodyParser.booleanValue;
import static com.qtools.media.support.MediaBodyParser.boolValue;
import static com.qtools.media.support.MediaBodyParser.decimalValue;
import static com.qtools.media.support.MediaBodyParser.intValue;
import static com.qtools.media.support.MediaBodyParser.integerValue;
import static com.qtools.media.support.MediaBodyParser.longValue;
import static com.qtools.media.support.MediaBodyParser.longsValue;
import static com.qtools.media.support.MediaBodyParser.metaPatch;
import static com.qtools.media.support.MediaBodyParser.optionalDecimalValue;
import static com.qtools.media.support.MediaBodyParser.personPatch;
import static com.qtools.media.support.MediaBodyParser.rolesValue;
import static com.qtools.media.support.MediaBodyParser.parseObject;
import static com.qtools.media.support.MediaBodyParser.stringValue;
import static com.qtools.media.support.MediaBodyParser.stringsValue;
import static com.qtools.media.support.MediaBodyParser.studioPatch;
import static com.qtools.media.support.MediaBodyParser.textValue;

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

    /** 扫描入库（P5.91）：遍历某个库根目录写库，任务只在内存、带实时日志与取消 */
    @Autowired
    private IMediaScanIngestService mediaScanIngestService;

    @Autowired
    private IMediaOrganizeService mediaOrganizeService;

    /** 作品简介自动生成（模板 / 本地模型 / 批量任务），P5.47 */
    @Autowired
    private IMediaSynopsisService mediaSynopsisService;

    /** 人物 / 演员体系（P3-A）：字典 CRUD + 视频/相册人物绑定 */
    @Autowired
    private IMediaPersonService mediaPersonService;

    /** 人物卡背景预渲染（P5.90 / 预热 P5.92）：管理与排障用的手动预热入口 */
    @Autowired
    private IMediaPersonCardBgService mediaPersonCardBgService;

    /** 工作室（P5-A）：字典 CRUD + 视频绑定全量替换 */
    @Autowired
    private IMediaStudioService mediaStudioService;

    /** 日常维护：多库根 media_root + 便携包导出产物 */
    @Autowired
    private IMediaMaintenanceService mediaMaintenanceService;

    /** 产物根子目录清单（P5.80）：一个产物根里 7 类派生资源各占多少空间 */
    @Autowired
    private IMediaRootArtifactService mediaRootArtifactService;

    /** 站端编辑策略（off / local / full）：管理端「编辑策略」面板读它、改它 */
    @Autowired
    private IMediaEditPolicyService mediaEditPolicyService;

    /** 内容分级（P5.99）：视频 / 相册的单条与批量分级 + 分级可见性策略 */
    @Autowired
    private IMediaRatingService mediaRatingService;

    /** 保存的筛选条件（站端写、管理端只读清单 + 清理） */
    @Autowired
    private IMediaSavedFilterService mediaSavedFilterService;

    /** 播放列表（站端写、管理端只读清单 + 清理，P5-B） */
    @Autowired
    private IMediaPlaylistService mediaPlaylistService;

    /** 派生资源状态（P5.10-A）：列表里的「是否已经生成 / 生成了哪些东西」 */
    @Autowired
    private IMediaDeriveStatusService mediaDeriveStatusService;

    /**
     * 文件监听（自动扫描）：<b>常驻 Bean</b>（不再由 {@code qtools.media.watch.enabled} 决定是否注册）
     *
     * <p>「听不听」是运行期状态（{@code sys_config.media.watchEnabled}），所以 bean 一定存在：
     * 关闭时状态接口照常可查、{@code scan-now} 照常可用，只有 pause / resume 会 409。</p>
     */
    @Autowired
    private IMediaWatchService mediaWatchService;

    // ------------------------------------------------------------------ 列表

    /**
     * 视频分页列表
     *
     * <p>{@code tagId} 是<b>层级筛选</b>：服务端先把该标签按 parent 链展开成「自身 + 全部后代」，
     * 再按 {@code media_video_tag.tag_id} 过滤 —— 选父标签能命中挂在子标签下的视频。
     * 站端筛选在前端做（索引接口无服务端筛选参数），前端可用 {@code GET /api/media/tags}
     * 里的 {@code parentId} 自行展开，语义与此处一致。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/videos")
    public TableDataInfo videos(@RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "folder", required = false) String folder,
            @RequestParam(value = "person", required = false) String person,
            @RequestParam(value = "vcodec", required = false) String vcodec,
            @RequestParam(value = "hidden", required = false) String hidden,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "tagId", required = false) Long tagId,
            @RequestParam(value = "ids", required = false) String ids)
    {
        startPage();
        return mediaAdminService.selectVideoPage(keyword, folder, person, vcodec, hidden, sort, tagId, ids);
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

    /**
     * 某册全部图片（<b>只读库</b>，不列磁盘）：管理端相册预览弹窗的数据源
     *
     * <p>返回裸数组 {@code [{id,seq,relPath,missing,bytes,w,h,thumbUrl,previewUrl,thumbVersion,
     * contentHash,lastSeen}]}，缺失行排在最后（{@code order by missing asc, seq asc}）。</p>
     *
     * <p><b>thumbUrl / previewUrl 是内容寻址的</b>：有 {@code content_hash} 就给
     * {@code /api/media/thumb/p/<hash>}（预览图 {@code /api/media/preview/p/<hash>}），
     * 老库还没回填哈希才回退 {@code /api/media/thumb/p/<albumId>/<%04d seq>}。前者响应头是
     * {@code Cache-Control: public, max-age=31536000, immutable}，后者是
     * {@code no-cache, must-revalidate}——序号 URL 重建后内容会变而 URL 不变，不能再让浏览器
     * 按 URL 缓存（「预览弹窗显示上一格的脸」就是 7 天强缓存导致的）。</p>
     * 与旧的 {@code GET /photos} 的区别：那个是分页的库内列表（且没有 missing/contentHash），
     * 这个一次给全册，由前端自己切片；两者都<b>不</b>读磁盘。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/album/{id}/photos")
    public List<Map<String, Object>> albumPhotos(@PathVariable("id") String id)
    {
        return mediaAdminService.albumPhotos(id);
    }

    /**
     * 缺失记录分页（{@code missing = 1} 的行）
     *
     * <p>查询参数：{@code kind=photo|video|all}（默认 photo）、{@code albumId}（photo 用）、
     * {@code pageNum/pageSize}。返回 {@code {rows:[{kind,id,albumId,seq,title,relPath,bytes,
     * contentHash,missing,lastSeen,missingSince}], total, kind}}。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/missing")
    public Map<String, Object> missing(@RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "albumId", required = false) String albumId,
            @RequestParam(value = "pageNum", required = false, defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", required = false, defaultValue = "20") int pageSize)
    {
        return mediaAdminService.missingList(kind, albumId, pageNum, pageSize);
    }

    /**
     * 清理缺失记录：body {@code {kind:"photo"|"video"|"all", ids?:[...]}} 或 {@code {kind, all:true}}
     *
     * <p>为什么<b>硬删</b>而不是走回收站：这些行指向的文件在磁盘上根本不存在，回收站里没有源文件
     * 可搬，软删只会留下「hidden=1 但源不存在」的僵尸行（正是本次要清理的东西）。删除有安全闸：
     * SQL 里恒带 {@code where missing = 1}，扫描又把文件找回来（missing=0）的行永远删不掉。</p>
     *
     * @return {@code {deleted, kind, all, photos, videos}}
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-清理缺失记录", businessType = BusinessType.DELETE)
    @PostMapping("/missing/prune")
    public Map<String, Object> pruneMissing(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        String kind = stringValue(body.get("kind"));
        List<String> ids = stringsValue(body.get("ids"), "ids");
        boolean all = Boolean.TRUE.equals(boolValue(body.get("all")));
        return mediaAdminService.pruneMissing(kind, ids, all);
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
     * 派生资源状态分页（P5.10-A）：列表里直接显示「封面 / 精灵图 / 预览片段」生成没生成
     *
     * <p>查询参数：{@code page}（从 1 开始）、{@code size}（默认 50，最大 200）、{@code ids}
     * （逗号分隔的 videoId，≤200，配合「只勾选这几条」）、{@code q}（标题 / 路径模糊）、
     * {@code kind}（all | thumb | sprite | preview；后三者只回「该档缺失或过期」的行）。</p>
     *
     * <p>返回 {@code {rows:[{videoId,title,dur,thumb:{exists,stale},sprite:{exists,stale},
     * preview:{exists,stale,frames},bytes}], total, page, size, kind}}。判定口径与
     * build-thumbs.js 的增量判定一致（产物存在非空 = exists；源 mtime &gt; 产物 mtime = stale），
     * 目录<b>一次扫描</b>后逐行查表，预览帧数恒为 null（不逐行 ffprobe，见服务实现注释）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/derive-status")
    public Map<String, Object> deriveStatus(@RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "50") int size,
            @RequestParam(value = "ids", required = false) String ids,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "state", required = false) String state)
    {
        return mediaDeriveStatusService.deriveStatus(ids, q, kind, state, page, size);
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

    // ------------------------------------------------------------------ 内容分级（P5.99）

    /**
     * 单条视频改分级：body {@code {rating:"general"|"guidance"|"restricted"}}
     *
     * <p>非法值 400（值域与校验集中在 {@code MediaRating} 一处）；改完立刻失效访客可见性快照
     * 与索引快照，所以未登录访客下一次请求就是新口径，不用等 TTL。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-内容分级", businessType = BusinessType.UPDATE)
    @PutMapping("/video/{id}/rating")
    public Map<String, Object> videoRating(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaRatingService.updateVideoRating(id, stringValue(body.get("rating")));
    }

    /** 单本相册改分级：body {@code {rating}}（整册属性，册里的图片继承它） */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-内容分级", businessType = BusinessType.UPDATE)
    @PutMapping("/album/{id}/rating")
    public Map<String, Object> albumRating(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaRatingService.updateAlbumRating(id, stringValue(body.get("rating")));
    }

    /**
     * 批量改分级：body {@code {type:"video"|"album", ids:[...], rating}}
     *
     * <p>管理端列表多选后一次提交；返回 {@code {kind,rating,label,requested,matched,updated}}，
     * {@code matched} 与 {@code requested} 的差 = 不存在（已被扫描删掉）的 id 数。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-批量内容分级", businessType = BusinessType.UPDATE)
    @PostMapping("/rating/batch")
    public Map<String, Object> ratingBatch(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaRatingService.batch(stringValue(body.get("type")), stringsValue(body.get("ids"), "ids"),
                stringValue(body.get("rating")));
    }

    /**
     * 读分级可见性策略：{@code {general, guidance, restricted, items:[{value,label,guestVisible}]}}
     *
     * <p>{@code guestVisible=true} = 未登录可见。三档默认都可见（与迁移预置一致）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/rating-policy")
    public Map<String, Object> ratingPolicy()
    {
        return mediaRatingService.policy();
    }

    /**
     * 改分级可见性策略：body {@code {general?:bool, guidance?:bool, restricted?:bool}}
     *
     * <p><b>逐项局部更新</b>：不传 = 不改；传 true/false = 覆盖。管理端「库根管理」页的
     * 「分级与可见性」区就是这三个开关，一次只翻一个也照常工作。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-分级可见性", businessType = BusinessType.UPDATE)
    @PutMapping("/rating-policy")
    public Map<String, Object> updateRatingPolicy(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaRatingService.updatePolicy(boolValue(body.get("general")), boolValue(body.get("guidance")),
                boolValue(body.get("restricted")));
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
     * <p>body 可选字段：{@code mode} / {@code confirm} / {@code limit} / {@code albumMode} / {@code exclude} /
     * {@code noFresh} / {@code force}；{@code type} 是旧参数，mode 缺省时按 type 兜底
     * （full → scan，thumb → thumb），既有调用行为不变。</p>
     *
     * <table border="1">
     *   <caption>mode → 子进程命令</caption>
     *   <tr><th>mode</th><th>命令</th></tr>
     *   <tr><td>scan</td><td>{@code node scanScript --out=…} + scanArgs
     *       [+ {@code --album-mode=dir|prefix}] [+ {@code --exclude=a,b}]（= 旧 type=full）</td></tr>
     *   <tr><td>thumb</td><td>{@code node thumbScript} + thumbArgs [+ {@code --no-fresh}] [+ {@code --force}]
     *       （= 旧 type=thumb）</td></tr>
     *   <tr><td>sprite</td><td>thumb 命令 + {@code --sprite} [+ {@code --force}]</td></tr>
     *   <tr><td>preview</td><td>thumb 命令 + {@code --preview} [+ {@code --force}]</td></tr>
     *   <tr><td>sprite,preview</td><td>thumb 命令 + {@code --sprite --preview} [+ {@code --force}]
     *       （组合模式只接受逗号分隔）</td></tr>
     *   <tr><td>gc</td><td>thumb 命令 + {@code --gc --yes}；必须 {@code confirm=true}，否则 400</td></tr>
     *   <tr><td>enrich</td><td>{@code node enrichScript} + enrichArgs [+ {@code --limit=n}]（ffprobe 走 MS_FFPROBE）</td></tr>
     *   <tr><td>backfill-hash</td><td>{@code node scanScript --out=…} + scanArgs + {@code --backfill-hash}</td></tr>
     *   <tr><td>fix-poster</td><td>{@code node scanScript --out=…} + scanArgs + {@code --fix-poster-path}</td></tr>
     *   <tr><td>export</td><td>{@code node exportScript} + exportArgs + {@code --out=<exportPath>/<yyyyMMdd-HHmmss>}；
     *       产物目录由后端生成，任务结束写 {@code EXPORT ok dir=… files=… bytes=…} 日志行</td></tr>
     * </table>
     *
     * <p>P5.91「扫描入库」是同一个 URL 的另一个形状：body 里出现 {@code rootId}（或 {@code mode=ingest}）时不再
     * 走 mode 分支，而是交给 {@code IMediaScanIngestService}——媒体根从 {@code media_root} 里按 id 取，
     * 不再由 {@code scanArgs} 写死；回包是 {@code {jobId, command, startedAt, ...}}，
     * 后续用 {@code GET /scan/jobs/{jobId}} 轮询实时日志。字段与默认值见
     * {@link com.qtools.media.domain.dto.MediaScanIngestRequest}。</p>
     *
     * <p>生成 / 删除 / 回填开关全部由后端按 mode 追加，前端<b>不能</b>传任意子进程参数：
     * {@code albumMode} 只接受 dir|prefix 且只对 scan 生效，{@code exclude} 只允许目录名文本
     * （带空格 / 引号 / 分号一律 400），{@code noFresh} 与 {@code force} 只对 thumb / sprite / preview
     * 生效（gc / scan / enrich / backfill-hash / fix-poster / export 传了直接 400）且互斥，
     * {@code force=true} 时三档都会追加 {@code --force} 越过 mtime 新鲜度判定、全部重建；
     * {@code limit} 只对 thumb / sprite / preview / enrich 生效（其余传了直接 400）。</p>
     */
    @PreAuthorize("@ss.hasAnyPermi('media:admin:scan,media:admin:edit')")
    @Log(title = "媒体库-重建索引", businessType = BusinessType.OTHER)
    @PostMapping("/scan")
    public Map<String, Object> scan(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, false);
        if (body.containsKey("rootId") || "ingest".equals(stringValue(body.get("mode"))))
        {
            // P5.91 扫描入库：body {rootId, dryRun?, withVideo?, withPhoto?, limit?, force?} → {jobId, command, startedAt}
            // 与下面的 mode 分支共用同一个 URL，是因为「扫描」本来就只有一件事——遍历磁盘入库；
            // 差别只在参数来源（mode 分支的媒体根写死在 scanArgs 里，这条从 media_root 里选）。
            MediaScanIngestRequest ingest = new MediaScanIngestRequest();
            ingest.setRootId(longValue(body.get("rootId")));
            ingest.setDryRun(boolValue(body.get("dryRun")));
            ingest.setWithVideo(boolValue(body.get("withVideo")));
            ingest.setWithPhoto(boolValue(body.get("withPhoto")));
            ingest.setLimit(integerValue(body.get("limit")));
            ingest.setForce(boolValue(body.get("force")));
            // P5.97：扫完之后要不要接着补哈希 / 生成缩略图 / 重建索引快照（缺省全要）
            ingest.setPipeline(boolValue(body.get("pipeline")));
            ingest.setHash(boolValue(body.get("hash")));
            ingest.setRebuildIndex(boolValue(body.get("rebuildIndex")));
            ingest.setDerive(stringValue(body.get("derive")));
            return mediaScanIngestService.start(ingest);
        }
        MediaScanRequest request = new MediaScanRequest(stringValue(body.get("type")), stringValue(body.get("mode")),
                booleanValue(body.get("confirm")), integerValue(body.get("limit")));
        // 下面四个可选参数由服务层校验取值并拼进子进程命令；前端传不进任意参数
        request.setAlbumMode(stringValue(body.get("albumMode")));
        request.setExclude(stringValue(body.get("exclude")));
        request.setNoFresh(booleanValue(body.get("noFresh")));
        request.setForce(booleanValue(body.get("force")));
        return mediaScanService.start(request);
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

    // ------------------------------------------------------------------ 文件监听（自动扫描）

    /**
     * 文件监听状态：{@code {allowed,enabled,running,paused,debounceSeconds,quietMinutes,roots:[...],hint}}
     *
     * <p>{@code allowed}=部署层总闸（{@code qtools.media.watch.allowRuntimeToggle}），{@code enabled}=
     * {@code sys_config.media.watchEnabled} 的值，{@code running}=监听是否真的挂上。
     * {@code roots[]} 每项 {@code {id,path,registered,lastEventAt,lastScanAt,pending,pendingEvents}}：
     * 「哪些根真的挂上了监听、最后一次事件 / 最后一次自动扫描是什么时候、有没有攒下待补跑」的唯一可视入口。</p>
     *
     * <p>关闭（{@code enabled=false}）时本接口照样 200：只是 {@code running=false} + {@code hint}
     * 提示用 {@code PUT /watch/config} 打开，读状态不该 404 / 500。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/watch")
    public Map<String, Object> watch()
    {
        return mediaWatchService.status();
    }

    /**
     * 运行期改自动扫描配置：body {@code {enabled?, debounceSeconds?, quietMinutes?}}，落库后立即生效
     *
     * <p>校验在服务层：部署层 {@code allowRuntimeToggle=false} → 403；{@code debounceSeconds} 只能 1-60、
     * {@code quietMinutes} 只能空或 {@code HH:mm-HH:mm}（跨零点允许），非法 400。返回与
     * {@code GET /watch} 同结构的最新状态。缺省的字段不改。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-自动扫描配置", businessType = BusinessType.UPDATE)
    @PutMapping("/watch/config")
    public Map<String, Object> updateWatchConfig(@RequestBody(required = false) String rawBody)
    {
        return mediaWatchService.updateConfig(parseObject(rawBody, true));
    }

    /** 暂停自动扫描（运行期开关，不写配置；{@code media.watchEnabled=false} 时 409） */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-暂停自动扫描", businessType = BusinessType.UPDATE)
    @PostMapping("/watch/pause")
    public Map<String, Object> watchPause()
    {
        return mediaWatchService.pause();
    }

    /** 恢复自动扫描（运行期开关，不写配置） */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-恢复自动扫描", businessType = BusinessType.UPDATE)
    @PostMapping("/watch/resume")
    public Map<String, Object> watchResume()
    {
        return mediaWatchService.resume();
    }

    /**
     * 立即扫一次（等价管理端「重建索引」按钮）
     *
     * <p>不走去抖、不受暂停影响；监听没开启时也能用（直接走 {@code mode=scan}）。
     * 已有任务在跑时与按钮同口径：409。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-立即扫描", businessType = BusinessType.OTHER)
    @PostMapping("/watch/scan-now")
    public Map<String, Object> watchScanNow()
    {
        return mediaWatchService.scanNow();
    }

    // ------------------------------------------------------------------ 保存的筛选条件（管理端）

    /**
     * 全量筛选条件清单：{@code [{id,name,params,ownerKey,global,createTime,updateTime}]}
     *
     * <p>站端保存的条目按 {@code owner_key} 隔离，管理端是唯一能看到全部（含全局条目）的地方——
     * 只读，不提供新建：筛选条件的语义由前端决定，管理端改了也没人用。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/filters")
    public List<Map<String, Object>> filterList()
    {
        return mediaSavedFilterService.adminList();
    }

    /** 清理任一条筛选条件（不看归属；不存在 404） */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-清理保存筛选", businessType = BusinessType.DELETE)
    @DeleteMapping("/filters/{id}")
    public Map<String, Object> filterDelete(@PathVariable("id") Long id)
    {
        return mediaSavedFilterService.adminDelete(id);
    }

    // ------------------------------------------------------------------ 播放列表（管理端，P5-B）

    /**
     * 全量播放列表：{@code [{id,name,count,ownerKey,global,note,createTime,updateTime}]}
     *
     * <p>与筛选条件同一分工：列表按 {@code owner_key} 隔离，管理端是唯一能看到全部（含全局列表）
     * 的地方——只读，不提供新建：列表里装什么由用户在站端自己攒。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/playlists")
    public List<Map<String, Object>> playlistList()
    {
        return mediaPlaylistService.adminList();
    }

    /** 清理任一个播放列表（不看归属，连条目一起删；不存在 404） */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-清理播放列表", businessType = BusinessType.DELETE)
    @DeleteMapping("/playlists/{id}")
    public Map<String, Object> playlistDelete(@PathVariable("id") Long id)
    {
        return mediaPlaylistService.adminDelete(id);
    }

    // ------------------------------------------------------------------ 多库根（media_root）

    /**
     * 多库根清单
     *
     * <p>返回 {@code [{id,name,path,kind,note,exists,readable,videoCount,photoCount,isDefault}]}：
     * 「配了几个根、哪个根现在没挂上、每个根里有多少媒体」的唯一可视入口。条数由一次
     * {@code group by root_id} 得到，{@code root_id} 为 NULL 的存量行并入默认根
     * （{@code kind='root'} 的第一行）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/roots")
    public List<Map<String, Object>> roots()
    {
        return mediaMaintenanceService.listRoots();
    }

    /**
     * 新增库根：body {@code {name, path, kind?, note?, guestVisible?}}；path 必须存在且是目录、不能与已有根重复
     *
     * <p>{@code guestVisible}（P5.78）：true=未登录可见（缺省值，＝改动前行为），false=仅登录可见。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-新增库根", businessType = BusinessType.INSERT)
    @PostMapping("/roots")
    public Map<String, Object> createRoot(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaMaintenanceService.createRoot(stringValue(body.get("name")), stringValue(body.get("path")),
                stringValue(body.get("kind")), stringValue(body.get("note")), boolValue(body.get("guestVisible")));
    }

    /**
     * 改库根：body {@code {name?, path?, note?, guestVisible?}}
     *
     * <p>{@code note} 出现且为空串 = 清空备注；不出现 = 保持原值。改 {@code path} <b>不会</b>自动重扫，
     * 返回体的 {@code pathChanged} / {@code hint} 会提示还要跑一次 {@code mode=scan}。</p>
     *
     * <p>{@code guestVisible}（P5.78）是<b>真正的局部更新</b>：不传 = 不改；传 true/false = 覆盖。
     * 管理端「库根管理」页的行内开关就是这条：{@code PUT /api/media/admin/roots/{id} {"guestVisible":false}}，
     * 写完立即让站端判定缓存失效，未登录访客下一次请求就看不到该根。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-修改库根", businessType = BusinessType.UPDATE)
    @PutMapping("/roots/{id}")
    public Map<String, Object> updateRoot(@PathVariable("id") Integer id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        String note = null;
        if (body.containsKey("note"))
        {
            String raw = stringValue(body.get("note"));
            note = raw == null ? "" : raw;
        }
        return mediaMaintenanceService.updateRoot(id, stringValue(body.get("name")),
                stringValue(body.get("path")), note, boolValue(body.get("guestVisible")));
    }

    /**
     * 产物根子目录清单（P5.80）：{@code GET /api/media/admin/roots/{id}/artifacts?refresh=true}
     *
     * <p>一个产物根（{@code kind='thumbs'}）包含<b>全部</b>派生资源，按子目录分成 7 类：
     * {@code p} 图片缩略图 / {@code v} 视频封面 / {@code s} 精灵图 / {@code pv} 旧预览片段 /
     * {@code pv2} 预览片段 / {@code frames} 取帧缓存 / {@code avcrop} 头像裁剪缓存。返回每类的
     * 文件数与占用，外加「未登记的子目录」和根合计，专门给清理缓存用。</p>
     *
     * <ul>
     *   <li>只有 {@code kind=thumbs} 有清单：其它 kind → 400（内容根放源文件，没有派生产物）；</li>
     *   <li>id 不存在 → 404；根目录当前不可用（盘没挂）→ 200 + {@code exists=false} + {@code note}；</li>
     *   <li>统计带边界：最多 20 万文件 / 8 秒 / 6 层深，超限返回 {@code partial=true} 与已统计量；</li>
     *   <li>结果缓存 60s，{@code refresh=true} 强制重扫（管理端「重扫」按钮）。</li>
     * </ul>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/roots/{id}/artifacts")
    public Map<String, Object> rootArtifacts(@PathVariable("id") Integer id,
            @RequestParam(value = "refresh", required = false) String refresh)
    {
        return mediaRootArtifactService.artifacts(id, Boolean.TRUE.equals(boolValue(refresh)));
    }

    /** 删除库根：{@code media_video} / {@code media_photo} 仍有引用时 400，message 里带引用数 */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-删除库根", businessType = BusinessType.DELETE)
    @DeleteMapping("/roots/{id}")
    public Map<String, Object> deleteRoot(@PathVariable("id") Integer id)
    {
        return mediaMaintenanceService.deleteRoot(id);
    }

    // ------------------------------------------------------------------ 导出产物

    /** 最近 10 次导出目录 + 每目录文件清单与大小（每个文件带下载地址） */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/export/list")
    public List<Map<String, Object>> exportList()
    {
        return mediaMaintenanceService.exportList();
    }

    /**
     * 下载一个导出产物：{@code GET /api/media/admin/export/download?dir=yyyyMMdd-HHmmss&file=media.sql.gz}
     *
     * <p>安全边界：{@code dir} 只接受 {@code yyyyMMdd-HHmmss}，{@code file} 的扩展名只接受
     * sql / gz / json / zip，解析（含 {@code toRealPath()} 复核）后必须仍在 {@code qtools.media.exportPath}
     * 之下——越界一律 403，不存在 404，名字不合法 400。以流式附件返回，不整文件读入内存。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/export/download")
    public ResponseEntity<Resource> exportDownload(@RequestParam("dir") String dir,
            @RequestParam("file") String file)
    {
        Path target = mediaMaintenanceService.resolveExportFile(dir, file);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(target.getFileName().toString(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(target));
    }

    // ------------------------------------------------------------------ 组织能力（M3-A：标签 / 评分 / 标记）

    /**
     * 标签字典（含引用次数与层级），按 useCount 降序
     *
     * <p>返回 {@code [{id,name,parentId,color,useCount,path}]}：{@code parentId} 为 null = 顶层标签，
     * {@code path} 是顶层到自身的名字链（如 {@code 演员/日本/XX}）。前端据此渲染标签树 / 选择器，
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
     * <p>{@code rating} 为 0-100 的整数，{@code null} = 未评分；{@code markers} 按 pos 升序。</p>
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
     *   <tr><td>{@code rating}</td><td>0-100 整数（越界 400）；出现即覆盖，{@code null} = 清空评分</td></tr>
     *   <tr><td>{@code tagIds}</td><td><b>全量替换</b>：出现即把该视频的字典标签整体换成这一组（{@code []} = 清空）</td></tr>
     *   <tr><td>{@code removeTagIds}</td><td><b>增量摘除</b>（不存在的关联静默忽略，天然幂等）</td></tr>
     *   <tr><td>{@code addTagIds}</td><td><b>增量追加</b>（重复打同一个标签不会产生第二行）</td></tr>
     *   <tr><td>{@code markers}</td><td><b>全量替换</b>标记列表（{@code []} = 清空）。每项 {@code {pos,label?,color?,endPos?}}：
     *       pos 必须 0 &lt;= pos &lt;= dur（dur 为空则跳过上限），{@code endPos} 缺省 / null = 单点标记，
     *       给值时必须 pos &lt; endPos &lt;= dur，非法 400；单视频最多 100 条</td></tr>
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

    /**
     * 单片作品简介（P5.38）：{@code {id, synopsis}}，{@code synopsis} 为 null = 暂无简介
     *
     * <p>站端有同名的匿名读接口 {@code GET /api/media/video/{id}/synopsis}；
     * 管理端这份给「后台逐条核对文案」用（可读软删行：读接口不过滤 hidden）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/video/{id}/synopsis")
    public Map<String, Object> videoSynopsis(@PathVariable("id") String id)
    {
        return mediaOrganizeService.videoSynopsis(id);
    }

    /**
     * 写单片作品简介（P5.38）：body {@code {synopsis}}（null 或空串 = 清空）→ {@code {id, synopsis}}
     *
     * <p>与站端 {@code PUT /api/media/video/{id}/synopsis} 同能力、<b>不分档位</b>：管理端本来就是
     * 管理员在改，编辑策略（off / local / full）管的是「站端访客能不能写」。
     * 路径刻意用复数 {@code /videos/{id}/synopsis}，与既有的 {@code PUT /videos/{id}/persons}、
     * {@code PUT /videos/{id}/studios} 同一写法。</p>
     *
     * <p>校验（全部在服务层）：长度 ≤ 8000 字符（超限 400）、纯空白归一化成 NULL、换行保留；
     * 写完 {@code invalidateSnapshot()}，站端索引最多 60s 的缓存也不会拖住这次修改。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-作品简介", businessType = BusinessType.UPDATE)
    @PutMapping("/videos/{id}/synopsis")
    public Map<String, Object> updateVideoSynopsis(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        if (!body.containsKey("synopsis"))
        {
            throw MediaException.badRequest("synopsis 必填（null 或空串 = 清空）");
        }
        return mediaOrganizeService.updateVideoSynopsis(id, textValue(body.get("synopsis"), "synopsis"));
    }

    // ------------------------------------------------------------------ 作品简介自动生成（P5.47）

    /**
     * 生成能力：{@code {template:{maxChars}, llm:{enabled,provider,model}}}
     *
     * <p>前端据此决定「⚡ 自动生成」默认走本地模型还是模板；不含 baseUrl / apiKey。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/synopsis/capabilities")
    public Map<String, Object> synopsisCapabilities()
    {
        return mediaSynopsisService.capabilities();
    }

    /**
     * 模板生成（P5.47 交付 1）：<b>预览，不落库</b> → {@code {id, synopsis, chars, mode:"template"}}
     *
     * <p>为什么是 POST 而不是 GET：它虽然不写库，但不是幂等读取——同一份素材以后可能换规则，
     * 缓存住「生成结果」没有意义；POST 也让调用方没法被浏览器预取。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/videos/{id}/synopsis/template")
    public Map<String, Object> synopsisTemplate(@PathVariable("id") String id)
    {
        return mediaSynopsisService.template(id);
    }

    /**
     * 本地模型生成（P5.47 交付 2，<b>预览，不落库</b>）→ {@code {id, synopsis, chars, mode:"llm", model, provider, ms}}
     *
     * <p>未启用 / Ollama 没起 / 超时 / 返回空 一律 503 + 明确文案（错误体仍是 {@code {"error":"..."}}），
     * 前端据此显示「本地模型未就绪（Ollama 未启动？）」并自动回退到模板生成。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/videos/{id}/synopsis/llm")
    public Map<String, Object> synopsisLlm(@PathVariable("id") String id)
    {
        return mediaSynopsisService.llm(id);
    }

    /**
     * 用音频转写重新生成（P5.57，<b>预览，不落库</b>）→ 回包结构同 {@code /synopsis/llm}
     *
     * <p>给「字幕根本不准」时的手动入口：<b>只走</b> whisper 转写（ffmpeg 抽 16k 单声道 →
     * whisper-cli {@code -oj}），不看既有字幕也不看画面。转写不可用 / 正文太少一律 200 +
     * {@code insufficient:true} + {@code audioReason}，由前端显示原因，不做静默降级。</p>
     *
     * <p>单条限时 {@code qtools.media.llm.transcribe.timeoutMs}（默认 120s）：超时即 insufficient，
     * 不会把前端挂在一个没有上界的推理上。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-音频转写生成简介", businessType = BusinessType.UPDATE)
    @PostMapping("/videos/{id}/synopsis/audio")
    public Map<String, Object> synopsisAudio(@PathVariable("id") String id)
    {
        return mediaSynopsisService.audio(id);
    }

    /**
     * 批量生成（P5.47 交付 2）：body {@code {videoIds:[...] | all:true, mode:"template"|"llm", overwrite:false}}
     *
     * <p>创建后台任务并立即返回任务节点；任务**逐个**处理，每完成一条立刻落库（生成一个替换一个），
     * 进度与逐条结果用 {@code GET /synopsis/jobs/{jobId}} 查。
     * {@code overwrite=false}（默认）时已有简介的视频直接跳过。</p>
     *
     * <p>{@code mode="llm"} 而本地模型没启用 → 503（而不是静默降级成模板：调用方显式要了模型，
     * 悄悄换实现比失败更难排查）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-批量生成简介", businessType = BusinessType.UPDATE)
    @PostMapping("/synopsis/generate")
    public Map<String, Object> synopsisGenerate(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaSynopsisService.startBatch(stringsValue(body.get("videoIds"), "videoIds"),
                booleanValue(body.get("all")), stringValue(body.get("mode")), booleanValue(body.get("overwrite")));
    }

    /** 批量任务进度与逐条结果：{@code {jobId,mode,status,total,processed,generated,skipped,failed,counts,skipReasons,items:[...]}} */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/synopsis/jobs/{jobId}")
    public Map<String, Object> synopsisJob(@PathVariable("jobId") String jobId)
    {
        return mediaSynopsisService.job(jobId);
    }

    /** 正在跑的批量任务（P5.55）：{@code {count,jobs:[...]}} */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/synopsis/jobs/active")
    public Map<String, Object> synopsisActiveJobs()
    {
        return mediaSynopsisService.activeJobs();
    }

    /** 批量前的影响面预览（P5.55 两步确认）：body {@code {videoIds? | all:true, overwrite?}} */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/synopsis/preview")
    public Map<String, Object> synopsisPreview(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaSynopsisService.preview(stringsValue(body.get("videoIds"), "videoIds"),
                booleanValue(body.get("all")), booleanValue(body.get("overwrite")));
    }

    /** 请求停止（P5.55）：协作式，不打断正在生成的那一条；幂等 */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-停止批量生成简介", businessType = BusinessType.UPDATE)
    @PostMapping("/synopsis/jobs/{jobId}/cancel")
    public Map<String, Object> synopsisJobCancel(@PathVariable("jobId") String jobId)
    {
        return mediaSynopsisService.cancel(jobId);
    }

    /** 撤销本次批量（P5.55）：按旧值快照逐行恢复 */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-撤销批量生成简介", businessType = BusinessType.UPDATE)
    @PostMapping("/synopsis/jobs/{jobId}/rollback")
    public Map<String, Object> synopsisJobRollback(@PathVariable("jobId") String jobId)
    {
        return mediaSynopsisService.rollback(jobId);
    }

    /**
     * 按 ids 清空简介（P5.55 兜底）：body {@code {videoIds:[...], dryRun?}}
     *
     * <p>P5.55 之前的任务没有旧值快照 —— 那种情况下的「撤销」只能是按 id 抹掉生成结果。
     * {@code dryRun} 缺省 true（只统计不写），第一次确认弹窗读它。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-按ids清空简介", businessType = BusinessType.UPDATE)
    @PostMapping("/synopsis/clear")
    public Map<String, Object> synopsisClear(@RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        boolean dryRun = !body.containsKey("dryRun") || booleanValue(body.get("dryRun"));
        return mediaSynopsisService.clearSynopsis(stringsValue(body.get("videoIds"), "videoIds"), dryRun);
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
        return applyTagPatch(id, rawBody);
    }

    /**
     * 标签层级 / 改名 / 改色（{@code PUT /api/media/admin/tags/{id}}，body {@code {parentId?, name?, color?}}）
     *
     * <p>{@code parentId} 出现即改父标签：{@code null} = <b>置顶</b>；自引用、成环、
     * 深度超过 20 层一律 400。与 {@code PATCH /tags/{id}} 共用同一段实现
     * （PATCH 是老调用方，PUT 是按 REST 语义给「改层级」留的入口）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-修改标签层级", businessType = BusinessType.UPDATE)
    @PutMapping("/tags/{id}")
    public Map<String, Object> updateTagTree(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        return applyTagPatch(id, rawBody);
    }

    /** PATCH / PUT 共用的字段解析：{@code containsKey} 即「出现」，null = 清空 / 置顶 */
    private Map<String, Object> applyTagPatch(Long id, String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaOrganizeService.updateTag(id, stringValue(body.get("name")),
                stringValue(body.get("color")), body.containsKey("color"),
                longValue(body.get("parentId")), body.containsKey("parentId"));
    }

    /**
     * 新增单个标记：body {@code {pos, label?, color?, endPos?}}
     *
     * <p>与 {@code PUT /video/{id}/meta} 的 {@code markers} 全量替换互补：本接口只追加一条，
     * pos 必须 {@code 0 <= pos <= dur}（dur 为 NULL 时跳过上限）。
     * {@code endPos} 可选：缺省 / {@code null} = 单点标记；给值时必须 {@code pos < endPos <= dur}
     * （dur 为 NULL 时只校验 {@code > pos}），非法一律 400。返回 {@code {id,pos,endPos,label,color}}，
     * 单点标记的 {@code endPos} 为 {@code null}。</p>
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
        // endPos 不传 = 单点标记；传 null 也当单点（老前端回传整条标记时不会带这个字段）
        marker.setEndPos(optionalDecimalValue(body.get("endPos"), "endPos"));
        marker.setLabel(stringValue(body.get("label")));
        marker.setColor(stringValue(body.get("color")));
        return mediaOrganizeService.addMarker(id, marker);
    }

    // ------------------------------------------------------------------ 人物（P3-A）

    /**
     * 人物分页：{@code GET /api/media/admin/persons?q=&page=&size=}
     *
     * <p>{@code q} 对 name / alias 模糊匹配；返回
     * {@code {rows:[{id,name,alias,note,avatarPath,gender,birthday,useCount,videoCount,albumCount}],total,page,size}}。
     * 与其它列表接口不同，这里用显式 {@code page/size} 而不是 PageHelper：
     * 站端人物页（{@code GET /api/media/person/{id}/videos}）要同一套分页语义。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/persons")
    public Map<String, Object> persons(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size)
    {
        return mediaPersonService.personPage(q, page, size);
    }

    /**
     * 新建人物：{@code POST /api/media/admin/persons}，body {@code {name, alias?, note?, gender?, birthday?}}
     *
     * <p>name 去首尾空格后 1-64 字符；<b>重名 409</b>（不像标签那样静默复用 —— 两个同名人物
     * 会把绑定关系挂到另一个人身上）。birthday 只接受 {@code yyyy-MM-dd}。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-新建人物", businessType = BusinessType.INSERT)
    @PostMapping("/persons")
    public Map<String, Object> createPerson(@RequestBody(required = false) String rawBody)
    {
        return mediaPersonService.createPerson(personPatch(parseObject(rawBody, true)));
    }

    /**
     * 改人物：{@code PUT /api/media/admin/persons/{id}}，body 任选
     * {@code {name?, alias?, note?, gender?, birthday?, avatarPath?}}
     *
     * <p>字段出现即覆盖，显式 {@code null} = 清空该列；改名撞别人 409。返回改完的整行。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-修改人物", businessType = BusinessType.UPDATE)
    @PutMapping("/persons/{id}")
    public Map<String, Object> updatePerson(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        return mediaPersonService.updatePerson(id, personPatch(parseObject(rawBody, true)));
    }

    /**
     * 删人物：{@code DELETE /api/media/admin/persons/{id}}
     *
     * <p>仍被视频 / 相册引用时 <b>400</b>，message 里带 {@code videoCount / albumCount}；
     * 先解绑（{@code PUT /videos/{id}/persons} / {@code PUT /albums/{id}/persons}）再删。
     * 不做级联删除：一次误删会静默抹掉几百条绑定。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-删除人物", businessType = BusinessType.DELETE)
    @DeleteMapping("/persons/{id}")
    public Map<String, Object> deletePerson(@PathVariable("id") Long id)
    {
        return mediaPersonService.deletePerson(id);
    }

    /**
     * 设人物头像：{@code POST /api/media/admin/persons/{id}/avatar}，body {@code {path}}
     *
     * <p>只存路径，不落二进制：上传<b>沿用项目既有的 {@code POST /common/upload}</b>
     * （若依通用上传，需登录），把返回的 {@code url} 原样作为 {@code path} 回传即可；
     * {@code path} 只接受 http(s) URL 或站内绝对路径（以 {@code /} 开头），
     * 空串 / {@code null} = 清空头像。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-人物头像", businessType = BusinessType.UPDATE)
    @PostMapping("/persons/{id}/avatar")
    public Map<String, Object> updatePersonAvatar(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaPersonService.updateAvatar(id, stringValue(body.get("path")));
    }

    /**
     * 管理端设人物手动头像（P5.66）：{@code PUT /api/media/admin/persons/{id}/avatar}，body {@code {kind, ref}}
     *
     * <p>三个 tab 落库的形状与站端一致：{@code kind = "photo"} 时 {@code ref = "<albumId>:<seq>"}、
     * {@code kind = "frame"} 时 {@code ref = "frame:<videoId>:<sec>"}、{@code kind = "url"} 时 {@code ref} 是
     * {@code http(s)://} / {@code data:image/…}；三者都可带 {@code #crop=x,y,w,h}（比例）裁剪尾巴。
     * 回包是整条 personNode（含后端解析好的 {@code avatarKind / avatarRef / avatarUrl}）。</p>
     *
     * <p><b>与站端那条的关系</b>：业务实现完全相同 —— 直接复用
     * {@link com.qtools.media.service.IMediaPersonService#setAvatar(Long, String, String)}，
     * 一行逻辑都不复制。区别只有入口和闸门：</p>
     * <ul>
     *   <li>站端 {@code PUT /api/media/person/{id}/avatar}（MediaEditController）：{@code @Anonymous} +
     *       {@code requireWritable()} 站端编辑策略闸门，为「访客在 full 档位下也能改」而开；</li>
     *   <li>本接口：管理端权限 {@code media:admin:edit}，<b>不过</b>站端编辑策略闸门 ——
     *       管理员本来就该能写，站端 off 档（禁止访客编辑）不该把管理员一起挡住。</li>
     * </ul>
     *
     * <p>管理端前端（QTools-M）只用本接口，不再借用站端匿名那条。</p>
     *
     * <p>错误码：kind 非法 / ref 空 / ref 不成形 / 图片不在该人物名下 / 视频不存在或秒越界 → <b>400</b>；
     * 人物不存在 → <b>404</b>；无 {@code media:admin:edit} 权限 → <b>403</b>。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-人物手动头像", businessType = BusinessType.UPDATE)
    @PutMapping("/persons/{id}/avatar")
    public Map<String, Object> setPersonAvatarRef(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        return mediaPersonService.setAvatar(id, stringValue(body.get("kind")), stringValue(body.get("ref")));
    }

    /**
     * 管理端清除人物手动头像（P5.66）：{@code DELETE /api/media/admin/persons/{id}/avatar} → 回三级自动
     *
     * <p>清的是 {@code avatar_kind / avatar_ref}（手动那层），历史 {@code avatar_path} 不动；
     * 实现复用 {@link com.qtools.media.service.IMediaPersonService#clearAvatar(Long)}。
     * 人物不存在 → 404；无 {@code media:admin:edit} 权限 → 403。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-人物手动头像", businessType = BusinessType.UPDATE)
    @DeleteMapping("/persons/{id}/avatar")
    public Map<String, Object> clearPersonAvatarRef(@PathVariable("id") Long id)
    {
        return mediaPersonService.clearAvatar(id);
    }

    /**
     * 全量替换某视频的人物与角色：{@code PUT /api/media/admin/videos/{id}/persons}
     *
     * <p>body {@code {personIds:[1,2], roles:{1:主演}}}：{@code personIds} 出现即整体替换
     * （{@code []} = 解除全部绑定，P5.3-A 起会落成「素人」而不是空 —— 见
     * {@link com.qtools.media.service.IMediaPersonService#replaceVideoPersons(String, java.util.List, java.util.Map)}），
     * {@code roles} 可缺省（键必须属于本次 personIds，值 &lt;= 32 字符）。
     * 只清 {@code person_id IS NOT NULL} 的绑定行，扫描脚本写的 name-only 历史行不受影响。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-视频人物", businessType = BusinessType.UPDATE)
    @PutMapping("/videos/{id}/persons")
    public Map<String, Object> replaceVideoPersons(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        List<Long> personIds = longsValue(body.get("personIds"), "personIds");
        if (personIds == null)
        {
            throw MediaException.badRequest("personIds 必填（[] = 解除全部绑定）");
        }
        return mediaPersonService.replaceVideoPersons(id, personIds, rolesValue(body.get("roles"), "roles"));
    }

    /**
     * 全量替换某相册的人物：{@code PUT /api/media/admin/albums/{id}/persons}
     *
     * <p>body {@code {personIds:[1,2]}}：{@code []} = 未归类。写入 {@code media_album_person}
     * 同时把 {@code media_album.person_id}（单选镜像列）同步成第一个人物。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-相册人物", businessType = BusinessType.UPDATE)
    @PutMapping("/albums/{id}/persons")
    public Map<String, Object> replaceAlbumPersons(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        List<Long> personIds = longsValue(body.get("personIds"), "personIds");
        if (personIds == null)
        {
            throw MediaException.badRequest("personIds 必填（[] = 未归类）");
        }
        return mediaPersonService.replaceAlbumPersons(id, personIds);
    }

    // ------------------------------------------------------------------ 工作室（P5-A）

    /**
     * 工作室清单：{@code GET /api/media/admin/studios}
     *
     * <p>返回 {@code [{id,name,alias,note,avatarPath,useCount,sort,hidden,videoCount}]}，按 sort / name 升序。
     * 与标签字典不同，这里<b>带 hidden 行</b>：被隐藏的工作室仍要让管理端能改回来
     * （站端清单 {@code GET /api/media/studios} 只出未隐藏的）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/studios")
    public List<Map<String, Object>> studios()
    {
        return mediaStudioService.studioList();
    }

    /**
     * 新建工作室：{@code POST /api/media/admin/studios}，body {@code {name, alias?, note?, avatarPath?, sort?, hidden?}}
     *
     * <p>name 去首尾空格后 1-64 字符且<b>唯一，重名 409</b>（不像标签那样静默复用：
     * 复用会把「我以为新建了一个厂牌」变成「悄悄绑到另一个同名厂牌上」）。
     * avatarPath 只接受 http(s) URL 或站内绝对路径（以 {@code /} 开头）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-新建工作室", businessType = BusinessType.INSERT)
    @PostMapping("/studios")
    public Map<String, Object> createStudio(@RequestBody(required = false) String rawBody)
    {
        return mediaStudioService.createStudio(studioPatch(parseObject(rawBody, true)));
    }

    /**
     * 改工作室：{@code PUT /api/media/admin/studios/{id}}，body 任选
     * {@code {name?, alias?, note?, avatarPath?, sort?, hidden?}}
     *
     * <p>字段出现即覆盖，显式 {@code null} 清空该列（name 除外：name 出现必须是合法名字）；
     * 改名撞别的行 409。返回改完的整行。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-修改工作室", businessType = BusinessType.UPDATE)
    @PutMapping("/studios/{id}")
    public Map<String, Object> updateStudio(@PathVariable("id") Long id,
            @RequestBody(required = false) String rawBody)
    {
        return mediaStudioService.updateStudio(id, studioPatch(parseObject(rawBody, true)));
    }

    /**
     * 删工作室：{@code DELETE /api/media/admin/studios/{id}}
     *
     * <p>仍被视频引用时 <b>400</b>，message 里带引用计数；先解绑
     * （{@code PUT /videos/{id}/studios}）再删。不做级联删除。</p>
     */
    /**
     * 预热全部人物卡背景：{@code POST /api/media/admin/person/card-bg/warm}
     *
     * <p>P5.92：人物页首屏那 13 张图（低清占位 + 正式各一张）在服务端是内容寻址缓存 ——
     * 命中 3–20ms，冷渲染 60–200ms 且并发时互相抢 CPU。索引重建（60s 一次）与保存背景参数后
     * 会自动排后台预热；这个接口是手动挡：部署完 / 清过缓存 / 想确认真人访问一定 HIT 时用。</p>
     *
     * <p>参数可全省：省了就用每个人「上一次真实访问的那组参数」（尺寸 / 模糊 / 主题 / 卡片底色，
     * 服务端猜不出视口宽度，所以只能跟着真实访问学）。传了就按传的对所有人物渲一遍。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-预热卡片背景", businessType = BusinessType.OTHER)
    @PostMapping("/person/card-bg/warm")
    public Map<String, Object> warmAllCardBg(@RequestParam(value = "w", required = false) Integer w,
            @RequestParam(value = "h", required = false) Integer h,
            @RequestParam(value = "blur", required = false) Integer blur,
            @RequestParam(value = "d", required = false) String d,
            @RequestParam(value = "bg", required = false) String bg)
    {
        boolean dark = "1".equals(d) || "true".equalsIgnoreCase(d);
        List<Long> ids = personIds();
        int rendered = 0;
        int skipped = 0;
        int failed = 0;
        for (Long id : ids)
        {
            try
            {
                if (mediaPersonCardBgService.warmOne(id, w, h, null, null, null, blur, dark, bg).isHit())
                {
                    skipped++;
                }
                else
                {
                    rendered++;
                }
            }
            catch (RuntimeException e)
            {
                failed++;
                log.debug("手动预热卡片背景失败 person={} err={}", id, e.getMessage());
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("persons", ids.size());
        out.put("rendered", rendered);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("stats", mediaPersonCardBgService.warmStats());
        return out;
    }

    /**
     * 预热单个：{@code POST /api/media/admin/person/{id}/card-bg/warm}
     *
     * @return {@code {id, hit, key, ms, width, height}}（hit=true = 本来就有，没白渲染）
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/person/{id}/card-bg/warm")
    public Map<String, Object> warmOneCardBg(@PathVariable("id") Long id,
            @RequestParam(value = "w", required = false) Integer w,
            @RequestParam(value = "h", required = false) Integer h,
            @RequestParam(value = "blur", required = false) Integer blur,
            @RequestParam(value = "d", required = false) String d,
            @RequestParam(value = "bg", required = false) String bg)
    {
        boolean dark = "1".equals(d) || "true".equalsIgnoreCase(d);
        IMediaPersonCardBgService.CardBgImage image = mediaPersonCardBgService.warmOne(id, w, h, null, null, null,
                blur, dark, bg);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", id);
        out.put("hit", image.isHit());
        out.put("key", image.getKey());
        out.put("ms", image.getRenderMs());
        out.put("width", image.getWidth());
        out.put("height", image.getHeight());
        return out;
    }

    /** 预热状态：{@code GET /api/media/admin/person/card-bg/warm/stats} */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/person/card-bg/warm/stats")
    public Map<String, Object> warmCardBgStats()
    {
        return mediaPersonCardBgService.warmStats();
    }

    /** 全部人物 id（管理端预热用；不排序、不筛可见性 —— 预热与「谁能看见」无关） */
    private List<Long> personIds()
    {
        List<Long> ids = new java.util.ArrayList<>();
        for (Map<String, Object> node : mediaPersonService.personBriefList())
        {
            Object id = node == null ? null : node.get("id");
            if (id instanceof Number)
            {
                ids.add(((Number) id).longValue());
            }
            else if (id != null)
            {
                try
                {
                    ids.add(Long.valueOf(String.valueOf(id)));
                }
                catch (NumberFormatException ignored)
                {
                    // 非数字 id 不参与预热
                }
            }
        }
        return ids;
    }

    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-删除工作室", businessType = BusinessType.DELETE)
    @DeleteMapping("/studios/{id}")
    public Map<String, Object> deleteStudio(@PathVariable("id") Long id)
    {
        return mediaStudioService.deleteStudio(id);
    }

    /**
     * 全量替换某视频的工作室：{@code PUT /api/media/admin/videos/{id}/studios}
     *
     * <p>body {@code {studioIds:[1,2]}}：{@code studioIds} 必填，出现即整体替换
     * （{@code []} = 解除全部绑定），一次最多 50 个，未知 studioId 400。
     * 返回 {@code {updated,id,studios:[{id,name,alias}]}}。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-视频工作室", businessType = BusinessType.UPDATE)
    @PutMapping("/videos/{id}/studios")
    public Map<String, Object> replaceVideoStudios(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody)
    {
        Map<String, Object> body = parseObject(rawBody, true);
        List<Long> studioIds = longsValue(body.get("studioIds"), "studioIds");
        if (studioIds == null)
        {
            throw MediaException.badRequest("studioIds 必填（[] = 解除全部绑定）");
        }
        return mediaStudioService.replaceVideoStudios(id, studioIds);
    }
}

