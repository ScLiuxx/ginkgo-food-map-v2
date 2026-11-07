package com.ginkgo.foodmap.common.exception;

import com.ginkgo.foodmap.common.api.ApiResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.List;

/**
 * 将 Controller 调用链中的异常转换为统一、非敏感的 API 错误响应。
 *
 * <p>数据库异常和未知异常只记录异常类型，避免异常消息或堆栈意外携带密码、Token 等敏感值； 响应不得暴露 SQL、表名、堆栈或内部类名。 Spring Security
 * 过滤器中的认证失败发生在 Controller 之前，不由本处理器接管；JWT 阶段会配置 专用 AuthenticationEntryPoint 和
 * AccessDeniedHandler，并复用 {@link ApiResponse} 格式。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 方法级授权发生在 Controller 调用内部，必须优先返回403，不能被兜底异常误报成500。 */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied() {
        return ResponseEntity.status(403).body(ApiResponse.error("AUTH_FORBIDDEN", "没有权限执行此操作"));
    }

    /** 将可预期业务失败映射为业务错误码指定的 HTTP 状态。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode.getCode(), exception.getMessage()));
    }

    /** 返回字段级校验结果，帮助前端定位输入框；按字段名排序保证响应和测试稳定。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<FieldValidationError>>> handleValidationException(
            MethodArgumentNotValidException exception) {
        List<FieldValidationError> details =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        new FieldValidationError(
                                                error.getField(),
                                                safeValidationMessage(error.getDefaultMessage())))
                        .sorted(Comparator.comparing(FieldValidationError::getField))
                        .toList();
        return error(CommonErrorCode.VALIDATION_FAILED, details);
    }

    /** JSON 语法错误或字段类型无法转换时返回固定提示，不回显解析器内部信息。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage() {
        return error(CommonErrorCode.MALFORMED_REQUEST);
    }

    /** 请求方法不匹配时保持明确的 405 语义。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported() {
        return error(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    /** 数据库唯一约束等并发冲突统一返回 409；具体业务应尽量提前检查并抛出更精确的业务错误码。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException exception) {
        log.warn(
                "Database constraint rejected a request; exceptionType={}",
                exception.getClass().getName());
        return error(CommonErrorCode.DATA_CONFLICT);
    }

    /** 兜底处理未知异常。完整异常只进入服务端日志，客户端仅收到固定提示，避免泄露内部实现。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled request exception; exceptionType={}", exception.getClass().getName());
        return error(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<ApiResponse<Void>> error(CommonErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode.getCode(), errorCode.getDefaultMessage()));
    }

    private static <T> ResponseEntity<ApiResponse<T>> error(CommonErrorCode errorCode, T data) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode.getCode(), errorCode.getDefaultMessage(), data));
    }

    private static String safeValidationMessage(String message) {
        return message == null || message.isBlank() ? "字段值不合法" : message;
    }
}
