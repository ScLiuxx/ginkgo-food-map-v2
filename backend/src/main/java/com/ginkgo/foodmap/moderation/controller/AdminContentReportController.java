package com.ginkgo.foodmap.moderation.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.moderation.dto.ContentReportDismissRequest;
import com.ginkgo.foodmap.moderation.dto.ContentReportQuery;
import com.ginkgo.foodmap.moderation.service.ContentReportAdminService;
import com.ginkgo.foodmap.moderation.vo.ContentReportViews;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 管理员举报查询与驳回协议入口；权限由管理安全链和服务注解双重校验。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/content-reports")
public class AdminContentReportController {
    private final ContentReportAdminService service;

    /** 有界分页；无结果仍返回200和真实总数，不返回其他状态的内容。 */
    @GetMapping
    public ResponseEntity<ApiResponse<ContentReportViews.AdminPage>> page(
            @Valid @ModelAttribute ContentReportQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.page(query)));
    }

    /** 详情包含提交时的正文快照和举报人，仅管理端可读。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ContentReportViews.AdminItem>> detail(
            @PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.detail(id)));
    }

    /** 驳回同一内容下的全部待处理举报；备注选填，首次和重复驳回均返回200。 */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<ApiResponse<ContentReportViews.Dismissed>> dismiss(
            @PathVariable String id,
            @Valid @RequestBody(required = false) ContentReportDismissRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.dismiss(id, request)));
    }
}
