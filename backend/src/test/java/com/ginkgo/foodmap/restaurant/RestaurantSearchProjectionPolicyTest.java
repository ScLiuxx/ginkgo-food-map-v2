package com.ginkgo.foodmap.restaurant;

import com.ginkgo.foodmap.restaurant.entity.RestaurantSearchSource;
import com.ginkgo.foodmap.restaurant.policy.RestaurantSearchProjectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

/** 纯业务/序列化边界：保留未知与真实零、原始累计精度、Long身份及最小删除墓碑。 */
class RestaurantSearchProjectionPolicyTest {
    static RestaurantSearchSource regular() {
        var row = new RestaurantSearchSource();
        row.setId(9007199254740993L); row.setName("测试商户");
        row.setCategoryCode("HOT_POT"); row.setCategoryName("火锅串串"); row.setRestaurantType("REGULAR");
        row.setPriceKind("UNKNOWN"); row.setBusinessStatus("OPERATING"); row.setNeedsLocation(true);
        row.setOverallScoreSum(BigDecimal.ZERO);
        return row;
    }

    @Test void preservesUnknownsEmptyTagsAndExactLongIdentity() {
        var result = RestaurantSearchProjectionPolicy.assemble(regular(), List.of(), null, Long.MAX_VALUE);
        assertThat(result.documentId()).isEqualTo("9007199254740993");
        assertThat(result.getId()).isEqualTo(9007199254740993L);
        assertThat(result.getProjectionVersion()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.getTags()).isEmpty();
        assertThat(result.getLocation()).isNull();
        assertThat(result.getPricePerPerson()).isNull();
        assertThat(result.getSupportsDineIn()).isNull();
        assertThat(result.getSupportsDelivery()).isNull();
        assertThat(result.getRatingScore()).isNull();
        assertThat(result.getRecommendRate()).isNull();
        assertThat(result.getParticipantCount()).isZero();
        assertThat(result.getReviewCount()).isZero();
    }

    @Test void keepsRawRatioWithoutPageRoundingAndCopiesOrderedTags() {
        var row = regular(); row.setParticipantCount(3); row.setReviewCount(7);
        row.setRecommendCount(1); row.setOverallScoreSum(new BigDecimal("23.12"));
        row.setNeedsLocation(false); row.setLatitude(new BigDecimal("30.750001"));
        row.setLongitude(new BigDecimal("103.930002")); row.setPriceKind("EXACT"); row.setPricePerPerson(1);
        row.setSupportsDineIn(false); row.setSupportsDelivery(true);
        var tags = new ArrayList<>(List.of("学生聚餐", "夜宵"));
        var result = RestaurantSearchProjectionPolicy.assemble(row, tags, "9223372036854775807", 4);
        tags.clear();
        assertThat(result.getTags()).containsExactly("学生聚餐", "夜宵");
        assertThat(result.getRatingScore()).isCloseTo(23.12 / 3, within(1e-14)).isNotEqualTo(7.71);
        assertThat(result.getRecommendRate()).isEqualTo(1.0 / 3);
        assertThat(result.getReviewCount()).isEqualTo(7);
        assertThat(result.getCoverPhotoId()).isEqualTo("9223372036854775807");
        assertThat(result.getLocation().getLat()).isEqualByComparingTo("30.750001");
        assertThat(result.getLocation().getLon()).isEqualByComparingTo("103.930002");
        assertThat(result.getSupportsDineIn()).isFalse();
        assertThat(result.getSupportsDelivery()).isTrue();
    }

    @Test void canteenHasNoCategoryAndRealZeroScoresArePreserved() {
        var row = regular(); row.setRestaurantType("CANTEEN");
        row.setCategoryCode(null); row.setCategoryName(null); row.setParticipantCount(1); row.setReviewCount(2);
        var result = RestaurantSearchProjectionPolicy.assemble(row, List.of(), null, 1);
        assertThat(result.getCategoryCode()).isNull(); assertThat(result.getCategoryName()).isNull();
        assertThat(result.getRatingScore()).isZero(); assertThat(result.getRecommendRate()).isZero();
    }

    @Test void tombstoneSerializesOnlyThreeFieldsAndNeverCarriesStalePublicData() {
        var row = regular(); row.setDeleted(true);
        var doc = RestaurantSearchProjectionPolicy.assemble(row, List.of("旧标签"), "801", 2);
        Map<?, ?> body = new ObjectMapper().convertValue(doc, Map.class);
        assertThat(body.keySet().equals(Set.of("id", "deleted", "projectionVersion"))).isTrue();
        assertThat(body.get("deleted")).isEqualTo(true);
        assertThat(body.get("id")).isEqualTo(9007199254740993L);
    }

    @Test void sourceFieldsMatchVersionedMappingWhitelistWithNoMetadataOrPrivateFields() throws Exception {
        var row = regular(); row.setSupportsDineIn(true); row.setSupportsDelivery(false);
        row.setNeedsLocation(false); row.setLatitude(new BigDecimal("3")); row.setLongitude(new BigDecimal("136"));
        row.setPriceKind("ESTIMATE"); row.setPricePerPerson(5000); row.setAddress("示意地址");
        row.setParticipantCount(1); row.setReviewCount(1);
        var json = new ObjectMapper();
        Map<?, ?> body = json.convertValue(RestaurantSearchProjectionPolicy.assemble(row, List.of("标签"), "801", 3), Map.class);
        try (var stream = getClass().getResourceAsStream("/elasticsearch/restaurant-search-v002.json")) {
            Map<?, ?> spec = json.readValue(stream, Map.class);
            Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) spec.get("mappings")).get("properties");
            assertThat(body.keySet().equals(properties.keySet())).isTrue();
        }
        assertThat(body.get("id")).isInstanceOf(Long.class);
        assertThat(body.get("projectionVersion")).isInstanceOf(Number.class);
        assertThat(body.get("coverPhotoId")).isInstanceOf(String.class);
    }

    @ParameterizedTest @MethodSource("invalidSources")
    void rejectsBrokenSourceContracts(Consumer<RestaurantSearchSource> mutation) {
        var row = regular(); mutation.accept(row);
        assertThatIllegalArgumentException().isThrownBy(() -> RestaurantSearchProjectionPolicy.assemble(row, List.of(), null, 1));
    }

    static Stream<Consumer<RestaurantSearchSource>> invalidSources() {
        return Stream.of(row -> row.setId(0L), row -> row.setName(" "), row -> row.setName("长".repeat(41)),
                row -> row.setCategoryCode(null), row -> row.setCategoryName(null),
                row -> row.setRestaurantType("CANTEEN"), row -> row.setRestaurantType(null),
                row -> row.setPriceKind(null), row -> row.setPricePerPerson(0),
                row -> { row.setPriceKind("EXACT"); row.setPricePerPerson(5001); },
                row -> row.setBusinessStatus("UNKNOWN"), row -> row.setLatitude(BigDecimal.TEN),
                row -> row.setNeedsLocation(false),
                row -> { row.setNeedsLocation(false); row.setLatitude(new BigDecimal("2.999999")); row.setLongitude(new BigDecimal("103")); },
                row -> row.setReviewCount(-1), row -> row.setParticipantCount(1),
                row -> row.setRecommendCount(1), row -> row.setOverallScoreSum(BigDecimal.ONE),
                row -> row.setOverallScoreSum(null));
    }

    @Test void rejectsInvalidVersionsCoverIdsAndTags() {
        for (long version : new long[]{0, -1})
            assertThatIllegalArgumentException().isThrownBy(() -> RestaurantSearchProjectionPolicy.assemble(regular(), List.of(), null, version));
        for (String cover : List.of("0", "01", "9223372036854775808", "file/key.jpg"))
            assertThatIllegalArgumentException().isThrownBy(() -> RestaurantSearchProjectionPolicy.assemble(regular(), List.of(), cover, 1));
        for (List<String> tags : List.of(List.of(""), List.of("重复", "重复"), List.of("长".repeat(81)),
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9")))
            assertThatIllegalArgumentException().isThrownBy(() -> RestaurantSearchProjectionPolicy.assemble(regular(), tags, null, 1));
    }
}
