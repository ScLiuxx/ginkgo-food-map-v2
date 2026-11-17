package com.foodmap.user.controller;

import com.foodmap.user.dto.UserAvatarUpdateRequest;
import com.foodmap.user.service.UserAvatarService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 头像只有本人可写；公开读取限定到用户当前绑定头像，不能猜测临时文件或旧头像ID。 */
@RestController
@RequiredArgsConstructor
public class UserAvatarController {
    private final UserAvatarService avatars;

    /** 保存成功无正文；头像与其他资料独立更新，避免覆盖同时保存的简介。 */
    @PutMapping("/api/me/avatar")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> update(@Valid @RequestBody UserAvatarUpdateRequest request) {
        avatars.update(request.getFileId());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** 没有自定义头像时204，由页面显示用户名首字；不生成或存储默认图片。 */
    @GetMapping("/api/users/{userId}/avatar")
    public ResponseEntity<byte[]> read(@PathVariable String userId) {
        byte[] image = avatars.read(userId);
        if (image == null) {
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .body(image);
    }
}
