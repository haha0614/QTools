package com.qtools.media.support;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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
