package com.foodmap.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一异常响应的独立 MVC 测试，不启动数据库和 Redis，确保错误协议可以快速回归。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 验证业务错误码、提示及 HTTP 状态能够完整传递。 */
    @Test
    void shouldHandleBusinessException() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_DATA_CONFLICT"))
                .andExpect(jsonPath("$.message").value("测试数据冲突"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /** 验证 Bean Validation 错误以稳定的字段列表返回。 */
    @Test
    void shouldHandleRequestValidationException() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[0].field").value("username"))
                .andExpect(jsonPath("$.data[0].message").value("用户名不能为空"));
    }

    /** 验证非法 JSON 不会把解析器异常细节返回给客户端。 */
    @Test
    void shouldHandleMalformedJson() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求内容格式错误"));
    }

    /** 验证未知异常只返回固定提示，不泄露异常原文。 */
    @Test
    void shouldHideUnexpectedExceptionDetails() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }

    @RestController
    @RequestMapping("/test")
    private static class TestController {

        @GetMapping("/business")
        void businessFailure() {
            throw new BusinessException(CommonErrorCode.DATA_CONFLICT, "测试数据冲突");
        }

        @PostMapping("/validation")
        void validate(@Valid @RequestBody TestRequest request) {
            // 请求进入方法即表示校验通过；测试只关注进入方法之前的失败响应。
        }

        @GetMapping("/unexpected")
        void unexpectedFailure() {
            throw new IllegalStateException("sensitive internal detail");
        }
    }

    @Data
    private static class TestRequest {

        @NotBlank(message = "用户名不能为空")
        private String username;
    }
}
