package com.qtools.media.support;

import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 离线批量算感知指纹（P6.77）——把 SQL 打到标准输出，由调用方喂给 mysql。
 *
 * <p>为什么离线而不是接口里跑：全库 9443 张要 2-3 分钟，HTTP 会超时；而这个动作本质是
 * 一次性的回填，跑一次就够，之后新图由维护面板的「算相似指纹」增量补齐。</p>
 *
 * <pre>
 * java -cp qtools-media/target/classes com.qtools.media.support.MediaPhashTool &lt;thumbsDir&gt; &gt; phash.sql
 * </pre>
 */
public final class MediaPhashTool
{
    public static void main(String[] args) throws Exception
    {
        if (args.length < 1)
        {
            System.err.println("用法: MediaPhashTool <thumbsDir>");
            System.exit(2);
        }
        Path dir = Paths.get(args[0]).resolve("p8");
        if (!Files.isDirectory(dir))
        {
            System.err.println("不是目录: " + dir);
            System.exit(2);
        }
        PrintStream out = System.out;
        out.println("-- MediaPhashTool generated");
        int ok = 0;
        int fail = 0;
        try (DirectoryStream<Path> it = Files.newDirectoryStream(dir, "*.jpg"))
        {
            for (Path f : it)
            {
                String name = f.getFileName().toString();
                String hash = name.substring(0, name.length() - 4).toLowerCase();
                if (hash.length() != 16)
                {
                    continue;
                }
                Long code = MediaPhash.of(f);
                if (code == null)
                {
                    fail++;
                    continue;
                }
                out.println("update media_photo set phash=" + code.longValue()
                        + " where content_hash='" + hash + "';");
                ok++;
            }
        }
        out.flush();
        System.err.println("phash ok=" + ok + " fail=" + fail);
    }
}
