package com.foodmap.statistics.storage;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 每日Set只存账号字符串；不存注册或点评计数，不创建并集键，不清空共享Redis。 */
public class ActivityRedisStore implements AutoCloseable {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final LettuceConnectionFactory connections;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> record = new DefaultRedisScript<>();

    public ActivityRedisStore(LettuceConnectionFactory connections) {
        this.connections = connections;
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        record.setLocation(new ClassPathResource("redis/activity-record-user.lua"));
        record.setResultType(Long.class);
    }

    public void record(LocalDate date, long userId) {
        long expires = date.plusDays(90).atStartOfDay(ZONE).toEpochSecond();
        Long result =
                redis.execute(
                        record, List.of(key(date)), Long.toString(userId), Long.toString(expires));
        if (result == null || result < 0) {
            throw new IllegalStateException("Activity set could not be recorded");
        }
    }

    public long count(LocalDate date) {
        Long result = redis.opsForSet().size(key(date));
        if (result == null) {
            throw new IllegalStateException("Missing activity cardinality response");
        }
        return result;
    }

    public long union(LocalDate today, LocalDate startedDate) {
        var keys =
                java.util.stream.IntStream.rangeClosed(0, 6)
                        .filter(offset -> !today.minusDays(offset).isBefore(startedDate))
                        .mapToObj(offset -> key(today.minusDays(offset)))
                        .toList();
        var result = redis.opsForSet().union(keys);
        if (result == null) {
            throw new IllegalStateException("Missing activity union response");
        }
        return result.size();
    }

    /** 实例重启及淘汰会破坏去重完整性；只保留两个非个人运行标识用于保守降级。 */
    public String fingerprint() {
        try (var connection = redis.getConnectionFactory().getConnection()) {
            var server = connection.serverCommands().info("server");
            var stats = connection.serverCommands().info("stats");
            String run = server == null ? null : server.getProperty("run_id");
            String evicted = stats == null ? null : stats.getProperty("evicted_keys");
            if (run == null
                    || !run.matches("[a-fA-F0-9]{40}")
                    || evicted == null
                    || !evicted.matches("[0-9]+")) {
                throw new IllegalStateException("Redis integrity could not be checked");
            }
            return run + ":" + evicted;
        }
    }

    public static String key(LocalDate date) {
        return "foodmap:activity:users:" + date;
    }

    @Override
    public void close() {
        connections.destroy();
    }
}
