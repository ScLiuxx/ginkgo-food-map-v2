package com.ginkgo.foodmap.notification.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.notification.dto.NotificationQuery;
import com.ginkgo.foodmap.notification.service.NotificationInboxService;
import com.ginkgo.foodmap.notification.vo.NotificationViews;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 收件箱没有发送HTTP入口，客户端只能读取、标记已读和删除本人的收件箱消息。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/me/notifications")
public class NotificationInboxController {
    private final NotificationInboxService inbox;

    /** 导航只取本人总未读数，不加载消息正文和署名。 */
    @GetMapping("/unread")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> unread() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(inbox.unread()));
    }

    private final com.ginkgo.foodmap.notification.service.NotificationStreamService streams;

    /** 实时通知仅提示刷新，身份取自登录上下文；数据库事务成功提交后才广播。 */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> events(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    org.springframework.security.oauth2.jwt.Jwt jwt) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(streams.open(jwt));
    }

    /** 账号、offset与排序由服务端控制，客户端仅能选择已开放分类和有界分页。 */
    @InitBinder("notificationQuery")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("category", "page", "pageSize");
    }

    /** 返回本人有界分页与未读数，页面读取不会改变阅读状态，私有响应禁止缓存。 */
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationViews.Page>> page(
            @Valid @ModelAttribute NotificationQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(inbox.page(query)));
    }

    /** 单条已读成功返回无响应体的204，归属及首次阅读时间由业务服务保证。 */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable String id) {
        inbox.markRead(id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** 删除只影响本人收件箱；重复成功204，不返回内部来源回执。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable String id) {
        inbox.remove(id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** 首页使用ALL，系统分类使用SYSTEM；校验与列表一致，空收件箱也可幂等调用。 */
    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(@Valid @ModelAttribute NotificationQuery query) {
        inbox.markAllRead(query.getCategory());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
