package com.qtools.shortlink.service;

import com.qtools.shortlink.service.impl.ShortCodeServiceImpl;
import com.qtools.shortlink.support.ShortCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class IShortCodeServiceTest {
    @Test
    @DisplayName("跨段取2500个短码：全不重复，且从100000起步")
    void noRepetitionAcrossParagraphs() {
        FakeSegmentSource src = new FakeSegmentSource();
        ShortCodeServiceImpl svc = new ShortCodeServiceImpl(src);
        Set<String> set = new HashSet<>();
        for (int i = 0; i < 2500; i++) {
            assertTrue(set.add(svc.nextCode()), "第" + i + "个重复了");
        }
        assertEquals(2500, set.size());
    }

    @Test
    @DisplayName("号段生效：2500个码只申请3次段")
    void 段内不查外部源() {
        FakeSegmentSource src = new FakeSegmentSource();
        ShortCodeServiceImpl svc = new ShortCodeServiceImpl(src);
        for (int i = 0; i < 2500; i++) {
            svc.nextCode();
        }
        assertEquals(3,src.calls);
    }

    @Test
    @DisplayName("8线程并发4000个码：无重复")
    void concurrentUniqueness() throws Exception {
        FakeSegmentSource src = new FakeSegmentSource();
        ShortCodeServiceImpl svc = new ShortCodeServiceImpl(src);
        int threads = 8;
        int per = 500;
        Set<String> set = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < per; i++) {
                        set.add(svc.nextCode());
                    }
                }catch (InterruptedException ignored){

                } finally {done.countDown();}
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(threads * per, set.size(), "并发下出现重复短码");
    }

    @Test
    @DisplayName("取段失败要抛异常，不能发出不唯一的码")
    void segmentRetrievalFailed() {
        FakeSegmentSource src = new FakeSegmentSource();
        ShortCodeServiceImpl svc = new ShortCodeServiceImpl(src);
        for (int i = 0; i < 1000; i++) {
            svc.nextCode();
        }
        src.fail = true;
        assertThrows(RuntimeException.class, svc::nextCode);
    }

    @Test
    @DisplayName("第一个码就是 6 位：100000")
    void firstCodeIsSixDigits() {
        ShortCodeServiceImpl svc = new ShortCodeServiceImpl(new FakeSegmentSource());
        String first = svc.nextCode();
        assertEquals("100000", first);
        assertTrue(first.length() >= 6, "短码长度必须 ≥ 6，实际=" + first);
    }

}