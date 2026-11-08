package com.ginkgo.foodmap.notification.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.notification.dto.AnnouncementPublishRequest;
import com.ginkgo.foodmap.notification.dto.AnnouncementQuery;
import com.ginkgo.foodmap.notification.service.AnnouncementService;
import com.ginkgo.foodmap.notification.vo.AnnouncementViews;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 官方公告管理入口；过滤链及应用服务同时要求当前管理员身份。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/announcements")
public class AdminAnnouncementController {
    private final AnnouncementService announcements;

    @InitBinder("announcementQuery")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("status", "page", "pageSize");
    }

    /** 发布或幂等重试返回同一公告及replayed标志，正文只保存纯文本。 */
    @PostMapping
    public ResponseEntity<ApiResponse<AnnouncementViews.Detail>> publish(
            @Valid @RequestBody AnnouncementPublishRequest request) {
        return response(announcements.publish(request));
    }

    /** 管理员分页查看已发布及撤回记录。 */
    @GetMapping
    public ResponseEntity<ApiResponse<AnnouncementViews.Page>> page(
            @Valid @ModelAttribute AnnouncementQuery query) {
        return response(announcements.page(query));
    }

    /** 管理员可读取撤回历史用于核对，用户详情使用独立权限边界。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AnnouncementViews.Detail>> detail(@PathVariable String id) {
        return response(announcements.adminDetail(id));
    }

    /** 无编辑接口；撤回重复请求保持首次操作人及时间。 */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<ApiResponse<AnnouncementViews.Detail>> withdraw(@PathVariable String id) {
        return response(announcements.withdraw(id));
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
