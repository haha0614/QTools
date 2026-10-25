package com.qtools.media.support;

import java.math.BigDecimal;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.domain.MediaVideoMarker;
import com.qtools.media.domain.dto.MediaMetaPatch;
import com.qtools.media.domain.dto.MediaPersonPatch;
import com.qtools.media.domain.dto.MediaStudioPatch;
import com.qtools.media.exception.MediaException;

/**
 * 媒体库控制器共用的 body 解析工具
 *
 * <p>管理端（{@code /api/media/admin/**}）与站端（{@code /api/media/**}）对组织属性的
 * body 形状要求完全一致，所以 JSON → DTO 的转换只保留这一份：把一个 body 解析成
 * {@link MediaMetaPatch} 的规则（{@code containsKey} 即「字段出现」、{@code null} 即「清空」）
 * 两处必须逐字相同，否则同一个前端 payload 在两端会有不同结果。</p>
 *
 * <p>这里只管「类型转换 + 字段是否出现」，取值范围 / 编号上限 / pos 与时长比较全部在服务层做。
 * 非法 JSON、非对象 body、错误类型一律转 {@link MediaException#badRequest(String)}（400），
 * 不落到 500。</p>
 *
 * @author qtools
 */
public final class MediaBodyParser
{
    /** 只用于解析请求体，不参与响应序列化 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MediaBodyParser()
    {
    }

    /** 手工解析 body：空 body 允许；非法 JSON / 非对象一律 400（而不是落到 500） */
    public static Map<String, Object> parseObject(String rawBody, boolean required)
    {
        if (StringUtils.isEmpty(rawBody) || rawBody.trim().isEmpty())
        {
            if (required)
            {
                throw MediaException.badRequest("missing request body");
            }
            return Map.of();
        }
        try
        {
            Map<String, Object> parsed = MAPPER.readValue(rawBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>()
            {
            });
            return parsed == null ? Map.of() : parsed;
        }
        catch (Exception e)
        {
            throw MediaException.badRequest("invalid json body");
        }
    }

    /** confirm：接受 true / "true" / 1 / "1"，其余（含缺省）一律算未确认 */
    public static boolean booleanValue(Object value)
    {
        if (value instanceof Boolean)
        {
            return ((Boolean) value).booleanValue();
        }
        String text = stringValue(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    /** limit：接受数字或数字字符串；非法字符串 400（而不是落到 500） */
    public static Integer integerValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number)
        {
            return Integer.valueOf(((Number) value).intValue());
        }
        String text = stringValue(value);
        if (text == null)
        {
            return null;
        }
        try
        {
            return Integer.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest("limit 必须是整数");
        }
    }

    public static String stringValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equals(text))
        {
            return null;
        }
        return text;
    }

    public static Boolean boolValue(Object value)
    {
        if (value instanceof Boolean flag)
        {
            return flag;
        }
        if (value instanceof Number number)
        {
            return number.intValue() != 0;
        }
        String text = stringValue(value);
        if (text == null)
        {
            return null;
        }
        String lower = text.toLowerCase();
        if ("true".equals(lower) || "1".equals(lower))
        {
            return Boolean.TRUE;
        }
        if ("false".equals(lower) || "0".equals(lower))
        {
            return Boolean.FALSE;
        }
        throw MediaException.badRequest("hidden 只能是 true / false");
    }

    public static Integer intValue(Object value)
    {
        Long parsed = longValue(value);
        return parsed == null ? null : parsed.intValue();
    }

    public static Long longValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number number)
        {
            return number.longValue();
        }
        String text = stringValue(value);
        if (text == null || "null".equalsIgnoreCase(text))
        {
            return null;
        }
        try
        {
            return Long.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest("数值参数格式错误: " + text);
        }
    }

    /**
     * body → {@link MediaMetaPatch}
     *
     * <p>判断「出现」一律用 {@code containsKey}：JSON 里的 {@code "rating": null} 是<b>有值且为 null</b>，
     * 表示清空评分，不能用 get() == null 来当成缺省。</p>
     *
     * @param batch true = 批量接口（读 ids / markerDelta），false = 单条接口（读 markers）
     */
    public static MediaMetaPatch metaPatch(Map<String, Object> body, boolean batch)
    {
        MediaMetaPatch patch = new MediaMetaPatch();
        if (body.containsKey("rating"))
        {
            patch.setRatingPresent(true);
            patch.setRating(ratingValue(body.get("rating")));
        }
        if (body.containsKey("tagIds"))
        {
            patch.setTagIdsPresent(true);
            patch.setTagIds(longsValue(body.get("tagIds"), "tagIds"));
        }
        patch.setAddTagIds(longsValue(body.get("addTagIds"), "addTagIds"));
        patch.setRemoveTagIds(longsValue(body.get("removeTagIds"), "removeTagIds"));

        if (!batch)
        {
            if (body.containsKey("markers"))
            {
                patch.setMarkersPresent(true);
                List<MediaVideoMarker> markers = markersValue(body.get("markers"), "markers");
                patch.setMarkers(markers == null ? List.of() : markers);
            }
            return patch;
        }

        patch.setIds(stringsValue(body.get("ids"), "ids"));
        Object delta = body.get("markerDelta");
        if (delta != null)
        {
            if (!(delta instanceof Map))
            {
                throw MediaException.badRequest("markerDelta 必须是对象 {add:[], remove:[]}");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> deltaMap = (Map<String, Object>) delta;
            patch.setMarkerAdd(markersValue(deltaMap.get("add"), "markerDelta.add"));
            patch.setMarkerRemoveIds(longsValue(deltaMap.get("remove"), "markerDelta.remove"));
        }
        return patch;
    }

    /**
     * body → {@link MediaPersonPatch}（P3-A：人物新建 / 改名 / 改字段）
     *
     * <p>与 {@link #metaPatch} 同一套规则：{@code containsKey} 即「字段出现」，
     * JSON 里显式给 {@code null} 是<b>有值且为 null</b>（清空该列）。
     * 取值范围（名字 1-64、别名 128、备注 512 …）全部在服务层校验。</p>
     */
    public static MediaPersonPatch personPatch(Map<String, Object> body)
    {
        MediaPersonPatch patch = new MediaPersonPatch();
        if (body.containsKey("name"))
        {
            patch.setNamePresent(true);
            patch.setName(stringValue(body.get("name")));
        }
        if (body.containsKey("alias"))
        {
            patch.setAliasPresent(true);
            patch.setAlias(stringValue(body.get("alias")));
        }
        if (body.containsKey("note"))
        {
            patch.setNotePresent(true);
            patch.setNote(stringValue(body.get("note")));
        }
        if (body.containsKey("gender"))
        {
            patch.setGenderPresent(true);
            patch.setGender(stringValue(body.get("gender")));
        }
        if (body.containsKey("birthday"))
        {
            patch.setBirthdayPresent(true);
            patch.setBirthday(dateValue(body.get("birthday"), "birthday"));
        }
        if (body.containsKey("avatarPath"))
        {
            patch.setAvatarPresent(true);
            patch.setAvatarPath(stringValue(body.get("avatarPath")));
        }
        // P5-A 资料字段：全部可空，空串 / null = 清空；范围校验（身高 / 体重 / 年份）在服务层
        if (body.containsKey("measurements"))
        {
            patch.setMeasurementsPresent(true);
            patch.setMeasurements(stringValue(body.get("measurements")));
        }
        if (body.containsKey("careerStart"))
        {
            patch.setCareerStartPresent(true);
            patch.setCareerStart(intValue(body.get("careerStart")));
        }
        if (body.containsKey("careerEnd"))
        {
            patch.setCareerEndPresent(true);
            patch.setCareerEnd(intValue(body.get("careerEnd")));
        }
        if (body.containsKey("tattoos"))
        {
            patch.setTattoosPresent(true);
            patch.setTattoos(stringValue(body.get("tattoos")));
        }
        if (body.containsKey("piercings"))
        {
            patch.setPiercingsPresent(true);
            patch.setPiercings(stringValue(body.get("piercings")));
        }
        if (body.containsKey("ethnicity"))
        {
            patch.setEthnicityPresent(true);
            patch.setEthnicity(stringValue(body.get("ethnicity")));
        }
        if (body.containsKey("hairColor"))
        {
            patch.setHairColorPresent(true);
            patch.setHairColor(stringValue(body.get("hairColor")));
        }
        if (body.containsKey("eyeColor"))
        {
            patch.setEyeColorPresent(true);
            patch.setEyeColor(stringValue(body.get("eyeColor")));
        }
        if (body.containsKey("heightCm"))
        {
            patch.setHeightCmPresent(true);
            patch.setHeightCm(intValue(body.get("heightCm")));
        }
        if (body.containsKey("weightKg"))
        {
            patch.setWeightKgPresent(true);
            patch.setWeightKg(intValue(body.get("weightKg")));
        }
        if (body.containsKey("fakeBoobs"))
        {
            patch.setFakeBoobsPresent(true);
            patch.setFakeBoobs(boolValue(body.get("fakeBoobs")));
        }
        if (body.containsKey("nationality"))
        {
            patch.setNationalityPresent(true);
            patch.setNationality(stringValue(body.get("nationality")));
        }
        if (body.containsKey("twitter"))
        {
            patch.setTwitterPresent(true);
            patch.setTwitter(stringValue(body.get("twitter")));
        }
        if (body.containsKey("instagram"))
        {
            patch.setInstagramPresent(true);
            patch.setInstagram(stringValue(body.get("instagram")));
        }
        return patch;
    }

    /**
     * body → {@link MediaStudioPatch}（P5-A：工作室新建 / 改名 / 改字段）
     *
     * <p>与 {@link #personPatch} 同一套规则：{@code containsKey} 即「字段出现」，
     * 显式 {@code null} = 清空该列。name 必填且唯一的校验在服务层。</p>
     */
    public static MediaStudioPatch studioPatch(Map<String, Object> body)
    {
        MediaStudioPatch patch = new MediaStudioPatch();
        if (body.containsKey("name"))
        {
            patch.setNamePresent(true);
            patch.setName(stringValue(body.get("name")));
        }
        if (body.containsKey("alias"))
        {
            patch.setAliasPresent(true);
            patch.setAlias(stringValue(body.get("alias")));
        }
        if (body.containsKey("note"))
        {
            patch.setNotePresent(true);
            patch.setNote(stringValue(body.get("note")));
        }
        if (body.containsKey("avatarPath"))
        {
            patch.setAvatarPresent(true);
            patch.setAvatarPath(stringValue(body.get("avatarPath")));
        }
        if (body.containsKey("sort"))
        {
            patch.setSortPresent(true);
            patch.setSort(intValue(body.get("sort")));
        }
        if (body.containsKey("hidden"))
        {
            patch.setHiddenPresent(true);
            patch.setHidden(boolValue(body.get("hidden")));
        }
        return patch;
    }

    /**
     * 日期：只接受 {@code yyyy-MM-dd}（严格模式，2026-02-30 也 400）；空串 / null = 清空
     */
    public static Date dateValue(Object value, String field)
    {
        String text = stringValue(value);
        if (text == null)
        {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(text, position);
        if (parsed == null || position.getIndex() != text.length())
        {
            throw MediaException.badRequest(field + " 必须是 yyyy-MM-dd 格式的日期: " + text);
        }
        return parsed;
    }

    /**
     * 角色表 {@code {personId: role}}：键接受数字或数字字符串，值去空格后仍为字符串
     *
     * <p>缺省返回 {@code null}（= 不改角色）；空对象返回空表。键 / 值的合法性
     * （键必须属于本次提交的 personIds、角色 <= 32 字符）由服务层校验。</p>
     */
    public static Map<Long, String> rolesValue(Object value, String field)
    {
        if (value == null)
        {
            return null;
        }
        if (!(value instanceof Map))
        {
            throw MediaException.badRequest(field + " 必须是对象 {personId: role}");
        }
        Map<?, ?> raw = (Map<?, ?>) value;
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet())
        {
            Long personId = longValue(entry.getKey());
            if (personId == null)
            {
                throw MediaException.badRequest(field + " 里含空 personId");
            }
            out.put(personId, stringValue(entry.getValue()));
        }
        return out;
    }

    /** rating：接受整数或整数字符串；null 原样返回（= 清空）；小数 / 非数字一律 400 */
    public static Integer ratingValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number number)
        {
            double asDouble = number.doubleValue();
            if (asDouble != Math.rint(asDouble))
            {
                throw MediaException.badRequest("rating 必须是整数 0-100");
            }
            return Integer.valueOf(number.intValue());
        }
        String text = stringValue(value);
        if (text == null || "null".equalsIgnoreCase(text))
        {
            return null;
        }
        try
        {
            return Integer.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest("rating 必须是整数 0-100: " + text);
        }
    }

    /** id 数组：接受 [1,2] 或 ["1","2"]；缺省返回 null（= 不改动），空数组返回空表（= 全量清空） */
    public static List<Long> longsValue(Object value, String field)
    {
        if (value == null)
        {
            return null;
        }
        if (!(value instanceof List))
        {
            throw MediaException.badRequest(field + " 必须是数组");
        }
        List<?> raw = (List<?>) value;
        List<Long> out = new ArrayList<>(raw.size());
        for (Object item : raw)
        {
            Long id = longValue(item);
            if (id == null)
            {
                throw MediaException.badRequest(field + " 里含空 id");
            }
            out.add(id);
        }
        return out;
    }

    /** 字符串数组：每个元素去首尾空格，空串 400（ids 里出现空 id 一定是前端 bug） */
    public static List<String> stringsValue(Object value, String field)
    {
        if (value == null)
        {
            return null;
        }
        if (!(value instanceof List))
        {
            throw MediaException.badRequest(field + " 必须是数组");
        }
        List<?> raw = (List<?>) value;
        List<String> out = new ArrayList<>(raw.size());
        for (Object item : raw)
        {
            String text = stringValue(item);
            if (text == null)
            {
                throw MediaException.badRequest(field + " 里含空值");
            }
            out.add(text);
        }
        return out;
    }

    /**
     * 标记数组：[{pos, label?, color?, endPos?}]；pos 必填（秒，允许小数）
     *
     * <p>{@code endPos} 可选：缺失或显式 null = 单点标记；给了数字就解析成区间标记
     * （{@code pos < endPos} 与 dur 上限由服务层校验，格式错误仍在这里回 400）。</p>
     */
    public static List<MediaVideoMarker> markersValue(Object value, String field)
    {
        if (value == null)
        {
            return null;
        }
        if (!(value instanceof List))
        {
            throw MediaException.badRequest(field + " 必须是数组");
        }
        List<?> raw = (List<?>) value;
        List<MediaVideoMarker> out = new ArrayList<>(raw.size());
        for (Object item : raw)
        {
            if (!(item instanceof Map))
            {
                throw MediaException.badRequest(field + " 的每一项必须是对象 {pos,label,color}");
            }
            Map<?, ?> map = (Map<?, ?>) item;
            MediaVideoMarker marker = new MediaVideoMarker();
            marker.setPos(decimalValue(map.get("pos"), field + ".pos"));
            marker.setEndPos(optionalDecimalValue(map.get("endPos"), field + ".endPos"));
            marker.setLabel(stringValue(map.get("label")));
            marker.setColor(stringValue(map.get("color")));
            out.add(marker);
        }
        return out;
    }

    /** 秒数（可空）：缺失 / null = null；有值但非数字 400（用于 endPos 这类可选字段） */
    public static BigDecimal optionalDecimalValue(Object value, String field)
    {
        if (value == null)
        {
            return null;
        }
        String text = stringValue(value);
        if (text == null)
        {
            return null;
        }
        try
        {
            return new BigDecimal(text);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest(field + " 必须是数字（秒）: " + text);
        }
    }

    /** 秒数：接受数字或数字字符串；缺失 / 非数字 400 */
    public static BigDecimal decimalValue(Object value, String field)
    {
        if (value == null)
        {
            throw MediaException.badRequest(field + " 必填（秒，可为小数）");
        }
        String text = stringValue(value);
        if (text == null)
        {
            throw MediaException.badRequest(field + " 必填（秒，可为小数）");
        }
        try
        {
            return new BigDecimal(text);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest(field + " 必须是数字（秒）: " + text);
        }
    }
}
