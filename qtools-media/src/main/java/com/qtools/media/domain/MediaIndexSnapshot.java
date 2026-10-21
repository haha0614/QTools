package com.qtools.media.domain;

/**
 * /api/media/index 的内存快照：序列化结果 + ETag + 构建时间
 *
 * <p>缓存 60s，期间同一版本直接复用字节体，ETag 因此稳定，If-None-Match 才可能命中 304。</p>
 *
 * @author qtools
 */
public class MediaIndexSnapshot
{
    /** 已序列化的 JSON 字节体 */
    private final byte[] body;

    /** 强 ETag（带双引号） */
    private final String etag;

    /** 构建时刻（毫秒） */
    private final long builtAt;

    public MediaIndexSnapshot(byte[] body, String etag, long builtAt)
    {
        this.body = body;
        this.etag = etag;
        this.builtAt = builtAt;
    }

    public byte[] getBody()
    {
        return body;
    }

    public String getEtag()
    {
        return etag;
    }

    public long getBuiltAt()
    {
        return builtAt;
    }
}
