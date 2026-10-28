package com.qtools.media.support;

import java.util.regex.Pattern;

/**
 * 数据源 URL → {@code {host, port, db}}（P5.97 从扫描入库里提出来，维护任务也用它）
 *
 * <p>为什么每个起 node 子进程的地方都必须做这件事：子进程用 {@code --secret=} 只拿到口令，
 * 库名缺省是 {@code qtools}。管理端跑在克隆库 / 灾备库 / 多实例环境时，父进程读写 A 库、
 * 子进程却把结果写进 B 库（生产库），而接口返回的计数一切正常——这是最难发现的错法。
 * 扫描入库早就这么做了（见 {@code MediaScanIngestServiceImpl}），P5.97 把它提到这里，
 * 缩略图 / 精灵图 / 预览 / 补元数据 / 补哈希同样显式带上 {@code --host/--port/--db}。</p>
 *
 * <p>解析不出来（非 {@code jdbc:mysql:} 形状 / 库名为空 / 含非法字符）返回 {@code null}：
 * 调用方沿用配置里的连接参数并打一条 warn——宁可照旧跑，也不要因为解析失败把正常任务挡下来。</p>
 *
 * @author qtools
 */
public final class MediaDataSourceTarget
{
    /** host / port / db 允许的字符：它会被拼成命令行参数，绝不能带空白或引号 */
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    private MediaDataSourceTarget()
    {
    }

    /**
     * @param jdbcUrl 形如 {@code jdbc:mysql://localhost:3307/qtools?useUnicode=true}
     * @return {@code [host, port, db]}；解析不出来返回 null
     */
    public static String[] parse(String jdbcUrl)
    {
        String url = jdbcUrl == null ? "" : jdbcUrl.trim();
        int mark = url.indexOf("//");
        if (mark < 0)
        {
            return null;
        }
        String rest = url.substring(mark + 2);
        int slash = rest.indexOf('/');
        if (slash < 0)
        {
            return null;
        }
        String authority = rest.substring(0, slash);
        String dbPart = rest.substring(slash + 1);
        int stop = dbPart.length();
        for (int i = 0; i < dbPart.length(); i++)
        {
            char c = dbPart.charAt(i);
            if (c == '?' || c == ';')
            {
                stop = i;
                break;
            }
        }
        String db = dbPart.substring(0, stop);
        String host = authority;
        String port = "3306";
        int colon = authority.lastIndexOf(':');
        if (colon > 0)
        {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
        }
        if (!PART.matcher(host).matches() || !PART.matcher(port).matches() || !PART.matcher(db).matches())
        {
            return null;
        }
        return new String[] { host, port, db };
    }

    /** 拼成追加到命令行尾部的三个参数（{@code --host= --port= --db=}）：位置在配置项之后，因此优先级更高 */
    public static String[] arguments(String jdbcUrl)
    {
        String[] target = parse(jdbcUrl);
        return target == null ? new String[0]
                : new String[] { "--host=" + target[0], "--port=" + target[1], "--db=" + target[2] };
    }
}
