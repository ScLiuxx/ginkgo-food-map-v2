package com.foodmap.statistics.exception;

import com.foodmap.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ActivityStatisticsErrorCode implements ErrorCode {
    UNAVAILABLE(
            "ACTIVITY_STATISTICS_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "使用统计暂时不可用，请稍后重试");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
