package com.foodmap.user.controller;

import com.foodmap.common.exception.GlobalExceptionHandler;
import com.foodmap.user.service.EmailCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证码 Controller 的协议和参数校验测试。
 */
class AuthEmailCodeControllerTest {

    private EmailCodeService emailCodeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        emailCodeService = mock(EmailCodeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthEmailCodeController(emailCodeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 验证注册验证码接口只返回统一成功响应，并把邮箱交给业务 Service。 */
    @Test
    void shouldSendRegistrationCode() throws Exception {
        mockMvc.perform(post("/auth/email/code/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@std.uestc.edu.cn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"));

        verify(emailCodeService).sendRegistrationCode("student@std.uestc.edu.cn");
    }

    /** 验证密码重置接口使用独立 Service 方法，并保持相同成功响应。 */
    @Test
    void shouldSendPasswordResetCode() throws Exception {
        mockMvc.perform(post("/auth/email/code/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@std.uestc.edu.cn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(emailCodeService).sendPasswordResetCode("student@std.uestc.edu.cn");
    }

    /** 验证非法邮箱在 Controller 层直接返回统一参数错误。 */
    @Test
    void shouldRejectMalformedEmail() throws Exception {
        mockMvc.perform(post("/auth/email/code/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[0].field").value("email"));
    }
}
