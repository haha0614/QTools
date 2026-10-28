package com.qtools.media.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.qtools.media.support.MediaRatingRules.Field;
import com.qtools.media.support.MediaRatingRules.Verdict;

/**
 * 自动分级规则引擎单测（P6.02）—— 纯函数，不起 Spring、不连库
 *
 * <p>测试<b>直接加载 classpath 里真实的 rating-rules.json</b>：词表本身就是被测对象，
 * 用一个假的迷你词表测匹配逻辑，测不出「生产词表漏了一个大词」这种最要命的问题。</p>
 *
 * <p>样例取自现网真实标题（已脱敏压缩），正负两面都覆盖：中文 / 英文 / 中英混合、
 * 单字段 / 多字段冲突、相册按照片聚合。</p>
 *
 * @author qtools
 */
public class MediaRatingRulesTest
{
    private static MediaRatingRules rules;

    @BeforeAll
    public static void loadRules()
    {
        rules = MediaRatingRules.load("");
    }

    // ------------------------------------------------------------------ 限制级（正样例）

    @Test
    @DisplayName("词表本身：能加载、条数足够、id 不重复")
    public void ruleTableLoads()
    {
        assertTrue(rules.size() >= 30, "词表至少 30 条规则，实际 " + rules.size());
        assertTrue(rules.getSource().contains("rating-rules.json"));
    }

    @Test
    public void restrictedAndMasturbationTitle()
    {
        assertRating(MediaRating.RESTRICTED, "自慰_毛衣高跟_cicimengB_3m", "自慰");
    }

    @Test
    public void restrictedOral()
    {
        assertRating(MediaRating.RESTRICTED, "3D_NieR_2B_口穴_1m", "动漫");
    }

    @Test
    public void restrictedBedScene()
    {
        assertRating(MediaRating.RESTRICTED, "ASMR_剧情_灰丝床戏_15m", "ASMR");
        assertEquals(MediaRating.RESTRICTED, rules.classify(Field.of("title", "床戏片段")).getRating());
    }

    @Test
    public void restrictedNudity()
    {
        assertRating(MediaRating.RESTRICTED, "古风_白裙裸胸_24s", "跳舞");
        assertRating(MediaRating.RESTRICTED, "写真_浴室_全裸摆拍_1m", "写真");
        assertRating(MediaRating.RESTRICTED, "写真_露点3_紫光房_54s", "写真");
    }

    @Test
    public void restrictedNudeDanceBeatsDanceRule()
    {
        // 热舞/跳舞 是 guidance，裸舞 是 restricted：同时出现时必须是最严的 restricted
        Verdict verdict = rules.classify(Field.of("title", "跳舞_裸舞_LikeACat_3m"));
        assertEquals(MediaRating.RESTRICTED, verdict.getRating());
    }

    @Test
    public void restrictedEroticWords()
    {
        assertRating(MediaRating.RESTRICTED, "娜娜_律师制服的淫语挑逗_无码_32m", "nana");
        assertRating(MediaRating.RESTRICTED, "[淫词艳曲]《淫荡魅魔》", "词曲");
        assertRating(MediaRating.RESTRICTED, "娜娜_淫荡修女_肛塞白丝_41m", "nana");
    }

    @Test
    public void restrictedFetishAndBigScale()
    {
        assertRating(MediaRating.RESTRICTED, "主播_情趣装直播_104m", "自慰");
        assertRating(MediaRating.RESTRICTED, "1✿稚气萝姬✿ 甜美可爱大尺度高清私拍78P", "写真");
        assertRating(MediaRating.RESTRICTED, "SM捆绑_拘束具_口穴潮_130m", "战斗");
    }

    @Test
    public void restrictedSolicitationAndAdultIndustry()
    {
        assertRating(MediaRating.RESTRICTED, "广告_约炮引流_01_16s", "广告");
        assertRating(MediaRating.RESTRICTED, "EPORNER.COM - JAV PMV Catch Catch", "合集");
        assertRating(MediaRating.RESTRICTED, "DANDY960_日本AV合集_多场景_230m", "战斗");
    }

    @Test
    public void restrictedEnglishMarkersIgnoreCase()
    {
        assertEquals(MediaRating.RESTRICTED, rules.classify(Field.of("title", "NSFW collection")).getRating());
        assertEquals(MediaRating.RESTRICTED, rules.classify(Field.of("title", "Hentai animation")).getRating());
        assertEquals(MediaRating.RESTRICTED, rules.classify(Field.of("title", "my porn folder")).getRating());
        assertEquals(MediaRating.RESTRICTED, rules.classify(Field.of("title", "ASMR_18禁_豹纹内衣_19m")).getRating());
    }

    @Test
    public void restrictedFantasyTags()
    {
        assertRating(MediaRating.RESTRICTED, "【视频音声】精灵的异世界孕活~", "动漫");
        assertRating(MediaRating.RESTRICTED, "娜娜_吸精魅魔1_魅魔装床戏_38m", "nana");
    }

    // ------------------------------------------------------------------ 家长指导级（正样例）

    @Test
    public void guidancePhotoShoot()
    {
        assertRating(MediaRating.GUIDANCE, "写真_YeonWoo_泳装浴室_4K_11m", "写真");
        assertRating(MediaRating.GUIDANCE, "yuuhui玉汇 - 太太的春游计划【V-1】", "写真");
    }

    @Test
    public void guidanceLingerieAndStocking()
    {
        assertRating(MediaRating.GUIDANCE, "ASMR_双耳麦克风_白内衣_28m", "ASMR");
        assertRating(MediaRating.GUIDANCE, "ASMR_白丝绝对领域_7m", "ASMR");
    }

    @Test
    public void guidanceDance()
    {
        assertRating(MediaRating.GUIDANCE, "跳舞_韩国女团_内衣热舞_34m", "跳舞");
        assertRating(MediaRating.GUIDANCE, "热舞_白蕾丝_12s", "跳舞");
    }

    @Test
    public void guidanceCos()
    {
        assertRating(MediaRating.GUIDANCE, "古风_小乔 cos 写真_18s", "跳舞");
        assertEquals(MediaRating.GUIDANCE, rules.classify(Field.of("title", "洛丽塔 cosplay")).getRating());
    }

    @Test
    public void guidanceSuggestive()
    {
        assertRating(MediaRating.GUIDANCE, "擦边舞蹈合集", "合集");
        assertRating(MediaRating.GUIDANCE, "私房摄影 第 3 期", "写真");
        assertRating(MediaRating.GUIDANCE, "兔女郎热舞 4m", "跳舞");
    }

    @Test
    public void guidanceAsmrWithoutExplicit()
    {
        assertRating(MediaRating.GUIDANCE, "ASMR_双耳麦克风_5m", "ASMR");
        assertRating(MediaRating.GUIDANCE, "ASMR_麦克风耳语_黑丝_57m", "ASMR");
    }

    // ------------------------------------------------------------------ 大众级（正样例）

    @Test
    public void generalCarMaintenance()
    {
        assertRating(MediaRating.GENERAL, "故障灯视频第二版(1)", "2026年9月30日");
        assertRating(MediaRating.GENERAL, "交通标志易混", "2026年10月13日");
        assertRating(MediaRating.GENERAL, "2026年10月23日养车省钱小技巧", "2026年10月20日");
    }

    @Test
    public void generalDesktopWallpaper()
    {
        assertRating(MediaRating.GENERAL, "look-sit-01", "FRAC_桌面互动壁纸");
        assertRating(MediaRating.GENERAL, "look-classic-24", "FRAC_桌面互动壁纸");
    }

    @Test
    public void generalBusinessAndTutorial()
    {
        assertRating(MediaRating.GENERAL, "营业执照", "图片");
        assertRating(MediaRating.GENERAL, "软件演示 第三集", "图片");
        assertRating(MediaRating.GENERAL, "登录教程", "素材");
    }

    // ------------------------------------------------------------------ 负样例（规则未命中 = 不确定）

    @Test
    public void unknownTitlesHitNothing()
    {
        assertTrue(rules.classify(Field.of("title", "look-sit-01")).isEmpty());
        assertTrue(rules.classify(Field.of("title", "mmexport1792479821018"), Field.of("folder", "2026年10月20日"))
                .isEmpty());
        assertTrue(rules.classify(Field.of("title", "抖音20261026-731513")).isEmpty());
        assertTrue(rules.classify(Field.of("title", "古风_小乔_18s"), Field.of("folder", "跳舞")).isEmpty(),
                "「跳舞」不在词表里：舞蹈本身不构成家长指导，只有热舞/艳舞才算");
        assertTrue(rules.classify(Field.of("title", "ba99ce10012a813e29b1f8baf517fef1")).isEmpty());
    }

    @Test
    public void emptyAndNullFieldsDoNotMatch()
    {
        assertTrue(rules.classify((Field) null).isEmpty());
        assertTrue(rules.classify(Field.of("title", null)).isEmpty());
        assertTrue(rules.classify(Field.of("title", "   ")).isEmpty());
        assertNull(Field.of("title", ""));
    }

    // ------------------------------------------------------------------ 冲突与聚合口径

    @Test
    public void generalWordNeverBeatsRestricted()
    {
        // 标题是大众级素材、目录名却是「自慰」：以 restricted 为准（宁可高估）
        Verdict verdict = rules.classify(Field.of("title", "故障灯"), Field.of("folder", "自慰"));
        assertEquals(MediaRating.RESTRICTED, verdict.getRating());
    }

    @Test
    public void guidanceAndGeneralTakeGuidance()
    {
        Verdict verdict = rules.classify(Field.of("title", "泳装壁纸"), Field.of("folder", "素材"));
        assertEquals(MediaRating.GUIDANCE, verdict.getRating());
    }

    @Test
    public void fieldOrderDoesNotMatter()
    {
        Verdict a = rules.classify(Field.of("title", "写真"), Field.of("folder", "自慰"));
        Verdict b = rules.classify(Field.of("folder", "自慰"), Field.of("title", "写真"));
        assertEquals(a.getRating(), b.getRating());
        assertEquals(MediaRating.RESTRICTED, a.getRating());
    }

    @Test
    public void strictestMergesToStricterSide()
    {
        Verdict guidance = rules.classify(Field.of("title", "黑丝写真"));
        Verdict general = rules.classify(Field.of("title", "故障灯"));
        Verdict merged = MediaRatingRules.strictest(guidance, general);
        assertEquals(MediaRating.GUIDANCE, merged.getRating());
        assertTrue(merged.getHits().stream().anyMatch(hit -> "黑丝".equals(hit.getWord())));
        assertEquals(MediaRating.GUIDANCE, MediaRatingRules.strictest(general, guidance).getRating());
    }

    @Test
    @DisplayName("相册按内容聚合：目录是写真、册里一张照片落在裸拍目录 → 整册 restricted")
    public void albumAggregatesByStrictestPhoto()
    {
        Verdict album = rules.classify(Field.of("title", "010 涩"), Field.of("dir", "写真/念念 Nian Nian"));
        assertEquals(MediaRating.GUIDANCE, album.getRating());
        Verdict photo = rules.classify(Field.of("path", "写真/念念 Nian Nian/全裸/001.jpg"));
        Verdict merged = MediaRatingRules.strictest(album, photo);
        assertEquals(MediaRating.RESTRICTED, merged.getRating());
    }

    @Test
    public void reasonIsHumanReadable()
    {
        Verdict verdict = rules.classify(Field.of("title", "自慰_和室玩具_JVID_15m"));
        assertFalse(verdict.reason().isEmpty());
        assertTrue(verdict.reason().contains("『自慰』"), "理由要写出命中的词，实际：" + verdict.reason());
        assertTrue(verdict.reason().contains("标题"), "理由要写出命中的字段，实际：" + verdict.reason());
        assertTrue(rules.classify(Field.of("title", "纯 UUID 名")).reason().contains("未命中"));
    }

    @Test
    public void tagFieldParticipates()
    {
        Verdict verdict = rules.classify(Field.of("title", "第 3 集"), Field.of("tag", "cos热舞"));
        assertEquals(MediaRating.GUIDANCE, verdict.getRating());
    }

    // ------------------------------------------------------------------ 词表校验

    @Test
    public void invalidRuleTableIsRejected()
    {
        assertThrowsIllegalState("{}");
        assertThrowsIllegalState("{\"rules\":[]}");
        assertThrowsIllegalState("{\"rules\":[{\"id\":\"a\",\"rating\":\"explicit\",\"mode\":\"contains\",\"pattern\":\"x\"}]}");
        assertThrowsIllegalState("{\"rules\":[{\"id\":\"a\",\"rating\":\"general\",\"mode\":\"bad\",\"pattern\":\"x\"}]}");
    }

    @Test
    public void containsModeIsCaseInsensitive()
    {
        MediaRatingRules mini = MediaRatingRules.fromJson(
                "{\"rules\":[{\"id\":\"t\",\"rating\":\"restricted\",\"mode\":\"contains\",\"pattern\":\"nsfw\"}]}",
                "inline");
        assertEquals(MediaRating.RESTRICTED, mini.classify(Field.of("title", "NSFW")).getRating());
    }

    private void assertRating(String expected, String title, String folder)
    {
        Verdict verdict = rules.classify(Field.of("title", title), Field.of("folder", folder));
        assertEquals(expected, verdict.getRating(), "『" + title + "』（" + folder + "）判定不符，理由：" + verdict.reason());
    }

    private void assertThrowsIllegalState(String json)
    {
        try
        {
            MediaRatingRules.fromJson(json, "inline");
        }
        catch (IllegalStateException e)
        {
            return;
        }
        throw new AssertionError("非法词表应当加载失败：" + json);
    }
}
