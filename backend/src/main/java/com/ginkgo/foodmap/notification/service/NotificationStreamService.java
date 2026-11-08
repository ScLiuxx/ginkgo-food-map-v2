package com.ginkgo.foodmap.notification.service;

import com.ginkgo.foodmap.security.authentication.SessionJwtAuthenticationConverter;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单实例维护私有SSE连接；只发送刷新提示，不保存消息正文或完整令牌文本。 */
@Service
@RequiredArgsConstructor
public class NotificationStreamService {
    private final SessionJwtAuthenticationConverter authentication;
    private final ConcurrentHashMap<SseEmitter, Client> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    private static final class Client {
        final long owner;
        final Jwt jwt;
        final AtomicBoolean dirty = new AtomicBoolean();
        final AtomicBoolean sending = new AtomicBoolean();

        Client(long owner, Jwt jwt) {
            this.owner = owner;
            this.jwt = jwt;
        }
    }

    /** 从已认证JWT确定用户，每用户最多5连接、全局1000连接，避免无限占用资源。 */
    public synchronized SseEmitter open(Jwt jwt) {
        long owner = Long.parseLong(jwt.getSubject());
        if (clients.size() >= 1000
                || clients.values().stream().filter(c -> c.owner == owner).count() >= 5) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        var emitter = new SseEmitter(300_000L);
        var client = new Client(owner, jwt);
        clients.put(emitter, client);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(
                () -> {
                    clients.remove(emitter);
                    emitter.complete();
                });
        emitter.onError(error -> clients.remove(emitter));
        // 心跳每15秒复核会话；每连接最多一个发送任务，慢客户端不会积压线程。
        var task =
                timer.scheduleWithFixedDelay(
                        () -> Thread.startVirtualThread(() -> tick(emitter, client)),
                        0,
                        15,
                        TimeUnit.SECONDS);
        emitter.onCompletion(() -> task.cancel(false));
        emitter.onTimeout(() -> task.cancel(false));
        emitter.onError(error -> task.cancel(false));
        return emitter;
    }

    /** 提交线程仅设置脏标志，网络发送由虚拟线程完成，慢连接不阻塞业务事务。 */
    public void changed(long owner) {
        clients.forEach(
                (emitter, client) -> {
                    if ((owner == 0 || client.owner == owner)
                            && client.dirty.compareAndSet(false, true)) {
                        Thread.startVirtualThread(() -> tick(emitter, client));
                    }
                });
    }

    private void tick(SseEmitter emitter, Client client) {
        if (!client.sending.compareAndSet(false, true)) {
            return;
        }
        try {
            synchronized (client) {
                if (!clients.containsKey(emitter)) {
                    return;
                }
                try {
                    // 过期、撤销、禁用或凭证版本变化时关闭；认证基础设施故障同样拒绝推送。
                    if (client.jwt.getExpiresAt() == null
                            || !Instant.now().isBefore(client.jwt.getExpiresAt())) {
                        close(emitter);
                        return;
                    }
                    authentication.convert(client.jwt);
                    boolean changed = client.dirty.getAndSet(false);
                    emitter.send(
                            SseEmitter.event()
                                    .name(changed ? "notifications-changed" : "sync")
                                    .data("{}"));
                } catch (Exception error) {
                    close(emitter);
                }
            }
        } finally {
            client.sending.set(false);
        }
    }

    private void close(SseEmitter emitter) {
        clients.remove(emitter);
        emitter.complete();
    }

    /** 应用关闭时释放连接和定时器，不保留待补发事件。 */
    @PreDestroy
    public void shutdown() {
        timer.shutdownNow();
        clients.keySet().forEach(this::close);
    }
}
