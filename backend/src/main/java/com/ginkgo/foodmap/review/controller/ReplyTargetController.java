package com.ginkgo.foodmap.review.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.review.service.ReplyTargetService;
import com.ginkgo.foodmap.review.vo.ReplyTargetVo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 本人评论通知的源内容定位入口；没有身份选择参数，也不改变通知已读状态。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/me/review-replies")
public class ReplyTargetController {
    private final ReplyTargetService targets;

    /** 成功返回精确点评及回复快照，私有数据禁止缓存；越权与源内容失效都由服务拒绝。 */
    @GetMapping("/{id}/target")
    public ResponseEntity<ApiResponse<ReplyTargetVo>> target(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(targets.target(id)));
    }
}
