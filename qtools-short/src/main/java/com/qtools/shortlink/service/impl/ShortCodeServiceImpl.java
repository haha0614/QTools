package com.qtools.shortlink.service.impl;

import com.qtools.shortlink.service.IShortCodeService;
import com.qtools.shortlink.service.SegmentSource;
import com.qtools.shortlink.support.ShortCode;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
public class ShortCodeServiceImpl implements IShortCodeService {
    private final AtomicLong cursor = new AtomicLong(0); // 记录段内已经用到哪里
    private final AtomicLong limit = new AtomicLong(0); // 当前段的终点
    private final SegmentSource source;

    public ShortCodeServiceImpl(SegmentSource source) { this.source = source; }

    private synchronized void takeSegment() {
        long end = source.nextSegmentEnd();
        cursor.set(end - source.segmentSize());
        limit.set(end);
    }
    /**
     * 取出下一个短码（6位起步，唯一）
     * @return 至少六位的短码
     */
    @Override
    public String nextCode() {
        long id = cursor.incrementAndGet(); // 自增加一
        if (id > limit.get()) { // 说明段内用完了
            synchronized (this) {
                if (cursor.get() > limit.get()) {
                    takeSegment(); // 双重检查，避免重复取段
                }
                id = cursor.incrementAndGet();
            }
        }
        return ShortCode.encode(id);
    }
}
