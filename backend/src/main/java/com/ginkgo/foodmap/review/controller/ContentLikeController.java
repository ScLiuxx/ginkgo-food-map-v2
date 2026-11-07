package com.ginkgo.foodmap.review.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.review.service.ContentLikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 状态写入只接受路径目标；用户身份来自服务认证，所有响应禁止缓存。 */
@RestController
@RequiredArgsConstructor
public class ContentLikeController {
    private final ContentLikeService likes;

    /** 游客可读公开计数；携带有效认证时同时返回本人的点赞状态。 */
    @GetMapping("/api/content-likes")
    public ResponseEntity<?> batch(@RequestParam String type, @RequestParam String ids) {
        return ok(likes.batch(type, ids));
    }

    /** 显式设为已赞，重复提交不会反转状态或重复产生奖励与通知。 */
    @PutMapping("/api/reviews/{id}/like")
    public ResponseEntity<?> likeReview(@PathVariable String id) {
        return ok(likes.set("REVIEW", id, true));
    }

    /** 取消点评点赞，历史通知和已发放经验继续保留。 */
    @DeleteMapping("/api/reviews/{id}/like")
    public ResponseEntity<?> unlikeReview(@PathVariable String id) {
        return ok(likes.set("REVIEW", id, false));
    }

    /** 点赞回复，仅发送首次获赞通知，不产生经验奖励。 */
    @PutMapping("/api/review-replies/{id}/like")
    public ResponseEntity<?> likeReply(@PathVariable String id) {
        return ok(likes.set("REPLY", id, true));
    }

    /** 取消回复点赞，使用与点评相同的身份和归属校验。 */
    @DeleteMapping("/api/review-replies/{id}/like")
    public ResponseEntity<?> unlikeReply(@PathVariable String id) {
        return ok(likes.set("REPLY", id, false));
    }

    /** 当前有效获赞合计只查询认证本人，不接受客户端传入用户编号。 */
    @GetMapping("/api/me/received-likes")
    public ResponseEntity<?> received() {
        return ok(likes.received());
    }

    /** 按首次点赞关系定位本人被赞内容，失效或越权由服务统一隐藏。 */
    @GetMapping("/api/me/received-likes/{id}/target")
    public ResponseEntity<?> target(@PathVariable String id) {
        return ok(likes.target(id));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
}
