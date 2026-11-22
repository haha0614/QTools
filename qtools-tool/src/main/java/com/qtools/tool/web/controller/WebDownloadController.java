package com.qtools.tool.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.tool.mapper.ToolWebPostMapper;

/**
 * 「保存到本地 / 保存到媒体站」与下载记录（P6.115）
 *
 * <p>服务端本来就有一份下载好的 mp4（{@code data/media/web/<源站id>/video.mp4}），
 * 「保存到本地」直接把它以附件形式吐给浏览器，不重复下载一遍；
 * 还没下载过的先入队准备，准备完前端再触发浏览器下载。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/tool/web")
public class WebDownloadController
{
    private static final Logger log = LoggerFactory.getLogger(WebDownloadController.class);

    /** 服务端运行根，启动脚本以 -Dqtools.home 传入（缺省为便携布局） */
    private static final String HOME = System.getProperty("qtools.home", "E:/111/project/QTools");

    private static final String WEBROOT = HOME + "/data/media/web";

    /** 下载 sidecar（ffmpeg 拉 HLS 的活儿都交给它） */
    private static final String SIDECAR = "http://127.0.0.1:8765";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private ToolWebPostMapper toolWebPostMapper;

    @Autowired
    private com.qtools.tool.web.service.WebMediaImportService webMediaImportService;

    /** 用户端 token 校验（qtools-common 接口，实现在 qtools-app）；用来判断是不是站长 */
    @Autowired(required = false)
    private com.qtools.common.security.IAppTokenVerifier appTokenVerifier;

    /**
     * 当前身份能力：{@code GET /tool/web/capabilities}
     *
     * <p>决定「下载」弹不弹选择框：站长（owner）才能选「保存到媒体站」，
     * 普通用户直接走浏览器下载，避免普通用户往媒体库里塞内容。</p>
     */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(@RequestHeader(value = "Authorization", required = false) String authorization)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean owner = false;
        if (authorization != null && authorization.startsWith("Bearer ") && appTokenVerifier != null)
        {
            try
            {
                Long uid = appTokenVerifier.verifyUserId(authorization.substring(7).trim());
                if (uid != null)
                {
                    com.qtools.common.security.AppUserProfile profile = appTokenVerifier.profileOf(uid);
                    owner = profile != null && profile.isOwner();
                }
            }
            catch (RuntimeException e)
            {
                owner = false;
            }
        }
        out.put("owner", owner);
        out.put("loggedIn", ownerUserId(authorization) != null);
        out.put("canSaveToMedia", owner);
        return out;
    }

    /** 有效 token 的 userId；没登录或 token 失效返回 null */
    private Long ownerUserId(String authorization)
    {
        if (authorization == null || !authorization.startsWith("Bearer ") || appTokenVerifier == null)
        {
            return null;
        }
        try
        {
            return appTokenVerifier.verifyUserId(authorization.substring(7).trim());
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** 人物候选：{@code GET /tool/web/persons?q=xx}（站长保存时选人物用） */
    @GetMapping("/persons")
    public Map<String, Object> persons(@RequestParam(value = "q", required = false) String q)
    {
        StringBuilder sql = new StringBuilder("select id, name, use_count from media_person");
        if (q != null && !q.trim().isEmpty())
        {
            sql.append(" where name like '%").append(q.trim().replace("'", "''")).append("%'");
        }
        sql.append(" order by use_count desc, id desc limit 30");
        java.util.List<Map<String, Object>> rows = new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList(sql.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        return out;
    }

    /** 工作室候选：{@code GET /tool/web/studios?q=xx} */
    @GetMapping("/studios")
    public Map<String, Object> studios(@RequestParam(value = "q", required = false) String q)
    {
        StringBuilder sql = new StringBuilder("select id, name from media_studio");
        if (q != null && !q.trim().isEmpty())
        {
            sql.append(" where name like '%").append(q.trim().replace("'", "''")).append("%'");
        }
        sql.append(" order by id desc limit 30");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList(sql.toString()));
        return out;
    }

    /** 新建相册：{@code POST /tool/web/album} body {title, person}（仅站长） */
    @PostMapping("/album")
    public Map<String, Object> createAlbum(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> caps = capabilities(auth);
        if (!Boolean.TRUE.equals(caps.get("owner")))
        {
            out.put("ok", false);
            out.put("message", "只有站长可以新增相册");
            return out;
        }
        String title = body.get("title") == null ? "" : String.valueOf(body.get("title")).trim();
        String person = body.get("person") == null ? "" : String.valueOf(body.get("person")).trim();
        if (title.isEmpty() || title.length() > 200)
        {
            out.put("ok", false);
            out.put("message", "相册名不合法");
            return out;
        }
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        java.util.List<Map<String, Object>> exist = jdbc.queryForList(
                "select id, title from media_album where title = ? limit 1", title);
        if (!exist.isEmpty())
        {
            out.put("ok", true);
            out.put("exist", true);
            out.put("album", exist.get(0));
            return out;
        }
        String dir = "每日大赛/" + title;
        Long max = jdbc.query("select max(cast(substring(id,2) as unsigned)) from media_album where id like 'a%'",
                rs -> rs.next() ? rs.getLong(1) : 1L);
        String id = String.format("a%03d", (max == null ? 1L : max) + 1);
        Long pid = null;
        if (!person.isEmpty())
        {
            java.util.List<Long> p = jdbc.queryForList("select id from media_person where name = ? limit 1",
                    Long.class, person);
            pid = p.isEmpty() ? null : p.get(0);
        }
        jdbc.update("insert into media_album (id, title, dir, person_id, photo_count, bytes, content_rating, review_state, hidden, confirmed, sort, mtime, dir_key, identity_key, source) "
                + "values (?, ?, ?, ?, 0, 0, 'general', 'pending', 0, 1, 0, now(), ?, '*', 'prefix')",
                id, title, dir, pid, dir);
        jdbc.update("insert into media_album_dir (album_id, root_id, dir) values (?, 1, ?)", id, dir);
        out.put("ok", true);
        out.put("exist", false);
        out.put("album", Map.of("id", id, "title", title));
        return out;
    }

    /** 新建工作室：{@code POST /tool/web/studio} body {name}（仅站长） */
    @PostMapping("/studio")
    public Map<String, Object> createStudio(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> caps = capabilities(auth);
        if (!Boolean.TRUE.equals(caps.get("owner")))
        {
            out.put("ok", false);
            out.put("message", "只有站长可以新增工作室");
            return out;
        }
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        if (name.isEmpty() || name.length() > 120)
        {
            out.put("ok", false);
            out.put("message", "工作室名不合法");
            return out;
        }
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        java.util.List<Map<String, Object>> exist = jdbc.queryForList(
                "select id, name from media_studio where name = ? limit 1", name);
        if (!exist.isEmpty())
        {
            out.put("ok", true);
            out.put("exist", true);
            out.put("studio", exist.get(0));
            return out;
        }
        jdbc.update("insert into media_studio (name) values (?)", name);
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        out.put("ok", true);
        out.put("exist", false);
        out.put("studio", Map.of("id", id, "name", name));
        return out;
    }

    /** 相册候选：{@code GET /tool/web/albums?q=xx}（保存图片时选册用） */
    @GetMapping("/albums")
    public Map<String, Object> albums(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "person", required = false) String person)
    {
        StringBuilder sql = new StringBuilder("select id, title, dir from media_album where hidden = 0");
        if (q != null && !q.trim().isEmpty())
        {
            sql.append(" and title like '%").append(q.trim().replace("'", "''")).append("%'");
        }
        // 选了人物就只给他名下的相册（避免一堆别人物的册把列表刷满）
        if (person != null && !person.trim().isEmpty())
        {
            sql.append(" and person_id in (select id from media_person where name = '")
                    .append(person.trim().replace("'", "''")).append("')");
        }
        sql.append(" order by mtime desc limit 30");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList(sql.toString()));
        return out;
    }

    /** 新建人物：{@code POST /tool/web/person} body {name}（仅站长） */
    @PostMapping("/person")
    public Map<String, Object> createPerson(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> caps = capabilities(authorization);
        if (!Boolean.TRUE.equals(caps.get("owner")))
        {
            out.put("ok", false);
            out.put("message", "只有站长可以新增人物");
            return out;
        }
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        if (name.isEmpty() || name.length() > 120)
        {
            out.put("ok", false);
            out.put("message", "人物名不合法");
            return out;
        }
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        java.util.List<Map<String, Object>> exist = jdbc.queryForList(
                "select id, name from media_person where name = ? limit 1", name);
        if (!exist.isEmpty())
        {
            out.put("ok", true);
            out.put("exist", true);
            out.put("person", exist.get(0));
            return out;
        }
        // 与管理端新增人物对齐：允许填别名/性别/简介，而不是只存一个名字
        String alias = body.get("alias") == null ? null : String.valueOf(body.get("alias")).trim();
        String gender = body.get("gender") == null ? null : String.valueOf(body.get("gender")).trim();
        String note = body.get("note") == null ? null : String.valueOf(body.get("note")).trim();
        jdbc.update("insert into media_person (name, alias, gender, note) values (?, ?, ?, ?)", name,
                alias == null || alias.isEmpty() ? null : alias,
                gender == null || gender.isEmpty() ? null : gender,
                note == null || note.isEmpty() ? null : note);
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        out.put("ok", true);
        out.put("exist", false);
        out.put("person", Map.of("id", id, "name", name));
        return out;
    }

    /** 删除下载记录（清理测试/脏数据用）：{@code DELETE /tool/web/download-record/{id}} */
    @org.springframework.web.bind.annotation.DeleteMapping("/download-record/{id}")
    public Map<String, Object> deleteRecord(@org.springframework.web.bind.annotation.PathVariable("id") Long id)
    {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("delete from tool_web_download where id = ?", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    /** 记录一条下载：{@code POST /tool/web/download-record}  body: {postId, mode, params} */
    @PostMapping("/download-record")
    public Map<String, Object> createRecord(@RequestBody Map<String, Object> body)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Long postId = body.get("postId") == null ? null : Long.valueOf(String.valueOf(body.get("postId")));
        String mode = body.get("mode") == null ? "local" : String.valueOf(body.get("mode"));
        String params = body.get("params") == null ? null
                : String.valueOf(body.get("params"));
        if (postId == null)
        {
            out.put("ok", false);
            out.put("error", "缺少 postId");
            return out;
        }
        String kind = body.get("kind") == null ? ("media".equals(mode) ? "video" : "local") : String.valueOf(body.get("kind"));
        toolWebPostMapper.insertDownload(postId, mode, "queued", params, kind);
        long id = toolWebPostMapper.selectLastInsertId();
        out.put("ok", true);
        out.put("id", id);
        out.put("record", toolWebPostMapper.selectDownload(id));
        return out;
    }

    /** 更新进度 / 结果：{@code POST /tool/web/download-record/{id}} */
    @PostMapping("/download-record-update")
    public Map<String, Object> updateRecord(@RequestBody Map<String, Object> body)
    {
        Long id = body.get("id") == null ? null : Long.valueOf(String.valueOf(body.get("id")));
        Map<String, Object> out = new LinkedHashMap<>();
        if (id == null)
        {
            out.put("ok", false);
            return out;
        }
        String state = body.get("state") == null ? "done" : String.valueOf(body.get("state"));
        int percent = body.get("percent") == null ? 100 : Integer.parseInt(String.valueOf(body.get("percent")));
        Long size = body.get("sizeBytes") == null ? null : Long.valueOf(String.valueOf(body.get("sizeBytes")));
        String error = body.get("error") == null ? null : String.valueOf(body.get("error"));
        toolWebPostMapper.updateDownload(id, state, percent, size, error);
        out.put("ok", true);
        out.put("record", toolWebPostMapper.selectDownload(id));
        return out;
    }

    /**
     * 保存到媒体站：{@code POST /tool/web/save-media}
     * body: {postId, recordId, params:{rating, person, album}}
     *
     * <p>服务端把文件搬进媒体库根目录 → 调媒体站扫描入库 → 扫完回填分级与人物；
     * 同时更新那条下载记录，前端「下载记录」里能看到进度。</p>
     */
    @PostMapping("/save-media")
    public Map<String, Object> saveMedia(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Long postId = body.get("postId") == null ? null : Long.valueOf(String.valueOf(body.get("postId")));
        Long recordId = body.get("recordId") == null ? null : Long.valueOf(String.valueOf(body.get("recordId")));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map
                ? (Map<String, Object>) body.get("params") : Map.of();
        if (postId == null)
        {
            out.put("ok", false);
            out.put("message", "缺少 postId");
            return out;
        }
        // 未登录不允许保存任何内容（前端也会隐藏入口，这里是不依赖前端的兜底）
        if (ownerUserId(authorization) == null)
        {
            out.put("ok", false);
            out.put("message", "请先登录后再保存");
            return out;
        }
        String kind = body.get("kind") == null ? "video" : String.valueOf(body.get("kind"));
        // 媒体库必须唯一：同一条内容的同一类东西只入一次（本地下载不做这个校验）
        Integer done = new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                "select count(*) from tool_web_download where post_id = ? and kind = ? and mode = 'media' and state = 'done'",
                Integer.class, postId, kind);
        // 多段视频例外：早先只入了第 1 段，用户重新保存时应该把缺的段补进去，
        // 不能一律当成"重复"挡掉（否则媒体站永远只有 1 个视频）
        boolean multiSegment = false;
        try
        {
            Integer vc = new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                    "select ifnull(video_count,0) from tool_web_post where id = ?", Integer.class, postId);
            multiSegment = vc != null && vc > 1;
        }
        catch (Exception ignored)
        {
            // 取不到就按普通规则处理
        }
        if (done != null && done > 0 && !multiSegment)
        {
            out.put("ok", false);
            out.put("duplicate", true);
            out.put("message", "这条已经保存到媒体库了，不重复入库");
            return out;
        }
        Map<String, Object> post = toolWebPostMapper.selectOne(postId);
        if (post == null)
        {
            out.put("ok", false);
            out.put("message", "条目不存在");
            return out;
        }
        // 人物：新格式是数组 [{name, role}]；兼容旧格式的单个字符串
        java.util.List<String[]> persons = new java.util.ArrayList<>();
        Object rawPersons = params.get("persons");
        if (rawPersons instanceof java.util.List<?> list)
        {
            for (Object o : list)
            {
                if (o instanceof Map<?, ?> pm)
                {
                    Object n = pm.get("name");
                    Object r = pm.get("role");
                    if (n != null && !String.valueOf(n).isBlank())
                    {
                        persons.add(new String[] { String.valueOf(n), r == null ? "" : String.valueOf(r) });
                    }
                }
            }
        }
        else if (params.get("person") != null && !String.valueOf(params.get("person")).isBlank())
        {
            persons.add(new String[] { String.valueOf(params.get("person")), "" });
        }
        if (persons.isEmpty())
        {
            out.put("ok", false);
            out.put("message", "人物是必填项（默认「素人」）");
            return out;
        }
        Map<String, Object> res = webMediaImportService.importPost(postId, recordId,
                String.valueOf(post.get("sourceId")), String.valueOf(post.get("title")),
                params.get("rating") == null ? null : String.valueOf(params.get("rating")),
                persons,
                params.get("album") == null ? null : String.valueOf(params.get("album")),
                params.get("studio") == null ? null : String.valueOf(params.get("studio")),
                kind,
                post.get("tags") == null ? null : String.valueOf(post.get("tags")),
                body.get("imageFile") == null ? null : String.valueOf(body.get("imageFile")));
        if (recordId != null)
        {
            // 扫描是异步的：这里只落到 importing，真正结束由后台线程改成 done/error
            boolean ok = Boolean.TRUE.equals(res.get("ok"));
            toolWebPostMapper.updateDownload(recordId, ok ? "importing" : "error", ok ? 60 : 0, null,
                    ok ? null : String.valueOf(res.get("message")));
            res.put("record", toolWebPostMapper.selectDownload(recordId));
        }
        out.putAll(res);
        return out;
    }

    /** 下载记录列表：{@code GET /tool/web/download-records?limit=20} */
    @GetMapping("/download-records")
    public Map<String, Object> records(@RequestParam(value = "limit", required = false, defaultValue = "20") int limit)
    {
        List<Map<String, Object>> rows = toolWebPostMapper.selectDownloads(Math.min(Math.max(limit, 1), 100));
        attachSegmentProgress(rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        return out;
    }

    /** 单条记录（前端轮询准备进度用）：{@code GET /tool/web/download-record/1} */
    @GetMapping("/download-record")
    public Map<String, Object> record(@RequestParam("id") Long id)
    {
        Map<String, Object> row = toolWebPostMapper.selectDownload(id);
        if (row != null)
        {
            attachSegmentProgress(java.util.List.of(row));
        }
        return row;
    }

    /**
     * 把后台补段进度并进导入记录（P6.116 补段可见）
     *
     * <p>多段帖子的补段在后台上线程里一段段等 sidecar 下载（一整条 8~10 分钟），
     * 进度只存在 {@code WebMediaImportService} 的内存里（不落库 —— 它活到这段跑完就没意义了）。
     * 这里合并成 {@code note}（"补段 3/12"）与推进过的 {@code percent}，前端「下载记录」直接显示。</p>
     */
    private void attachSegmentProgress(List<Map<String, Object>> rows)
    {
        if (rows == null)
        {
            return;
        }
        for (Map<String, Object> row : rows)
        {
            if (row == null || row.get("postId") == null)
            {
                continue;
            }
            Long postId = Long.valueOf(String.valueOf(row.get("postId")));
            Map<String, Object> p = webMediaImportService.segmentProgress(postId);
            if (p == null)
            {
                continue;
            }
            int seg = p.get("seg") instanceof Number n ? n.intValue() : 0;
            int count = p.get("count") instanceof Number n ? n.intValue() : 0;
            String phase = String.valueOf(p.get("phase"));
            String note = switch (phase)
            {
                case "done" -> "补段完成 " + count + "/" + count;
                case "timeout" -> "补段超时 " + seg + "/" + count;
                case "failed" -> "补段失败 " + seg + "/" + count;
                case "queued" -> "排队等扫描";
                default -> "补段 " + seg + "/" + count;
            };
            row.put("note", note);
            row.put("seg", seg);
            row.put("segTotal", count);
            row.put("segPhase", phase);
            if ("importing".equals(String.valueOf(row.get("state"))) && count > 0)
            {
                // 补段占 60%→95%，收尾的「扫库回填」留给 awaitAndTag
                row.put("percent", Math.min(95, 60 + (int) Math.round(35.0 * seg / count)));
            }
        }
    }

    /**
     * 图片打包下载：{@code GET /tool/web/images/zip/{postId}}
     *
     * <p>把该条目在服务端的图集打成一个 zip 返回；只读磁盘，不落临时文件。</p>
     */
    @GetMapping("/images/zip/{postId}")
    public void zipImages(@org.springframework.web.bind.annotation.PathVariable("postId") Long postId,
            jakarta.servlet.http.HttpServletResponse response) throws IOException
    {
        Map<String, Object> row = toolWebPostMapper.selectOne(postId);
        if (row == null)
        {
            response.setStatus(404);
            return;
        }
        Path dir = Paths.get(WEBROOT, String.valueOf(row.get("sourceId")));
        if (!Files.isDirectory(dir))
        {
            response.setStatus(404);
            return;
        }
        java.util.List<Path> pics;
        try (var stream = Files.list(dir))
        {
            pics = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jpg"))
                    .sorted()
                    .toList();
        }
        if (pics.isEmpty())
        {
            response.setStatus(404);
            return;
        }
        String name = "mrds-" + row.get("sourceId") + "-images.zip";
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream()))
        {
            for (Path p : pics)
            {
                zos.putNextEntry(new java.util.zip.ZipEntry(p.getFileName().toString()));
                Files.copy(p, zos);
                zos.closeEntry();
            }
        }
    }

    /**
     * 「保存到本地」真正下载：{@code GET /tool/web/local/{postId}}
     *
     * <p>把服务端已下载好的 mp4 以附件形式返回；文件不存在则 404，
     * 由前端先走准备流程（入队下载）再来取。</p>
     */
    /**
     * 批量下载：{@code GET /tool/web/local-zip/{postId}}
     *
     * <p>多段视频逐段 ffmpeg 落盘（video.mp4 / v2.mp4 / v3.mp4…），打成 zip 一次性返回。
     * 单视频也用这个接口（zip 里就一个文件），前端不必区分。</p>
     */
    @GetMapping("/local-zip/{postId}")
    public void localZip(@org.springframework.web.bind.annotation.PathVariable("postId") Long postId,
            jakarta.servlet.http.HttpServletResponse response) throws IOException
    {
        Map<String, Object> row = toolWebPostMapper.selectOne(postId);
        if (row == null)
        {
            response.sendError(404);
            return;
        }
        String sid = String.valueOf(row.get("sourceId"));
        int count = row.get("videoCount") == null ? 1 : Math.max(1, Integer.parseInt(String.valueOf(row.get("videoCount"))));
        Path dir = Paths.get(WEBROOT, sid);
        List<Path> files = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 180_000L;
        for (int i = 0; i < count; i++)
        {
            Path f = dir.resolve(i == 0 ? "video.mp4" : ("v" + (i + 1) + ".mp4"));
            if (!Files.exists(f))
            {
                // 请 sidecar 用 ffmpeg 拉这一段，然后等它出现
                triggerSidecarDownload(postId, i);
                long until = Math.min(deadline, System.currentTimeMillis() + 90_000L);
                while (!Files.exists(f) && System.currentTimeMillis() < until)
                {
                    try
                    {
                        Thread.sleep(1500);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (Files.exists(f))
            {
                files.add(f);
            }
        }
        if (files.isEmpty())
        {
            response.sendError(404);
            return;
        }
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"mrds-" + sid + "-videos.zip\"");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream()))
        {
            int seq = 1;
            for (Path f : files)
            {
                zos.putNextEntry(new java.util.zip.ZipEntry(
                        String.format("%s-%02d.mp4", sid, seq++)));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
    }

    /** 通知 sidecar 用 ffmpeg 下载第 index 段（异步，失败只记日志） */
    private void triggerSidecarDownload(Long postId, int index)
    {
        try
        {
            HttpRequest req = HttpRequest.newBuilder(URI.create(SIDECAR + "/download"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":" + postId + ",\"index\":" + index + "}"))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        }
        catch (Exception e)
        {
            log.warn("触发分段下载失败 postId={} index={} err={}", postId, index, e.toString());
        }
    }

    @GetMapping("/local/{postId}")
    public ResponseEntity<Resource> local(@org.springframework.web.bind.annotation.PathVariable("postId") Long postId)
    {
        Map<String, Object> row = toolWebPostMapper.selectOne(postId);
        if (row == null)
        {
            return ResponseEntity.notFound().build();
        }
        String file = row.get("videoFile") == null ? null : String.valueOf(row.get("videoFile"));
        if (file == null || file.isEmpty())
        {
            return ResponseEntity.notFound().build();
        }
        // videoFile 形如 /web/<源站id>/video.mp4，映射到磁盘目录
        String rel = file.startsWith("/web/") ? file.substring(5) : file;
        Path path = Paths.get(WEBROOT, rel);
        if (!Files.isReadable(path))
        {
            return ResponseEntity.notFound().build();
        }
        long size = 0;
        try (InputStream in = Files.newInputStream(path))
        {
            size = Files.size(path);
        }
        catch (IOException e)
        {
            log.warn("读取本地视频失败 {}", path);
            return ResponseEntity.notFound().build();
        }
        String name = "mrds-" + row.get("sourceId") + ".mp4";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentLength(size)
                .body(new FileSystemResource(path));
    }
}
