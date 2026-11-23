package com.foodmap.user.controller;

import com.foodmap.common.exception.GlobalExceptionHandler;
import com.foodmap.user.service.UserRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册 Controller 协议测试，确保敏感业务逻辑只进入 Service。
 */
class AuthControllerTest {

    private UserRegistrationService userRegistrationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userRegistrationService = mock(UserRegistrationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(userRegistrationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 完整请求返回 201，且不会自动返回 Token。 */
    @Test
    void shouldRegisterWithoutReturningToken() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@std.uestc.edu.cn",
                                  "verificationCode": "123456",
                                  "username": "foodmap_user",
                                  "password": "Foodmap2026"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());

        verify(userRegistrationService).register(any());
    }

    /** 验证码格式错误在 Controller 层返回统一字段错误，不进入注册 Service。 */
    @Test
    void shouldRejectMalformedVerificationCode() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@std.uestc.edu.cn",
                                  "verificationCode": "12ab",
                                  "username": "foodmap_user",
                                  "password": "Foodmap2026"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[0].field").value("verificationCode"));

        verifyNoInteractions(userRegistrationService);
    }
}
