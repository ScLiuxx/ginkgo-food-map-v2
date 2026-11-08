package com.ginkgo.foodmap.notification.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.notification.service.AnnouncementService;
import com.ginkgo.foodmap.notification.vo.AnnouncementViews;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 用户公告详情：撤回或本人删除后404；GET不改变阅读状态。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/me/announcements")
public class AnnouncementController {
    private final AnnouncementService announcements;

    /** 正文为纯文本，前端不得作为HTML解释；阅读状态通过收件箱已读接口显式变更。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AnnouncementViews.Detail>> detail(@PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(announcements.detail(id)));
    }
}
