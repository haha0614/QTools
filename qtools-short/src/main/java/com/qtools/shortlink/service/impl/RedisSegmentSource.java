package com.qtools.shortlink.service.impl;

import com.qtools.shortlink.service.SegmentSource;
import com.qtools.shortlink.support.ShortCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisSegmentSource implements SegmentSource {
    private static final long SEGMENT = 1000;
    private static final String SEQ_KEY = "short:seq";

    private final StringRedisTemplate stringRedisTemplate;

    public RedisSegmentSource(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public long nextSegmentEnd() {
        // 第一次用：把 key 初始化成 62^5 - 1，保证第一个号是 100000
        stringRedisTemplate.opsForValue()
                .setIfAbsent(SEQ_KEY,
                        String.valueOf(ShortCode.FIRST_SIX_DIGIT_ID - 1));
        // 之后每次 INCRBY 1000，返回新值作为段终点
        Long end = stringRedisTemplate.opsForValue().increment(SEQ_KEY, segmentSize());
        if (end == null) {
            throw new IllegalStateException("取号段失败：Redis INCRBY 返回 null，key=" + SEQ_KEY);
        }
        return end;
    }

    @Override
    public long segmentSize() {
        return SEGMENT;
    }
}
