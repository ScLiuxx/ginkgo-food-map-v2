package com.ginkgo.foodmap.user.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import com.ginkgo.foodmap.user.dto.UserProfileUpdateRequest;
import com.ginkgo.foodmap.user.service.UserProfileService;
import com.ginkgo.foodmap.user.vo.UserProfileVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 当前账号的资料入口；URL、查询参数和JSON均不能指定其他账号。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/me/profile")
public class UserProfileController {
    private final UserProfileService profiles;
    private final CurrentActorService actors;

    @GetMapping
    public ResponseEntity<ApiResponse<UserProfileVo>> get() {
        return response(profiles.get(actors.requireActor().getUserId()));
    }
    @PatchMapping
    public ResponseEntity<ApiResponse<UserProfileVo>> update(@Valid @RequestBody UserProfileUpdateRequest request) {
        return response(profiles.update(actors.requireActor().getUserId(), request.getBio()));
    }
    private ResponseEntity<ApiResponse<UserProfileVo>> response(UserProfileVo value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
}
