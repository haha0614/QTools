package com.qtools.media.support;

import java.util.List;
import com.qtools.common.utils.StringUtils;

/**
 * 配置里的「附加参数」过滤（{@code qtools.media.*Args} → 子进程命令行）
 *
 * <p>管理端的所有子进程参数都由后端按白名单拼装，配置里那一串是部署环境参数（{@code --root=} /
 * {@code --port=} / {@code --secret=}）。这里统一做三层过滤：</p>
 * <ul>
 *   <li>{@code forbidden}：永不接受的开关（{@code --truncate} 会清 6 张 media_* 表）；</li>
 *   <li>{@code managed}：只能由代码按请求决定的开关（{@code --backfill-hash} / {@code --force}）；</li>
 *   <li>{@code forbiddenPrefixes}：请求已经给了值、配置里的同名参数必须让位的参数
 *       （{@code --root=} / {@code --limit=} / {@code --out=}）。</li>
 * </ul>
 *
 * <p>抽成公共类的理由：{@code MediaScanServiceImpl}（按 mode 的维护任务）与
 * {@code MediaScanIngestServiceImpl}（扫描入库）读的是同一份配置，过滤规则一旦分叉，
 * 「按钮点的是增量、实际跑的是全量」这类问题就会从一个入口悄悄漏到另一个入口。</p>
 *
 * @author qtools
 */
public final class MediaScanArgs
{
    private MediaScanArgs()
    {
    }

    /** 追加配置里的附加参数（跳过空白、禁用的精确值、受管开关，以及被请求覆盖的前缀参数） */
    public static void appendConfigured(List<String> command, String extra, List<String> forbidden,
            List<String> managed, List<String> forbiddenPrefixes)
    {
        if (StringUtils.isEmpty(extra))
        {
            return;
        }
        for (String arg : extra.trim().split("\\s+"))
        {
            if (arg.isEmpty() || forbidden.contains(arg) || managed.contains(arg))
            {
                continue;
            }
            if (startsWithAny(arg, forbiddenPrefixes))
            {
                continue;
            }
            command.add(arg);
        }
    }

    /** arg 是否以 prefixes 里任一项开头 */
    public static boolean startsWithAny(String arg, List<String> prefixes)
    {
        for (String prefix : prefixes)
        {
            if (arg.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }
}
