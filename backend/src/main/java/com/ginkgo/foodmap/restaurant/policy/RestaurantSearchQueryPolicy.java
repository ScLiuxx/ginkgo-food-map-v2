package com.ginkgo.foodmap.restaurant.policy;

/** 查询语义纯规则：字符匹配与默认排序共用码点判断，不承担引擎选择或网络访问。 */
public final class RestaurantSearchQueryPolicy {
    private RestaurantSearchQueryPolicy() { }

    /** 首尾空白忽略，补充平面汉字按一个码点识别；不能用UTF-16字符串长度判断。 */
    public static boolean isSingleHan(String keyword) {
        if (keyword == null) return false;
        String text = keyword.strip();
        return text.codePointCount(0, text.length()) == 1
                && Character.UnicodeScript.of(text.codePointAt(0)) == Character.UnicodeScript.HAN;
    }
}
