package com.foodmap.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * HTTP API 的统一响应结构。
 *
 * <p>成功和失败响应使用相同字段，便于前端统一拦截和展示。Controller 只创建成功响应，
 * 失败响应由全局异常处理器或 Spring Security 的认证失败处理器创建，禁止各接口自行拼装错误 JSON。</p>
 *
 * @param <T>       业务数据类型
 */
@Data
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private String code;
    private String message;
    private T data;
    private Instant timestamp;

    /**
     * 创建统一的成功响应，并由服务端生成时间戳。
     *
     * @param data 业务响应数据
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "请求成功", data, Instant.now());
    }

    /**
     * 创建不包含附加数据的失败响应。
     *
     * @param code    稳定错误码
     * @param message 可安全返回给调用者的提示，不得包含堆栈或底层数据库信息
     * @return 失败响应
     */
    public static ApiResponse<Void> error(String code, String message) {
        return error(code, message, null);
    }

    /**
     * 创建带结构化错误详情的失败响应，例如参数校验失败字段列表。
     *
     * @param code    稳定错误码
     * @param message 可安全返回给调用者的提示
     * @param data    可公开的结构化错误详情
     * @param <T>     错误详情类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(false, code, message, data, Instant.now());
    }
}
