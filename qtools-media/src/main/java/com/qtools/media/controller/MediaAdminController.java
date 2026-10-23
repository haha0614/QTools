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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.common.annotation.Log;
import com.qtools.common.core.controller.BaseController;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.common.core.page.TableDataInfo;
import com.qtools.common.enums.BusinessType;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaAdminService;
import com.qtools.media.service.IMediaScanService;

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

    /** 只用于解析请求体，不参与响应序列化 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private IMediaAdminService mediaAdminService;

    @Autowired
    private IMediaScanService mediaScanService;

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

    // ------------------------------------------------------------------ 工具

    /** 手工解析 body：空 body 允许；非法 JSON / 非对象一律 400（而不是落到 500） */
    private static Map<String, Object> parseObject(String rawBody, boolean required)
    {
        if (StringUtils.isEmpty(rawBody) || rawBody.trim().isEmpty())
        {
            if (required)
            {
                throw MediaException.badRequest("missing request body");
            }
            return Map.of();
        }
        try
        {
            Map<String, Object> parsed = MAPPER.readValue(rawBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>()
            {
            });
            return parsed == null ? Map.of() : parsed;
        }
        catch (Exception e)
        {
            throw MediaException.badRequest("invalid json body");
        }
    }

    /** confirm：接受 true / "true" / 1 / "1"，其余（含缺省）一律算未确认 */
    private static boolean booleanValue(Object value)
    {
        if (value instanceof Boolean)
        {
            return ((Boolean) value).booleanValue();
        }
        String text = stringValue(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    /** limit：接受数字或数字字符串；非法字符串 400（而不是落到 500） */
    private static Integer integerValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number)
        {
            return Integer.valueOf(((Number) value).intValue());
        }
        String text = stringValue(value);
        if (text == null)
        {
            return null;
        }
        try
        {
            return Integer.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest("limit 必须是整数");
        }
    }

    private static String stringValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equals(text))
        {
            return null;
        }
        return text;
    }

    private static Boolean boolValue(Object value)
    {
        if (value instanceof Boolean flag)
        {
            return flag;
        }
        if (value instanceof Number number)
        {
            return number.intValue() != 0;
        }
        String text = stringValue(value);
        if (text == null)
        {
            return null;
        }
        String lower = text.toLowerCase();
        if ("true".equals(lower) || "1".equals(lower))
        {
            return Boolean.TRUE;
        }
        if ("false".equals(lower) || "0".equals(lower))
        {
            return Boolean.FALSE;
        }
        throw MediaException.badRequest("hidden 只能是 true / false");
    }

    private static Integer intValue(Object value)
    {
        Long parsed = longValue(value);
        return parsed == null ? null : parsed.intValue();
    }

    private static Long longValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number number)
        {
            return number.longValue();
        }
        String text = stringValue(value);
        if (text == null || "null".equalsIgnoreCase(text))
        {
            return null;
        }
        try
        {
            return Long.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest("数值参数格式错误: " + text);
        }
    }
}
