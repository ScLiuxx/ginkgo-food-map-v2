package com.ginkgo.foodmap.moderation.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.moderation.dto.ContentReportRequest;
import com.ginkgo.foodmap.moderation.service.ContentReportService;
import com.ginkgo.foodmap.moderation.vo.ContentReportViews;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 点评与回复举报入口；目标类型由路径确定，举报人只取认证身份。 */
@RestController
@RequiredArgsConstructor
public class ContentReportController {
    private final ContentReportService reports;

    /** 举报点评；首次创建返回201，同一用户重复提交返回200和原记录。 */
    @PostMapping("/api/reviews/{id}/reports")
    public ResponseEntity<ApiResponse<ContentReportViews.Submitted>> reportReview(
            @PathVariable String id, @Valid @RequestBody ContentReportRequest request) {
        return response(reports.submit("REVIEW", id, request));
    }

    /** 举报回复；父点评由服务端从回复事实读取，不接受客户端传入。 */
    @PostMapping("/api/review-replies/{id}/reports")
    public ResponseEntity<ApiResponse<ContentReportViews.Submitted>> reportReply(
            @PathVariable String id, @Valid @RequestBody ContentReportRequest request) {
        return response(reports.submit("REPLY", id, request));
    }

    private ResponseEntity<ApiResponse<ContentReportViews.Submitted>> response(
            ContentReportViews.Submitted result) {
        return ResponseEntity.status(result.isDuplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(result));
    }
}
