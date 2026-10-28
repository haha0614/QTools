package com.qtools.media.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

/**
 * 模型兜底的<b>回复解析</b>单测（P6.02）—— 纯函数，不调模型
 *
 * <p>解析是「模型最后一公里」：3B 模型经常回 {@code "Restricted."}、
 * {@code "答案：guidance"}，甚至 {@code "not restricted, it is general"}。
 * 这里锁死三条口径：大小写不敏感、中文档位名也认、啰嗦回复按最严的一档算。</p>
 *
 * @author qtools
 */
public class MediaRatingLlmJudgeTest
{
    @Test
    public void plainWords()
    {
        assertEquals(MediaRating.GENERAL, MediaRatingLlmJudge.parseRating("general"));
        assertEquals(MediaRating.GUIDANCE, MediaRatingLlmJudge.parseRating("guidance"));
        assertEquals(MediaRating.RESTRICTED, MediaRatingLlmJudge.parseRating("restricted"));
    }

    @Test
    public void toleratesPunctuationAndCase()
    {
        assertEquals(MediaRating.RESTRICTED, MediaRatingLlmJudge.parseRating("  RestrICted。\n"));
        assertEquals(MediaRating.GUIDANCE, MediaRatingLlmJudge.parseRating("答案：guidance"));
        assertEquals(MediaRating.GENERAL, MediaRatingLlmJudge.parseRating("\"general\""));
    }

    @Test
    public void chineseTierNames()
    {
        assertEquals(MediaRating.GENERAL, MediaRatingLlmJudge.parseRating("大众级"));
        assertEquals(MediaRating.GUIDANCE, MediaRatingLlmJudge.parseRating("家长指导级"));
        assertEquals(MediaRating.RESTRICTED, MediaRatingLlmJudge.parseRating("限制级"));
    }

    @Test
    public void verboseReplyTakesStrictest()
    {
        assertEquals(MediaRating.RESTRICTED, MediaRatingLlmJudge.parseRating("not general, restricted"));
        // 提到 restricted 就算 restricted（宁可高估）：约束句「不是 restricted，是 general」也按 restricted 处理，
        // 这种极端回复在 temperature 0.1 下几乎不出现，但一旦出现，把露骨内容判成大众级的代价更大
        assertEquals(MediaRating.RESTRICTED, MediaRatingLlmJudge.parseRating("介于 general 与 restricted 之间，判 guidance"));
    }

    @Test
    public void garbageIsRejected()
    {
        assertNull(MediaRatingLlmJudge.parseRating(null));
        assertNull(MediaRatingLlmJudge.parseRating(""));
        assertNull(MediaRatingLlmJudge.parseRating("我无法判断"));
    }
}
