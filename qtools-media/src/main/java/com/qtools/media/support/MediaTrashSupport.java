package com.qtools.media.support;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 回收站搬运工具：条目命名 + 「移动，跨卷时退化为复制 + 删源」
 *
 * <p><b>为什么单独抽出来</b>：软删（{@code MediaAdminServiceImpl.delete}）、恢复（{@code restore}）、
 * 彻底删除（{@code purgeTrash}）与 P4-A 的重复文件合并（{@code MediaDuplicateServiceImpl.merge}）
 * 都要把文件搬进同一套 {@code _媒体站/trash/<kind>/<rootId>/<id>__<原名>} 结构。命名规则一旦分成
 * 两份实现，「回收站列表能不能认出这条、能不能恢复」就会悄悄漂移，因此这里只有一个来源。</p>
 *
 * <p>没有任何真删路径被隐藏：{@link #moveOrCopy} 只搬，{@link #deleteRecursively} 只在
 * 「彻底删除」这一条既有链路上被调用。</p>
 *
 * @author qtools
 */
public final class MediaTrashSupport
{
    private static final Logger log = LoggerFactory.getLogger(MediaTrashSupport.class);

    /** 回收站条目名里的 id 分隔符：{@code <id>__<原名>} */
    public static final String TRASH_SEP = "__";

    /** 旁注目录名（与条目同目录的 {@code .meta/}）：回收站列表要跳过以 . 开头的名字 */
    public static final String META_DIR = ".meta";

    private static final DateTimeFormatter SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private MediaTrashSupport()
    {
    }

    /**
     * 回收站目标名：{@code <id>__<原名>}，已存在时加时间戳后缀，绝不覆盖
     *
     * <p>时间戳形态 {@code <id>__<yyyyMMddHHmmss>[_n]__<原名>} 与
     * {@code MediaAdminServiceImpl#parseTrashEntry} 的解析规则一一对应：改这里就会同时改
     * 「回收站列表怎么算删除时间」和「恢复时取哪一份」，两边必须同源。</p>
     */
    public static Path uniqueTarget(Path dir, String id, String baseName)
    {
        Path target = dir.resolve(id + TRASH_SEP + baseName);
        if (!Files.exists(target))
        {
            return target;
        }
        String stamp = LocalDateTime.now().format(SUFFIX_FORMAT);
        target = dir.resolve(id + TRASH_SEP + stamp + TRASH_SEP + baseName);
        int n = 1;
        while (Files.exists(target))
        {
            target = dir.resolve(id + TRASH_SEP + stamp + "_" + n + TRASH_SEP + baseName);
            n++;
        }
        return target;
    }

    /**
     * 把「原相对路径」落盘到条目旁边：{@code .meta/<条目名>.path}
     *
     * <p>解决的问题：重复副本清理 / 重复文件删除是「文件进回收站 + 删掉 DB 行」，
     * 行没了以后回收站就只能显示「原记录已丢失」，既看不到它原来在哪，也没法恢复。
     * 旁注在搬移成功之后写，只存原相对路径一行，读写都在本类里，不外泄格式。</p>
     */
    /** 旁注内容：原相对路径 + 内容哈希（有哈希就能在回收站里出封面/大图） */
    public static final class Meta
    {
        public final String relPath;
        public final String contentHash;

        Meta(String relPath, String contentHash)
        {
            this.relPath = relPath;
            this.contentHash = contentHash;
        }
    }

    public static void writeOriginalRel(Path entryPath, String originalRel)
    {
        writeMeta(entryPath, originalRel, null);
    }

    /** 写旁注（{@code rel=} / {@code hash=} 两行）；哈希为空时只写路径 */
    public static void writeMeta(Path entryPath, String originalRel, String contentHash)
    {
        if (entryPath == null || entryPath.getFileName() == null
                || originalRel == null || originalRel.isEmpty())
        {
            return;
        }
        Path parent = entryPath.getParent();
        if (parent == null)
        {
            return;
        }
        try
        {
            Path dir = parent.resolve(META_DIR);
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder("rel=").append(originalRel).append('\n');
            if (contentHash != null && !contentHash.isEmpty())
            {
                sb.append("hash=").append(contentHash).append('\n');
            }
            Files.writeString(dir.resolve(entryPath.getFileName().toString() + ".path"), sb.toString(),
                    StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            // 写不进去不能影响删除本身：最多是回收站里这条显示「原记录已丢失」
            log.warn("写回收站原路径旁注失败 {}：{}", entryPath, e.getMessage());
        }
    }

    /** 读旁注；没有旁注（老条目 / 未落盘）返回 null */
    public static String readOriginalRel(Path entryPath)
    {
        Meta meta = readMeta(entryPath);
        return meta == null ? null : meta.relPath;
    }

    /**
     * 读旁注（兼容两种格式）
     *
     * <p>新格式是 {@code rel=<路径>} / {@code hash=<哈希>} 两行；更早只写了一行纯路径，
     * 没有 {@code =} 就按「整段是路径」解释，老条目不会因为格式变化读不出来。</p>
     */
    public static Meta readMeta(Path entryPath)
    {
        if (entryPath == null || entryPath.getFileName() == null || entryPath.getParent() == null)
        {
            return null;
        }
        Path file = entryPath.getParent().resolve(META_DIR)
                .resolve(entryPath.getFileName().toString() + ".path");
        try
        {
            if (!Files.isRegularFile(file))
            {
                return null;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (text.isEmpty())
            {
                return null;
            }
            if (!text.contains("="))
            {
                return new Meta(text, null);
            }
            String rel = null;
            String hash = null;
            for (String line : text.split("\r?\n"))
            {
                int eq = line.indexOf('=');
                if (eq <= 0)
                {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if ("rel".equals(key))
                {
                    rel = value;
                }
                else if ("hash".equals(key))
                {
                    hash = value;
                }
            }
            if (rel == null || rel.isEmpty())
            {
                rel = null;
            }
            if (rel == null && (hash == null || hash.isEmpty()))
            {
                return null;
            }
            return new Meta(rel, hash);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /**
     * 移动；跨卷失败时退化为「复制 + 删源」
     *
     * <p>{@link Files#move} 同卷是改名，跨卷（多库根常是另一块盘）直接抛异常。这里补一层：
     * 复制完整成功之后才删源；复制中途失败就清掉残留目标再抛出，绝不留半份文件、
     * 也绝不在复制失败时先删掉源。</p>
     */
    public static void moveOrCopy(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target);
            return;
        }
        catch (IOException e)
        {
            log.warn("移动失败，改走复制+删源（多为跨盘）：{} -> {}（{}）", source, target, e.getMessage());
        }
        try
        {
            copyRecursively(source, target);
        }
        catch (IOException e)
        {
            try
            {
                deleteRecursively(target);
            }
            catch (IOException ignored)
            {
                log.warn("清理复制残留失败：{}", target);
            }
            throw e;
        }
        deleteRecursively(source);
    }

    /** 递归复制（相册软删搬的是整个目录）；目标已存在即失败，绝不覆盖 */
    private static void copyRecursively(Path source, Path target) throws IOException
    {
        Files.walkFileTree(source, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
            {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 彻底删除：文件直接删，目录递归删（回收站里的目录来自相册软删） */
    public static void deleteRecursively(Path target) throws IOException
    {
        if (!Files.isDirectory(target))
        {
            Files.delete(target);
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                if (exc != null)
                {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
