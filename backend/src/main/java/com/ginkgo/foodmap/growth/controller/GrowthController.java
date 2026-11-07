package com.ginkgo.foodmap.growth.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.growth.service.GrowthQueryService;
import com.ginkgo.foodmap.growth.vo.GrowthSummaryVo;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 本人成长摘要入口；保留现有JWT、数据库账号及Redis会话校验，不开放发奖或撤销端点。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class GrowthController {
    private final GrowthQueryService growth;
    private final CurrentActorService actors;

    /** GET不创建账户，认证身份为唯一查询对象；响应禁止缓存，避免账号切换混用经验。 */
    @GetMapping("/api/me/growth")
    public ResponseEntity<ApiResponse<GrowthSummaryVo>> summary() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(growth.summary(actors.requireActor().getUserId())));
    }
}
