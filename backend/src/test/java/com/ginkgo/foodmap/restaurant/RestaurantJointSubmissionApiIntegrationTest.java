package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ginkgo.foodmap.file.storage.OssStorageClient;
import com.ginkgo.foodmap.restaurant.dto.RestaurantChangeRequest;
import com.ginkgo.foodmap.review.mapper.ReviewSubmissionMapper;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 随机回环MySQL库验证联合提交、真实文件绑定、数据库回滚和并发；不会访问开发库或OSS。 */
@EnabledIfSystemProperty(named = "restaurant.integration", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.datasource.hikari.maximum-pool-size=6"
        })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
class RestaurantJointSubmissionApiIntegrationTest {
    private static final String ENDPOINT = "/api/restaurant-submissions";
    private static String database;
    private static boolean created;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtEncoder encoder;
    @Autowired private AuthenticationProperties authentication;
    @MockitoBean private LoginSessionService sessions;
    @MockitoBean private OssStorageClient storage;
    @MockitoSpyBean private ReviewSubmissionMapper submissionMapper;
    private String owner;
    private String other;
    private String admin;

    /** 只允许明确回环地址，每次独立建库并由应用执行完整Flyway迁移。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_joint_submit_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), username(), password());
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add(
                "spring.datasource.username",
                RestaurantJointSubmissionApiIntegrationTest::username);
        registry.add(
                "spring.datasource.password",
                RestaurantJointSubmissionApiIntegrationTest::password);
    }

    @BeforeEach
    void prepare() {
        reset(sessions, storage, submissionMapper);
        for (String table :
                List.of(
                        "review_submission_photos",
                        "review_submissions",
                        "restaurant_change_photos",
                        "restaurant_change_requests",
                        "media_files",
                        "restaurant_tags",
                        "restaurants",
                        "user_roles",
                        "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (long user : List.of(11L, 22L, 33L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified)"
                        + " VALUES(?,?,?,?,TRUE)",
                    user,
                    "joint_" + user,
                    "joint" + user + "@example.test",
                    "test-only");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",
                    user,
                    user == 33 ? "ROLE_ADMIN" : "ROLE_USER");
        }
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        owner = token(11);
        other = token(22);
        admin = token(33);
    }

    /** 仅清理本次确实创建的随机库，不接受任意库名。 */
    @AfterAll
    static void cleanup() throws SQLException {
        if (created && database.matches("ginkgo_joint_submit_it_[a-f0-9]{32}")) {
            try (var connection = DriverManager.getConnection(url(""), username(), password());
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + database + "`");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECOMMENDED", "NEUTRAL", "NOT_RECOMMENDED"})
    void savesBothKindsOfPhotosAndAllAttitudesWithoutPublishing(String attitude) throws Exception {
        media(10, 11, "RESTAURANT");
        media(20, 11, "REVIEW");
        media(21, 11, "REVIEW");
        var input = shop();
        input.put("photoIds", List.of("10"));
        input.put("review", review(attitude, "21", "20"));
        var result =
                submit(input)
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.status").value("PENDING"))
                        .andExpect(jsonPath("$.data.reviewSubmission.status").value("WAITING"))
                        .andExpect(
                                jsonPath("$.data.reviewSubmission.publishRequestId").doesNotExist())
                        .andReturn();
        String id = data(result).path("id").asString();
        String reviewId = data(result).path("reviewSubmission").path("id").asString();
        var row = jdbc.queryForMap("SELECT * FROM review_submissions WHERE id=?", reviewId);
        assertThat(row.get("restaurant_request_id").toString()).isEqualTo(id);
        assertThat(row.get("origin_request_id").toString()).isEqualTo(id);
        assertThat(row.get("author_user_id").toString()).isEqualTo("11");
        assertThat(row.get("recommendation")).isEqualTo(attitude);
        assertThat(row.get("body")).isEqualTo("真实用餐体验正文");
        assertThat((BigDecimal) row.get("taste_score")).isEqualByComparingTo("8");
        assertThat(
                        jdbc.queryForList(
                                "SELECT media_file_id FROM review_submission_photos ORDER BY"
                                    + " position",
                                Long.class))
                .containsExactly(21L, 20L);
        assertThat(
                        jdbc.queryForList(
                                "SELECT media_file_id FROM restaurant_change_photos", Long.class))
                .containsExactly(10L);
        assertThat(jdbc.queryForList("SELECT status FROM media_files", String.class))
                .containsOnly("BOUND");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT proposed_data FROM restaurant_change_requests WHERE id=?",
                                String.class,
                                id))
                .doesNotContain("review", "真实用餐体验正文");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT BIN_TO_UUID(publish_request_id) FROM review_submissions"
                                    + " WHERE id=?",
                                String.class,
                                reviewId))
                .isNotEqualTo(input.get("clientRequestId"));
        assertNoPublication();
        mvc.perform(get("/api/restaurants/1/reviews")).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @Test
    void retriesCanonicalContentAndRejectsChangesToShopReviewOrderOrPresence() throws Exception {
        media(20, 11, "REVIEW");
        media(21, 11, "REVIEW");
        var input = shop();
        var review = review("NEUTRAL", "21", "20");
        input.put("review", review);
        var first = data(submit(input).andExpect(status().isCreated()).andReturn());
        var before = snapshot();
        review.put("body", "\u00a0真实用餐体验正文　");
        review.put(
                "scores",
                Map.of("taste", new BigDecimal("8.000"), "value", 7, "service", 6, "ambience", 5));
        assertThat(data(submit(input).andExpect(status().isCreated()).andReturn()))
                .isEqualTo(first);
        assertThat(snapshot()).isEqualTo(before);
        for (String field : List.of("recommendation", "body", "photoIds")) {
            var changed = new LinkedHashMap<>(review);
            changed.put(
                    field,
                    switch (field) {
                        case "recommendation" -> "NOT_RECOMMENDED";
                        case "body" -> "修改了之后的体验正文";
                        default -> List.of("20", "21");
                    });
            input.put("review", changed);
            submit(input)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RESTAURANT_STATE_CONFLICT"));
        }
        input.remove("review");
        submit(input).andExpect(status().isConflict());
        input.put("review", null);
        submit(input).andExpect(status().isConflict());
        input.put("review", review);
        input.put("name", "改了店铺名称");
        submit(input).andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(count("restaurant_change_requests")).isEqualTo(1);
        assertNoPublication();
    }

    @Test
    void preservesLegacyHashAndNullReviewBehaviorWithoutAllowingLaterAttachment() throws Exception {
        var input = shop();
        var first =
                data(
                        submit(input)
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.data.reviewSubmission").doesNotExist())
                                .andReturn());
        String legacyHash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                json.writeValueAsBytes(
                                                        json.convertValue(
                                                                input,
                                                                RestaurantChangeRequest.class))));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT HEX(request_hash) FROM restaurant_change_requests",
                                String.class))
                .isEqualToIgnoringCase(legacyHash);
        input.put("review", null);
        assertThat(data(submit(input).andExpect(status().isCreated()).andReturn()))
                .isEqualTo(first);
        input.put("review", review("RECOMMENDED"));
        submit(input).andExpect(status().isConflict());
        assertThat(count("restaurant_change_requests")).isEqualTo(1);
        assertThat(count("review_submissions")).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missingAttitude",
                "booleanAttitude",
                "ordinalAttitude",
                "whitespaceAttitude",
                "unknownField",
                "clientUuid",
                "authorId",
                "targetId",
                "missingDimension",
                "nonHalfScore",
                "shortBody",
                "tooLongBody",
                "tooManyPhotos",
                "duplicatePhotos",
                "badPhotoId",
                "overflowId",
                "nullPhotos"
            })
    void rejectsInvalidNestedContentWithoutSavingEitherPart(String failure) throws Exception {
        var input = shop();
        var review = review("NEUTRAL");
        switch (failure) {
            case "missingAttitude" -> review.remove("recommendation");
            case "booleanAttitude" -> review.put("recommendation", false);
            case "ordinalAttitude" -> review.put("recommendation", 1);
            case "whitespaceAttitude" -> review.put("recommendation", "NEUTRAL ");
            case "unknownField" ->
                    review.put("photos", List.of("https://example.test/foreign.jpg"));
            case "clientUuid" -> review.put("clientRequestId", UUID.randomUUID().toString());
            case "authorId" -> review.put("authorUserId", "22");
            case "targetId" -> review.put("restaurantId", "1");
            case "missingDimension" -> review.put("scores", Map.of("taste", 8));
            case "nonHalfScore" ->
                    review.put(
                            "scores",
                            Map.of("taste", 8.1, "value", 7, "service", 6, "ambience", 5));
            case "shortBody" -> review.put("body", "四个字");
            case "tooLongBody" -> review.put("body", "🥟".repeat(501));
            case "tooManyPhotos" -> review.put("photoIds", List.of("1", "2", "3", "4", "5", "6"));
            case "duplicatePhotos" -> review.put("photoIds", List.of("1", "1"));
            case "badPhotoId" -> review.put("photoIds", List.of("01"));
            case "overflowId" -> review.put("photoIds", List.of("9223372036854775808"));
            case "nullPhotos" -> review.put("photoIds", null);
            default -> throw new IllegalArgumentException(failure);
        }
        input.put("review", review);
        submit(input).andExpect(status().isBadRequest());
        assertNoApplication();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "otherOwner",
                "wrongPurpose",
                "expired",
                "bound",
                "pendingDelete",
                "missing"
            })
    void invalidFileRollsBackShopAndBothKindsOfPhotoBinding(String failure) throws Exception {
        media(10, 11, "RESTAURANT");
        media(20, 11, "REVIEW");
        if (!"missing".equals(failure)) {
            media(
                    21,
                    "otherOwner".equals(failure) ? 22 : 11,
                    "wrongPurpose".equals(failure) ? "RESTAURANT" : "REVIEW");
        }
        switch (failure) {
            case "expired" ->
                    jdbc.update(
                            "UPDATE media_files SET expires_at=CURRENT_TIMESTAMP-INTERVAL 1 SECOND"
                                + " WHERE id=21");
            case "bound" ->
                    jdbc.update(
                            "UPDATE media_files SET"
                                + " status='BOUND',expires_at=NULL,bound_at=CURRENT_TIMESTAMP(6)"
                                + " WHERE id=21");
            case "pendingDelete" ->
                    jdbc.update("UPDATE media_files SET status='PENDING_DELETE' WHERE id=21");
            default -> {}
        }
        var input = shop();
        input.put("photoIds", List.of("10"));
        input.put("review", review("NEUTRAL", "20", "21"));
        submit(input)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_NOT_BINDABLE"));
        assertNoApplication();
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM media_files WHERE id IN (10,20)", String.class))
                .containsOnly("TEMPORARY");
    }

    @Test
    void failedAssociationWriteRollsBackEverythingAndSameRequestCanRetry() throws Exception {
        media(10, 11, "RESTAURANT");
        media(20, 11, "REVIEW");
        media(21, 11, "REVIEW");
        var input = shop();
        input.put("photoIds", List.of("10"));
        input.put("review", review("NEUTRAL", "20", "21"));
        doThrow(new IllegalStateException("injected association failure"))
                .when(submissionMapper)
                .insertPhoto(anyLong(), eq(1), anyLong());
        submit(input).andExpect(status().isInternalServerError());
        assertNoApplication();
        assertThat(jdbc.queryForList("SELECT status FROM media_files", String.class))
                .containsOnly("TEMPORARY");
        reset(submissionMapper);
        submit(input).andExpect(status().isCreated());
        assertThat(count("review_submissions")).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalSubmissionsReturnOneReceiptAndBindOnce() throws Exception {
        media(20, 11, "REVIEW");
        var input = shop();
        input.put("review", review("NEUTRAL", "20"));
        var results = concurrent(List.of(input, input, input));
        var receipts = new ArrayList<JsonNode>();
        for (var result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            receipts.add(data(result));
        }
        assertThat(receipts)
                .allSatisfy(receipt -> assertThat(receipt).isEqualTo(receipts.getFirst()));
        assertThat(count("restaurant_change_requests")).isEqualTo(1);
        assertThat(count("review_submissions")).isEqualTo(1);
        assertThat(count("review_submission_photos")).isEqualTo(1);
    }

    @Test
    void differentRequestsCanSubmitConcurrentlyWithoutEmptyIndexGapDeadlocks() throws Exception {
        var inputs = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 4; i++) {
            var input = shop();
            input.put("review", review("NEUTRAL"));
            inputs.add(input);
        }
        for (var result : concurrent(inputs)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
        }
        assertThat(count("restaurant_change_requests")).isEqualTo(4);
        assertThat(count("review_submissions")).isEqualTo(4);
    }

    @Test
    void oneReviewPhotoCannotBeClaimedByTwoDifferentApplications() throws Exception {
        media(20, 11, "REVIEW");
        var first = shop();
        first.put("review", review("NEUTRAL", "20"));
        var second = shop();
        second.put("review", review("NEUTRAL", "20"));
        assertThat(
                        concurrent(List.of(first, second)).stream()
                                .map(result -> result.getResponse().getStatus())
                                .toList())
                .containsExactlyInAnyOrder(201, 409);
        assertThat(count("restaurant_change_requests")).isEqualTo(1);
        assertThat(count("review_submissions")).isEqualTo(1);
        assertThat(count("review_submission_photos")).isEqualTo(1);
    }

    @Test
    void rejectsBypassesAndProtectsOwnershipUntilLifecycleIntegration() throws Exception {
        var input = shop();
        input.put("review", review("NEUTRAL"));
        String id =
                data(submit(input).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        var plain = new LinkedHashMap<>(input);
        plain.remove("review");
        plain.remove("clientRequestId");
        call(post("/api/admin/restaurant-requests/" + id + "/approve"), admin, plain)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_SUBMISSION_STATE_CONFLICT"));
        call(
                        post("/api/admin/restaurant-requests/" + id + "/reject"),
                        admin,
                        Map.of("reason", "信息不实"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_SUBMISSION_STATE_CONFLICT"));
        call(put("/api/me/restaurant-requests/" + id), owner, plain)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_SUBMISSION_STATE_CONFLICT"));
        call(post("/api/me/restaurant-requests/" + id + "/withdraw"), owner, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_SUBMISSION_STATE_CONFLICT"));
        call(post("/api/me/restaurant-requests/" + id + "/resubmit"), owner, input)
                .andExpect(status().isBadRequest());
        var resubmission = new LinkedHashMap<>(input);
        resubmission.remove("review");
        call(post("/api/me/restaurant-requests/" + id + "/resubmit"), owner, resubmission)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_SUBMISSION_STATE_CONFLICT"));
        call(post("/api/me/restaurant-requests/" + id + "/withdraw"), other, null)
                .andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM restaurant_change_requests WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("PENDING");
        assertThat(count("review_submissions")).isEqualTo(1);
        assertNoPublication();
    }

    @Test
    void doesNotBypassExistingCanteenReviewTargetRule() throws Exception {
        var input = shop();
        input.put("restaurantType", "CANTEEN");
        input.put("categoryCode", null);
        input.put("review", review("NEUTRAL"));
        submit(input).andExpect(status().isBadRequest());
        assertNoApplication();
    }

    @Test
    void retainsTheExistingRuleThatClosedRegularRestaurantsCanBeReviewed() throws Exception {
        var input = shop();
        input.put("businessStatus", "CLOSED");
        input.put("review", review("NEUTRAL"));
        submit(input).andExpect(status().isCreated());
        assertThat(count("review_submissions")).isEqualTo(1);
        assertNoPublication();
    }

    @Test
    void requiresAuthenticationAndRejectsReviewOnCorrectionEntry() throws Exception {
        var input = shop();
        input.put("review", review("NEUTRAL"));
        mvc.perform(
                        post(ENDPOINT)
                                .contentType("application/json")
                                .content(json.writeValueAsString(input)))
                .andExpect(status().isUnauthorized());
        jdbc.update("INSERT INTO restaurants(id,name,category_code) VALUES(1,'已有店铺','OTHER')");
        call(post("/api/restaurants/1/change-requests"), owner, input)
                .andExpect(status().isBadRequest());
        assertThat(count("restaurant_change_requests")).isZero();
        assertThat(count("review_submissions")).isZero();
    }

    private List<MvcResult> concurrent(List<Map<String, Object>> inputs) throws Exception {
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(inputs.size())) {
            var futures = new ArrayList<Future<MvcResult>>();
            for (var input : inputs) {
                futures.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return submit(input).andReturn();
                                }));
            }
            gate.countDown();
            var results = new ArrayList<MvcResult>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private ResultActions submit(Map<String, Object> input) throws Exception {
        return call(post(ENDPOINT), owner, input);
    }

    private ResultActions call(
            MockHttpServletRequestBuilder request, String token, Map<String, ?> input)
            throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (input != null) {
            request.contentType("application/json").content(json.writeValueAsString(input));
        }
        return mvc.perform(request);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private Map<String, Object> shop() {
        var input = new LinkedHashMap<String, Object>();
        input.put("clientRequestId", UUID.randomUUID().toString());
        input.put("name", "联合提交测试店");
        input.put("categoryCode", "OTHER");
        input.put("priceKind", "UNKNOWN");
        input.put("latitude", 30.7544);
        input.put("longitude", 103.9312);
        input.put("locationConfirmed", true);
        return input;
    }

    private Map<String, Object> review(String attitude, String... photos) {
        var input = new LinkedHashMap<String, Object>();
        input.put("scores", Map.of("taste", 8, "value", 7, "service", 6, "ambience", 5));
        input.put("recommendation", attitude);
        input.put("body", "真实用餐体验正文");
        input.put("photoIds", Arrays.asList(photos));
        return input;
    }

    private void media(long id, long user, String purpose) {
        jdbc.update(
                "INSERT INTO"
                    + " media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,purpose,expires_at)"
                    + " VALUES(?,?,'joint-test',?,'image/jpeg',4,1,1,UNHEX(SHA2(?,256)),'TEMPORARY',?,CURRENT_TIMESTAMP+INTERVAL"
                    + " 1 DAY)",
                id,
                user,
                "joint/" + id + ".jpg",
                "joint-" + id,
                purpose);
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList(
                "SELECT"
                    + " id,restaurant_request_id,origin_request_id,author_user_id,status,body,HEX(request_hash)"
                    + " request_hash,HEX(content_hash) content_hash,BIN_TO_UUID(publish_request_id)"
                    + " publish_uuid,created_at,updated_at FROM review_submissions ORDER BY id");
    }

    private long count(String table) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
    }

    private void assertNoApplication() {
        for (String table :
                List.of(
                        "restaurant_change_requests",
                        "restaurant_change_photos",
                        "review_submissions",
                        "review_submission_photos")) {
            assertThat(count(table)).as(table).isZero();
        }
        assertNoPublication();
    }

    private void assertNoPublication() {
        for (String table :
                List.of(
                        "restaurants",
                        "reviews",
                        "restaurant_search_outbox",
                        "growth_rewards",
                        "growth_accounts",
                        "notification_messages")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private String token(long user) {
        var now = Instant.now();
        var jwt = authentication.getJwt();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(jwt.getIssuer())
                        .audience(List.of(jwt.getAudience()))
                        .subject(Long.toString(user))
                        .issuedAt(now.minusSeconds(30))
                        .expiresAt(now.plusSeconds(600))
                        .id(UUID.randomUUID().toString())
                        .claim("credential_version", 0)
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static String url(String name) {
        String server =
                System.getenv()
                        .getOrDefault("RESTAURANT_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Expected loopback database server");
        }
        return server
                + name
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String username() {
        return System.getenv().getOrDefault("RESTAURANT_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        return Objects.requireNonNull(System.getenv("RESTAURANT_TEST_DB_PASSWORD"));
    }
}
