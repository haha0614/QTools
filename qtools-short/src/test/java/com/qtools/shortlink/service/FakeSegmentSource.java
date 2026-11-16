package com.qtools.shortlink.service;

import com.qtools.shortlink.support.ShortCode;

public class FakeSegmentSource implements SegmentSource {
    private long current = ShortCode.FIRST_SIX_DIGIT_ID - 1; // 让第一个段结束于916132832
     int calls = 0;
     boolean fail = false;

    @Override
    public long nextSegmentEnd() {
        calls++;
        if (fail) throw new IllegalStateException("redis down");
        current += segmentSize();
        return current;
    }

    @Override
    public long segmentSize() {
        return 1000;
    }
}
