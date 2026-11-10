package com.ginkgo.foodmap.moderation.policy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.moderation.exception.ModerationErrorCode;

import java.math.BigInteger;

/**
 * 举报提交的输入规则。
 *
 * <p>所有校验在写库前完成，避免依赖数据库约束后把唯一键或 CHECK 失败误报成409；正文快照由
 * review 模块提供，不在这里接受客户端上传的内容。
 */
public final class ContentReportPolicy {
    private static final int DETAIL_MAX_CODE_POINTS = 200;

    private ContentReportPolicy() {}

    /** 只接受正数且在BIGINT范围内的内容编号，禁止把路径参数直接拼入SQL。 */
    public static long targetId(String value) {
        if (value == null
                || !value.matches("[1-9][0-9]{0,18}")
                || new BigInteger(value).bitLength() > 63) {
            throw new BusinessException(ModerationErrorCode.INVALID_INPUT);
        }
        return Long.parseLong(value);
    }

    /** 原因必须是已确认的固定分类，文字说明按RISK-016保持选填。 */
    public static ReportReason reason(String value) {
        try {
            return ReportReason.of(value);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ModerationErrorCode.INVALID_INPUT);
        }
    }

    /** 说明去首尾空白后为空视为未填写；最多200个码点，与数据库列宽一致。 */
    public static String detail(String value) {
        return optionalText(value);
    }

    /** 管理端举报编号沿用内容编号规则；非法编号统一返回400而不是500。 */
    public static long reportId(String value) {
        return targetId(value);
    }

    /** 驳回备注与举报说明使用相同的长度和空白规则，空白备注不写入数据库。 */
    public static String decisionNote(String value) {
        return optionalText(value);
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > DETAIL_MAX_CODE_POINTS) {
            throw new BusinessException(ModerationErrorCode.INVALID_INPUT);
        }
        return trimmed;
    }
}
