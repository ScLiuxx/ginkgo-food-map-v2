package com.ginkgo.foodmap.community.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.community.dto.FollowListQuery;
import com.ginkgo.foodmap.community.policy.FollowIdentifiers;
import com.ginkgo.foodmap.community.service.FollowService;
import com.ginkgo.foodmap.community.vo.FollowViews;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 所有关注及名单接口要求登录；写入身份只从认证上下文取，不接受用户指定actor。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class FollowController {
    private final FollowService follows;
    private final CurrentActorService actors;

    @InitBinder("followListQuery")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("page", "pageSize");
    }

    @GetMapping("/api/users/{id}/social")
    public ResponseEntity<ApiResponse<FollowViews.Summary>> summary(@PathVariable String id) {
        return response(follows.summary(actor(), id(id)));
    }

    @PutMapping("/api/users/{id}/follow")
    public ResponseEntity<ApiResponse<FollowViews.Summary>> follow(@PathVariable String id) {
        return response(follows.set(actor(), id(id), true));
    }

    @DeleteMapping("/api/users/{id}/follow")
    public ResponseEntity<ApiResponse<FollowViews.Summary>> unfollow(@PathVariable String id) {
        return response(follows.set(actor(), id(id), false));
    }

    @GetMapping("/api/users/{id}/following")
    public ResponseEntity<ApiResponse<FollowViews.Page>> following(
            @PathVariable String id, @Valid @ModelAttribute FollowListQuery query) {
        return response(follows.list(actor(), id(id), "FOLLOWING", query));
    }

    @GetMapping("/api/users/{id}/followers")
    public ResponseEntity<ApiResponse<FollowViews.Page>> followers(
            @PathVariable String id, @Valid @ModelAttribute FollowListQuery query) {
        return response(follows.list(actor(), id(id), "FOLLOWERS", query));
    }

    private long actor() {
        return actors.requireActor().getUserId();
    }

    private long id(String value) {
        return FollowIdentifiers.id(value);
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
}
