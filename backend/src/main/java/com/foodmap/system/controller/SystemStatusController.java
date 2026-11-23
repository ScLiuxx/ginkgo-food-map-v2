package com.foodmap.system.controller;

import com.foodmap.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供无需登录的最小系统状态接口，供前端和部署探针确认后端已启动。
 *
 * <p>该接口只返回非敏感的应用标识和架构信息，不暴露数据库、Redis、版本细节或内部配置。</p>
 */
@RestController
@RequestMapping("/api/public/status")
public class SystemStatusController {

    /**
     * 返回前端初始化所需的非敏感系统状态。
     *
     * @return 统一包装的应用状态
     */
    @GetMapping
    ApiResponse<Map<String, String>> status() {
        return ApiResponse.ok(Map.of(
                "application", "foodmap",
                "architecture", "modular-monolith"
        ));
    }
}
