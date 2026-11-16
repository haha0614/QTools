package com.qtools.shortlink.support;

public final class ShortCode {
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    public static final long FIRST_SIX_DIGIT_ID = 916132832L; // 62^5 = 916132832，最小 6 位
    /**
     * 编码，将id转为Base62
     * @param id 短链id
     * @return Base62编码结果
     */
    public static String encode(long id) {
        if (id == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            int index = (int) (id % 62);
            sb.append(ALPHABET[(int) index]);
            id = id / 62;
        }
        return sb.reverse().toString(); // 返回反转后的结果
    }
}
