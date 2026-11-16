package com.ginkgo.foodmap.review.exception;

import com.ginkgo.foodmap.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

/** 文本发布的可公开错误；不将数据库信息或用户正文写入错误提示。 */
@Getter
@RequiredArgsConstructor
public enum ReviewErrorCode implements ErrorCode {
    NOT_FOUND("REVIEW_NOT_FOUND", HttpStatus.NOT_FOUND, "点评不存在或已删除"),
    FORBIDDEN("REVIEW_FORBIDDEN", HttpStatus.FORBIDDEN, "只能删除自己发布的点评"),
    REQUEST_DELETED("REVIEW_REQUEST_DELETED", HttpStatus.CONFLICT, "该次发布的点评已删除，请勿重放旧请求"),
    INVALID_CURSOR("REVIEW_INVALID_CURSOR", HttpStatus.BAD_REQUEST, "历史游标无效或不属于该餐厅和作者，请刷新点评列表"),
    INVALID_QUERY("REVIEW_INVALID_QUERY", HttpStatus.BAD_REQUEST, "资源ID必须为规范正整数"),
    INVALID_INPUT("REVIEW_INVALID_INPUT", HttpStatus.BAD_REQUEST, "请检查UUID、四维半分、推荐及5–500字正文"),
    REQUEST_CONFLICT("REVIEW_REQUEST_CONFLICT", HttpStatus.CONFLICT, "本次提交编号已用于不同内容，请核对提交结果"),
    SUBMISSION_STATE_CONFLICT(
            "REVIEW_SUBMISSION_STATE_CONFLICT", HttpStatus.CONFLICT, "申请类型、归属或点评状态不支持此操作"),
    BUSY("REVIEW_TEMPORARILY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "操作暂时繁忙，请稍后重试");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
