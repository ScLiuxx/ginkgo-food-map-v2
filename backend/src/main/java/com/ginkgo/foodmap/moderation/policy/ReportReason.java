package com.ginkgo.foodmap.moderation.policy;

/**
 * 第一版举报原因分类。
 *
 * <p>代码与数据库 CHECK 约束保持一致；新增或调整分类必须同时提供迁移，不能只改前端选项。
 */
public enum ReportReason {
    /** 广告、引流或重复刷屏。 */
    SPAM,
    /** 辱骂、人身攻击或恶意挑衅。 */
    ABUSE,
    /** 色情、低俗或露骨内容。 */
    PORNOGRAPHY,
    /** 违法、危险或疑似诈骗内容。 */
    ILLEGAL,
    /** 泄露他人隐私或联系方式。 */
    PRIVACY,
    /** 与店铺或点评主题无关的内容。 */
    IRRELEVANT,
    /** 无法归入上述分类的其他情况。 */
    OTHER;

    /** 严格按大写代码匹配，避免客户端传入近似值后被静默接受。 */
    public static ReportReason of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing report reason");
        }
        return valueOf(value);
    }
}
