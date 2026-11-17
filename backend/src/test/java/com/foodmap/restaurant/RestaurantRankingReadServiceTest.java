package com.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.restaurant.config.RestaurantRankingProperties;
import com.foodmap.restaurant.cache.RestaurantRankingCache;
import com.foodmap.restaurant.cache.RestaurantRankingLocalCache;
import com.foodmap.restaurant.entity.RestaurantRankingSnapshotEntity;
import com.foodmap.restaurant.exception.RestaurantErrorCode;
import com.foodmap.restaurant.mapper.RestaurantRankingSnapshotMapper;
import com.foodmap.restaurant.service.RestaurantRankingReadService;
import com.foodmap.restaurant.support.RestaurantRankingSnapshotCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/** 确定性验证数据年龄、分制、公开字段与故障语义，避免缓存后改变接口含义。 */
class RestaurantRankingReadServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00.123456Z");
    private RestaurantRankingSnapshotMapper mapper;
    private RestaurantRankingReadService service;
    private RestaurantRankingSnapshotEntity row;

    @BeforeEach
    void setup() {
        mapper = mock(RestaurantRankingSnapshotMapper.class);
        var properties = new RestaurantRankingProperties();
        properties.setCaffeineEnabled(false);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new RestaurantRankingReadService(mapper,
                new RestaurantRankingCache(Optional.empty(),
                        new RestaurantRankingSnapshotCodec(JsonMapper.builder().build()),
                        properties, clock, new RestaurantRankingLocalCache(properties, clock)));
        row = new RestaurantRankingSnapshotEntity();
        row.setCampusCode("QINGSHUIHE");
        row.setPeriodType("WEEK");
        row.setWindowEndAt(NOW.minusSeconds(300));
        row.setWindowStartAt(row.getWindowEndAt().minus(Duration.ofHours(168)));
        row.setGeneratedAt(row.getWindowEndAt().plusSeconds(30));
        row.setRulesSnapshot("{\"formatVersion\":1,\"scoreScale\":5}");
        row.setItems("[]");
        when(mapper.selectSnapshot("QINGSHUIHE", "WEEK")).thenReturn(row);
    }

    @Test
    void absentSnapshotRemainsPreparingAndEveryReadCallsMysql() {
        when(mapper.selectSnapshot("QINGSHUIHE", "WEEK")).thenReturn(null);
        assertThat(service.read("QINGSHUIHE", "WEEK").getStatus()).isEqualTo("PREPARING");
        assertThat(service.read("QINGSHUIHE", "WEEK").getItems()).isEmpty();
        verify(mapper, times(2)).selectSnapshot("QINGSHUIHE", "WEEK");
    }

    @Test
    void successfulEmptySnapshotIsReadyAndRetainsMicrosecondTimes() {
        var result = service.read("QINGSHUIHE", "WEEK");
        assertThat(result.getStatus()).isEqualTo("READY");
        assertThat(result.getItems()).isEmpty();
        assertThat(result.getScoreScale()).isEqualTo(5);
        assertThat(result.getWindowEndAt()).isEqualTo(row.getWindowEndAt());
        assertThat(result.getWindowEndAt().getNano()).isEqualTo(123456000);
    }

    @Test
    void projectsOnlyPublicFieldsWithoutDoubleConvertingScoresOrLosingLargeIds() {
        row.setItems("""
                [{"rank":1,"restaurantId":"9007199254740993","name":"中文餐厅🍜",
                  "coverUrl":"/api/restaurants/9007199254740993/photos/1",
                  "tasteScore":4.26,"participantCount":8,"sortingScore":3.999,"secret":"hidden"}]
                """);
        var result = service.read("QINGSHUIHE", "WEEK");
        var item = result.getItems().getFirst();
        assertThat(item.getRestaurantId()).isEqualTo("9007199254740993");
        assertThat(item.getTasteScore()).isEqualByComparingTo("4.3");
        assertThat(item.getParticipantCount()).isEqualTo(8);
        String output = JsonMapper.builder().build().writeValueAsString(result);
        assertThat(output).doesNotContain("sortingScore", "secret", "rulesSnapshot");
    }

    @Test
    void ageUsesCutoffInsteadOfGenerationTimeAndDoesNotReturnExpiredItems() {
        row.setWindowEndAt(NOW.minus(Duration.ofHours(24)).minusNanos(1000));
        row.setWindowStartAt(row.getWindowEndAt().minus(Duration.ofHours(168)));
        row.setGeneratedAt(NOW.minusSeconds(1));
        row.setItems("invalid old payload need not be decoded");
        var result = service.read("QINGSHUIHE", "WEEK");
        assertThat(result.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.getItems()).isEmpty();
    }

    @Test
    void exactlyTwentyFourHoursIsStillWithinTheConfirmedMaximum() {
        row.setWindowEndAt(NOW.minus(Duration.ofHours(24)));
        row.setWindowStartAt(row.getWindowEndAt().minus(Duration.ofHours(168)));
        row.setGeneratedAt(row.getWindowEndAt());
        assertThat(service.read("QINGSHUIHE", "WEEK").getStatus()).isEqualTo("READY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"QINGSHUIHE ", "qingshuihe", "OTHER", "", "QINGSHUIHE' OR 1=1"})
    void rejectsUnknownCampusBeforeDatabase(String campus) {
        assertThatThrownBy(() -> service.read(campus, "WEEK"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(RestaurantErrorCode.INVALID_INPUT));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsMissingOrInvalidPeriodBeforeDatabase() {
        for (String period : new String[] {null, "week", "DAY", "MONTH "}) {
            assertThatThrownBy(() -> service.read("QINGSHUIHE", period)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.read(null, "WEEK")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void databaseFailureIsUnavailableAndDoesNotExposeSql() {
        when(mapper.selectSnapshot("QINGSHUIHE", "WEEK"))
                .thenThrow(new DataAccessResourceFailureException("private database diagnostic"));
        unavailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "broken", "{\"formatVersion\":2,\"scoreScale\":5}",
            "{\"formatVersion\":1,\"scoreScale\":10}", "{\"formatVersion\":1.0,\"scoreScale\":5}"})
    void rejectsUnsupportedOrMalformedRuleFormat(String rules) {
        row.setRulesSnapshot(rules);
        unavailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "broken", "[null]",
            "[{\"rank\":2,\"restaurantId\":\"1\",\"name\":\"店\",\"tasteScore\":4,\"participantCount\":3}]",
            "[{\"rank\":1,\"restaurantId\":1,\"name\":\"店\",\"tasteScore\":4,\"participantCount\":3}]",
            "[{\"rank\":1,\"restaurantId\":\"01\",\"name\":\"店\",\"tasteScore\":4,\"participantCount\":3}]",
            "[{\"rank\":1,\"restaurantId\":\"9223372036854775808\",\"name\":\"店\",\"tasteScore\":4,\"participantCount\":3}]",
            "[{\"rank\":1,\"restaurantId\":\"1\",\"name\":\" \",\"tasteScore\":4,\"participantCount\":3}]",
            "[{\"rank\":1,\"restaurantId\":\"1\",\"name\":\"店\",\"tasteScore\":8,\"participantCount\":3}]",
            "[{\"rank\":1,\"restaurantId\":\"1\",\"name\":\"店\",\"tasteScore\":3.99,\"participantCount\":3}]",
            "[{\"rank\":1,\"restaurantId\":\"1\",\"name\":\"店\",\"tasteScore\":\"4\",\"participantCount\":3}]",
            "[{\"rank\":1,\"restaurantId\":\"1\",\"name\":\"店\",\"tasteScore\":4,\"participantCount\":2}]",
            "[{\"rank\":1,\"restaurantId\":\"1\",\"name\":\"店\",\"tasteScore\":4,\"participantCount\":3,\"coverUrl\":\"https://example.test/private\"}]",
            "[{\"rank\":1,\"restaurantId\":\"1\",\"name\":\"店\",\"tasteScore\":4,\"participantCount\":3,\"coverUrl\":\"/api/restaurants/2/photos/1\"}]"})
    void rejectsInvalidItemsAsAWhole(String items) {
        row.setItems(items);
        unavailable();
    }

    @Test
    void rejectsDuplicateRestaurantIds() {
        row.setItems("""
                [{"rank":1,"restaurantId":"1","name":"店","tasteScore":4,"participantCount":3},
                 {"rank":2,"restaurantId":"1","name":"店","tasteScore":4,"participantCount":3}]
                """);
        unavailable();
    }

    @Test
    void rejectsWrongWindowAndFutureGeneration() {
        row.setWindowStartAt(row.getWindowStartAt().plusSeconds(1));
        unavailable();
        row.setWindowStartAt(row.getWindowEndAt().minus(Duration.ofHours(168)));
        row.setGeneratedAt(NOW.plusNanos(1000));
        unavailable();
    }

    private void unavailable() {
        assertThatThrownBy(() -> service.read("QINGSHUIHE", "WEEK"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(RestaurantErrorCode.RANKING_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo("榜单暂时不可用，请稍后重试");
                });
    }
}
