package com.foodmap.statistics.service;

import com.foodmap.statistics.entity.ActivityDailySummary;
import com.foodmap.statistics.mapper.ActivityStatisticsMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/** 提交后回调使用独立事务；不复用已经提交的业务事务连接，也不重试结果不明的增量。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 2)
public class ActivityStatisticsWriter {
    private final ActivityStatisticsMapper mapper;

    public Instant start(Instant now) {
        mapper.createMetadata(ActivityStatisticsService.STARTED, now.toString());
        return Instant.parse(mapper.metadata(ActivityStatisticsService.STARTED));
    }

    public void createDay(ActivityDailySummary row) {
        mapper.createDay(row);
    }

    public void increment(LocalDate date, boolean registration) {
        int changed = registration ? mapper.incrementUsers(date) : mapper.incrementReviews(date);
        if (changed != 1) {
            throw new IllegalStateException("Statistics date was not initialized");
        }
    }

    public void markPartial(LocalDate first, LocalDate last) {
        mapper.markPartial(first, last);
    }

    public void synchronizeActive(LocalDate date, long count, Instant at) {
        mapper.synchronizeActive(date, count, at);
    }

    public void saveMetadata(String key, String value) {
        mapper.saveMetadata(key, value);
    }
}
