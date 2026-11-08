package com.ginkgo.foodmap.review.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.review.dto.ReviewHistoryQuery;
import com.ginkgo.foodmap.review.dto.ReviewListQuery;
import com.ginkgo.foodmap.review.dto.ReviewPublishRequest;
import com.ginkgo.foodmap.review.service.ReviewReadService;
import com.ginkgo.foodmap.review.service.ReviewWriteService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立窗口点评入口；公开读取与认证发布复用既有点评白名单，拒绝假餐厅身份。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/canteen-windows/{windowId}/reviews")
public class WindowReviewController {
    private final ReviewReadService reads;
    private final ReviewWriteService writes;

    @InitBinder("reviewListQuery")
    void listBinder(WebDataBinder binder) {
        binder.setAllowedFields("page", "pageSize", "sort");
    }

    @InitBinder("reviewHistoryQuery")
    void historyBinder(WebDataBinder binder) {
        binder.setAllowedFields("cursor", "pageSize");
    }

    /** 每位作者仅显示最新有效点评，历史按游标读取。 */
    @GetMapping
    public ResponseEntity<?> list(
            @PathVariable String windowId, @Valid @ModelAttribute ReviewListQuery query) {
        return response(reads.listWindow(windowId, query));
    }

    /** 游标与所属窗口/作者绑定。 */
    @GetMapping("/authors/{author}/history")
    public ResponseEntity<?> history(
            @PathVariable String windowId,
            @PathVariable String author,
            @Valid @ModelAttribute ReviewHistoryQuery query) {
        return response(reads.historyWindow(windowId, author, query));
    }

    /** 真实账号身份、窗口营业和图片归属均由应用服务验证。 */
    @PostMapping
    public ResponseEntity<?> publish(
            @PathVariable String windowId, @Valid @RequestBody ReviewPublishRequest request) {
        return response(writes.publishWindow(windowId, request));
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
