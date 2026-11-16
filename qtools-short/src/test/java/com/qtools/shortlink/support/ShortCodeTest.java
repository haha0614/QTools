package com.qtools.shortlink.support;

import static org.junit.jupiter.api.Assertions.*;

class ShortCodeTest {

    @org.junit.jupiter.api.Test
    void encode() {
        assertEquals("0", ShortCode.encode(0));
        assertEquals("Z", ShortCode.encode(61));      // 0-9 是 0..9，10..35 小写，36..61 大写
        assertEquals("10", ShortCode.encode(62));
        assertEquals("100000", ShortCode.encode(ShortCode.FIRST_SIX_DIGIT_ID));
    }
}