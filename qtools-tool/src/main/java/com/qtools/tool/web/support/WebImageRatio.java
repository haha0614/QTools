package com.qtools.tool.web.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从 JPEG 文件头读宽高。
 *
 * <p>用途：库里还没有 ffprobe 探到的视频比例时，用图集第一张（p1.jpg）的比例兜底，
 * 让播放器首帧就有合理尺寸，而不是先按 16:9 再跳、或者塌成 &lt;video&gt; 的默认 300×169。</p>
 */
public final class WebImageRatio
{
    private WebImageRatio()
    {
    }

    /**
     * @return {@code "宽/高"}，读不到返回 null
     */
    public static String of(Path file)
    {
        if (file == null || !Files.isRegularFile(file))
        {
            return null;
        }
        try (InputStream in = Files.newInputStream(file))
        {
            // 只读 64KB：SOF 段在文件头部，不需要整张图
            byte[] buf = in.readNBytes(65536);
            int i = 2;
            while (i + 9 < buf.length)
            {
                if ((buf[i] & 0xFF) != 0xFF)
                {
                    i += 1;
                    continue;
                }
                int marker = buf[i + 1] & 0xFF;
                // SOF0..SOF15，排除 DHT(c4)/JPG(c8)/DAC(cc)
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC)
                {
                    int h = ((buf[i + 5] & 0xFF) << 8) | (buf[i + 6] & 0xFF);
                    int w = ((buf[i + 7] & 0xFF) << 8) | (buf[i + 8] & 0xFF);
                    return w > 0 && h > 0 ? w + "/" + h : null;
                }
                int len = ((buf[i + 2] & 0xFF) << 8) | (buf[i + 3] & 0xFF);
                if (len < 2)
                {
                    return null;
                }
                i += 2 + len;
            }
            return null;
        }
        catch (IOException e)
        {
            return null;
        }
    }
}
