package com.ginkgo.foodmap.review.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.review.dto.*;
import com.ginkgo.foodmap.review.service.ReplyReadService;
import com.ginkgo.foodmap.review.service.ReplyWriteService;
import com.ginkgo.foodmap.review.vo.*;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 单层回复协议入口，GET公开、POST由认证链及服务双重保护。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reviews/{reviewId}/replies")
public class ReplyController {
    private final ReplyReadService reader;
    private final ReplyWriteService writer;

    /** 只绑定分页参数，拒绝客户端控制内部查询条件。 */
    @InitBinder("replyListQuery")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("cursor", "pageSize");
    }

    /** 第一次发布201，同内容重放200；均禁止缓存回执。 */
    @PostMapping
    public ResponseEntity<ApiResponse<ReplyPublishVo>> publish(
            @PathVariable String reviewId, @RequestBody ReplyPublishRequest input) {
        var result = writer.publish(reviewId, input);
        return ResponseEntity.status(result.isReplayed() ? 200 : 201)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(result));
    }

    /** 游客可阅读，但每次重新验证点评及父餐厅的可见性。 */
    @GetMapping
    public ResponseEntity<ApiResponse<ReplyPageVo>> list(
            @PathVariable String reviewId, @Valid @ModelAttribute ReplyListQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(reader.list(reviewId, query)));
    }
}
