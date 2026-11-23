package com.foodmap.statistics.controller;

import com.foodmap.common.api.ApiResponse;
import com.foodmap.security.service.CurrentActorService;
import com.foodmap.statistics.service.ActivityStatisticsService;
import com.foodmap.statistics.vo.ActivityStatisticsVo;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 汇总只读与独立活跃上报；不接收账号、日期、路由或客户端角色。 */
@RestController
@RequiredArgsConstructor
public class ActivityStatisticsController {
    private final ActivityStatisticsService statistics;
    private final CurrentActorService actors;

    @GetMapping("/api/admin/activity-statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ActivityStatisticsVo>> read() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(statistics.read()));
    }

    @PostMapping("/api/me/activity")
    @PreAuthorize("hasRole('USER') and !hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> record() {
        statistics.recordActive(actors.requireActor().getUserId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(null));
    }
}
