package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.exception.FileErrorCode;
import com.ginkgo.foodmap.file.storage.OssStorageClient;
import com.ginkgo.foodmap.growth.mapper.GrowthRewardMapper;
import com.ginkgo.foodmap.notification.mapper.NotificationMapper;
import com.ginkgo.foodmap.restaurant.dto.RestaurantChangeRequest;
import com.ginkgo.foodmap.restaurant.mapper.MyRestaurantRequestMapper;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantReviewStatisticsMapper;
import com.ginkgo.foodmap.review.mapper.ReviewPhotoMapper;
import com.ginkgo.foodmap.review.mapper.ReviewSubmissionMapper;
import com.ginkgo.foodmap.review.mapper.ReviewWriteMapper;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import java.util.concurrent.Callable;
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
    @MockitoSpyBean private ReviewPhotoMapper reviewPhotos;
    @MockitoSpyBean private ReviewWriteMapper reviewWrites;
    @MockitoSpyBean private RestaurantReviewStatisticsMapper statistics;
    @MockitoSpyBean private GrowthRewardMapper rewards;
    @MockitoSpyBean private NotificationMapper notices;
    @MockitoSpyBean private MyRestaurantRequestMapper ownerRequests;
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
        reset(
                sessions,
                storage,
                submissionMapper,
                reviewPhotos,
                reviewWrites,
                statistics,
                rewards,
                notices,
                ownerRequests);
        // 重提申请保留原驳回记录的自关联，仅在本次随机隔离库中先断开夹具引用再清理。
        jdbc.update("UPDATE restaurant_change_requests SET previous_request_id=NULL");
        for (String table :
                List.of(
                        "review_submission_photos",
                        "review_submissions",
                        "notification_messages",
                        "growth_rewards",
                        "growth_accounts",
                        "restaurant_search_outbox",
                        "review_photos",
                        "review_request_receipts",
                        "reviews",
                        "restaurant_review_statistics",
                        "restaurant_photos",
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
    void rejectedReviewRemainsPrivateAndRejectionRetriesAreIdempotent() throws Exception {
        var input = shop();
        input.put("review", review("NEUTRAL"));
        String id =
                data(submit(input).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        var before = snapshot();
        call(
                        post("/api/admin/restaurant-requests/" + id + "/reject"),
                        admin,
                        Map.of("reason", "信息不实"))
                .andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(before);
        var rejected = publicationSnapshot();
        reject(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(publicationSnapshot()).isEqualTo(rejected);
        call(
                        post("/api/admin/restaurant-requests/" + id + "/reject"),
                        admin,
                        Map.of("reason", "不同原因"))
                .andExpect(status().isConflict());
        approve(id, approval(input)).andExpect(status().isConflict());
        call(post("/api/me/restaurant-requests/" + id + "/withdraw"), other, null)
                .andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM restaurant_change_requests WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("REJECTED");
        assertThat(count("review_submissions")).isEqualTo(1);
        assertThat(count("reviews")).isZero();
        assertThat(count("growth_rewards")).isZero();
        assertThat(count("notification_messages")).isEqualTo(1);
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

    /** 使用真实JWT和SQL校验详情白名单、半分及零分，且读取不修改待发布内容。 */
    @ParameterizedTest
    @ValueSource(strings = {"RECOMMENDED", "NEUTRAL", "NOT_RECOMMENDED"})
    void ownerReadsAttachedReviewWithoutPublishingOrLeakingInternalFields(String attitude)
            throws Exception {
        media(20, 11, "REVIEW");
        media(21, 11, "REVIEW");
        var input = shop();
        var attached = review(attitude, "21", "20");
        attached.put("scores", Map.of("taste", 0, "value", 7.5, "service", 10, "ambience", 0.5));
        input.put("review", attached);
        String id =
                data(submit(input).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        var before = snapshot();
        var result =
                call(get("/api/me/restaurant-requests/" + id), owner, null)
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.reviewSubmission.status").value("WAITING"))
                        .andExpect(
                                jsonPath("$.data.reviewSubmission.recommendation").value(attitude))
                        .andExpect(jsonPath("$.data.reviewSubmission.body").value("真实用餐体验正文"))
                        .andExpect(jsonPath("$.data.reviewSubmission.scores.taste").value(0))
                        .andExpect(jsonPath("$.data.reviewSubmission.scores.value").value(7.5))
                        .andExpect(jsonPath("$.data.reviewSubmission.scores.service").value(10))
                        .andExpect(jsonPath("$.data.reviewSubmission.scores.ambience").value(0.5))
                        .andExpect(jsonPath("$.data.reviewSubmission.photoPositions[0]").value(0))
                        .andExpect(jsonPath("$.data.reviewSubmission.photoPositions[1]").value(1))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "requestHash",
                        "contentHash",
                        "clientRequestId",
                        "publishRequestId",
                        "authorUserId",
                        "mediaFileId",
                        "storageKey",
                        "joint/",
                        "photoIds");
        assertThat(data(result).path("proposed").has("review")).isFalse();
        assertThat(snapshot()).isEqualTo(before);
        assertNoPublication();
        verifyNoInteractions(storage);
    }

    @Test
    void plainApplicationHasNoAttachedReview() throws Exception {
        String id =
                data(submit(shop()).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        call(get("/api/me/restaurant-requests/" + id), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewSubmission").doesNotExist());
        call(get("/api/me/restaurant-requests/" + id + "/review/photos/0"), owner, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        verifyNoInteractions(storage);
    }

    /** 管理员与其他用户均无例外；注入身份参数或非法位置也不能探测他人的申请。 */
    @Test
    void privateReviewCannotBeReadByOtherUsersOrAdministrators() throws Exception {
        String id = submitWithReviewPhoto();
        for (String viewer : List.of(other, admin)) {
            call(
                            get("/api/me/restaurant-requests/" + id)
                                    .param("userId", "11")
                                    .param("authorUserId", "11"),
                            viewer,
                            null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESTAURANT_REQUEST_NOT_FOUND"));
            for (String position : List.of("0", "5", "bad")) {
                call(
                                get(
                                        "/api/me/restaurant-requests/"
                                                + id
                                                + "/review/photos/"
                                                + position),
                                viewer,
                                null)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("RESTAURANT_REQUEST_NOT_FOUND"));
            }
        }
        for (String route :
                List.of(
                        "/api/me/restaurant-requests/" + id,
                        "/api/me/restaurant-requests/" + id + "/review/photos/0")) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized());
        }
        for (String route :
                List.of("/api/admin/restaurant-requests/" + id, "/api/admin/restaurant-requests")) {
            String response =
                    call(get(route), admin, null)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(response)
                    .doesNotContain("reviewSubmission", "真实用餐体验正文", "recommendation", "joint/");
        }
        verifyNoInteractions(storage);
    }

    /** 数据异常时仍由review和file两层独立校验作者，不依赖申请表一处判断。 */
    @Test
    void reviewAuthorMustAlsoMatchTheCurrentUser() throws Exception {
        String id = submitWithReviewPhoto();
        jdbc.update(
                "UPDATE review_submissions SET author_user_id=22 WHERE restaurant_request_id=?",
                id);
        call(get("/api/me/restaurant-requests/" + id), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewSubmission").doesNotExist());
        call(get("/api/me/restaurant-requests/" + id + "/review/photos/0"), owner, null)
                .andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @Test
    void readsPhotoBytesInOriginalOrderWithoutDatabaseTransactionOrWrites() throws Exception {
        String id = submitWithReviewPhoto();
        // 绑定完成后有效期已清空，读取不能重新要求临时上传有效期。
        assertThat(
                        jdbc.queryForMap("SELECT expires_at FROM media_files WHERE id=20")
                                .get("expires_at"))
                .isNull();
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        when(storage.read("joint-test", "joint/20.jpg"))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return jpeg;
                        });
        var before = snapshot();
        call(get("/api/me/restaurant-requests/" + id + "/review/photos/0"), owner, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(jpeg));
        verify(storage).read("joint-test", "joint/20.jpg");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=20", String.class))
                .isEqualTo("BOUND");
        assertNoPublication();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "5", "00", "x", "1.0"})
    void rejectsInvalidReviewPhotoPositions(String position) throws Exception {
        String id = submitWithReviewPhoto();
        call(get("/api/me/restaurant-requests/" + id + "/review/photos/" + position), owner, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTAURANT_INVALID_INPUT"));
        verifyNoInteractions(storage);
    }

    /** 验证上界位置4及客户端顺序，避免按文件ID排序或误用店铺图片关联。 */
    @Test
    void readsAllFivePhotosBySubmittedPosition() throws Exception {
        for (long fileId = 20; fileId < 25; fileId++) {
            media(fileId, 11, "REVIEW");
        }
        var input = shop();
        input.put("review", review("NEUTRAL", "24", "23", "22", "21", "20"));
        String id =
                data(submit(input).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        call(get("/api/me/restaurant-requests/" + id), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewSubmission.photoPositions.length()").value(5));
        for (int position = 0; position < 5; position++) {
            String key = "joint/" + (24 - position) + ".jpg";
            byte[] bytes = {(byte) position};
            when(storage.read("joint-test", key)).thenReturn(bytes);
            call(
                            get("/api/me/restaurant-requests/" + id + "/review/photos/" + position),
                            owner,
                            null)
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(bytes));
            verify(storage).read("joint-test", key);
        }
        call(get("/api/me/restaurant-requests/" + id + "/photos/0"), owner, null)
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missingPosition",
                "otherOwner",
                "wrongPurpose",
                "temporary",
                "pendingDelete",
                "missingFile"
            })
    void rejectsMissingOrInvalidPrivateReviewMediaBeforeReadingStorage(String failure)
            throws Exception {
        String id = submitWithReviewPhoto();
        switch (failure) {
            case "otherOwner" ->
                    jdbc.update("UPDATE media_files SET uploader_user_id=22 WHERE id=20");
            case "wrongPurpose" ->
                    jdbc.update("UPDATE media_files SET purpose='RESTAURANT' WHERE id=20");
            case "temporary" ->
                    jdbc.update(
                            "UPDATE media_files SET"
                                + " status='TEMPORARY',bound_at=NULL,expires_at=CURRENT_TIMESTAMP+INTERVAL"
                                + " 1 DAY WHERE id=20");
            case "pendingDelete" ->
                    jdbc.update("UPDATE media_files SET status='PENDING_DELETE' WHERE id=20");
            case "missingFile" -> jdbc.update("DELETE FROM media_files WHERE id=20");
            default -> {}
        }
        String position = "missingPosition".equals(failure) ? "1" : "0";
        call(get("/api/me/restaurant-requests/" + id + "/review/photos/" + position), owner, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        verifyNoInteractions(storage);
    }

    /** 只种入未来终态，不开放任何生命周期写接口；已发布回执不恢复残留私有图片。 */
    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWN", "PUBLISHED"})
    void readsStoredTerminalStatesWithoutResurrectingPublishedContent(String state)
            throws Exception {
        String id = submitWithReviewPhoto();
        if ("PUBLISHED".equals(state)) {
            jdbc.update(
                    "UPDATE review_submissions SET"
                        + " status='PUBLISHED',taste_score=NULL,value_score=NULL,service_score=NULL,ambience_score=NULL,recommendation=NULL,body=NULL,published_review_id=99,published_at=CURRENT_TIMESTAMP(6)"
                        + " WHERE restaurant_request_id=?",
                    id);
        } else {
            jdbc.update(
                    "UPDATE review_submissions SET"
                            + " status='WITHDRAWN',withdrawn_at=CURRENT_TIMESTAMP(6) WHERE"
                            + " restaurant_request_id=?",
                    id);
        }
        var before = snapshot();
        var attached =
                data(call(get("/api/me/restaurant-requests/" + id), owner, null)
                                .andExpect(status().isOk())
                                .andReturn())
                        .path("reviewSubmission");
        assertThat(attached.path("status").asString()).isEqualTo(state);
        if ("PUBLISHED".equals(state)) {
            assertThat(attached.path("publishedReviewId").asString()).isEqualTo("99");
            assertThat(attached.path("scores").isNull()).isTrue();
            assertThat(attached.path("body").isNull()).isTrue();
            assertThat(attached.path("recommendation").isNull()).isTrue();
            assertThat(attached.path("photoPositions").size()).isZero();
            call(get("/api/me/restaurant-requests/" + id + "/review/photos/0"), owner, null)
                    .andExpect(status().isNotFound());
        } else {
            assertThat(attached.path("body").asString()).isEqualTo("真实用餐体验正文");
            when(storage.read("joint-test", "joint/20.jpg")).thenReturn(new byte[] {1});
            call(get("/api/me/restaurant-requests/" + id + "/review/photos/0"), owner, null)
                    .andExpect(status().isOk());
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void readFailuresRemainErrorsRatherThanEmptySuccess() throws Exception {
        String id = submitWithReviewPhoto();
        when(storage.read("joint-test", "joint/20.jpg"))
                .thenThrow(new BusinessException(FileErrorCode.UNAVAILABLE));
        call(get("/api/me/restaurant-requests/" + id + "/review/photos/0"), owner, null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FILE_STORAGE_UNAVAILABLE"));
        doThrow(new IllegalStateException("injected read failure"))
                .when(submissionMapper)
                .findOwn(anyLong(), anyLong());
        call(get("/api/me/restaurant-requests/" + id), owner, null)
                .andExpect(status().isInternalServerError());
        doThrow(new IllegalStateException("injected photo query failure"))
                .when(submissionMapper)
                .findOwnPhoto(anyLong(), anyLong(), eq(0));
        call(get("/api/me/restaurant-requests/" + id + "/review/photos/0"), owner, null)
                .andExpect(status().isInternalServerError());
    }

    /** 审核只接收店铺字段；原作者、独立UUID、三态与图片顺序均从已保存的私有记录取得。 */
    @ParameterizedTest
    @ValueSource(strings = {"RECOMMENDED", "NEUTRAL", "NOT_RECOMMENDED"})
    void approvalPublishesReviewAndMovesPhotosWithoutExposingPrivateContent(String attitude)
            throws Exception {
        media(10, 11, "RESTAURANT");
        var input = shop();
        input.put("photoIds", List.of("10"));
        boolean hasPhotos = !"NEUTRAL".equals(attitude);
        if (hasPhotos) {
            for (long file = 20; file < 25; file++) {
                media(file, 11, "REVIEW");
            }
        }
        var attached =
                hasPhotos ? review(attitude, "24", "23", "22", "21", "20") : review(attitude);
        attached.put("scores", Map.of("taste", 0, "value", 7.5, "service", 10, "ambience", 0.5));
        input.put("review", attached);
        String id =
                data(submit(input).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        String reserved =
                jdbc.queryForObject(
                        "SELECT BIN_TO_UUID(publish_request_id) FROM review_submissions",
                        String.class);
        var approved = approval(input);
        approved.put("name", "管理员核对后的店名");
        approved.put("businessStatus", "CLOSED");
        var result =
                approve(id, approved)
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.status").value("APPROVED"))
                        .andExpect(jsonPath("$.data.replayed").value(false))
                        .andReturn();
        String restaurant = data(result).path("restaurantId").asString();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "reviewSubmission", "真实用餐体验正文", "recommendation", "publishRequestId");
        var formal =
                jdbc.queryForMap(
                        "SELECT *,BIN_TO_UUID(client_request_id) publish_uuid FROM reviews");
        String reviewId = formal.get("id").toString();
        assertThat(formal.get("restaurant_id").toString()).isEqualTo(restaurant);
        assertThat(formal.get("author_user_id").toString()).isEqualTo("11");
        assertThat(formal.get("publish_uuid").toString()).isEqualToIgnoringCase(reserved);
        assertThat(formal.get("recommendation")).isEqualTo(attitude);
        assertThat(formal.get("body")).isEqualTo("真实用餐体验正文");
        var stats = jdbc.queryForMap("SELECT * FROM restaurant_review_statistics");
        assertThat(((Number) stats.get("participant_count")).longValue()).isEqualTo(1);
        assertThat(((Number) stats.get("review_count")).longValue()).isEqualTo(1);
        assertThat((BigDecimal) stats.get("taste_sum")).isEqualByComparingTo("0");
        assertThat((BigDecimal) stats.get("value_sum")).isEqualByComparingTo("7.5");
        assertThat((BigDecimal) stats.get("service_sum")).isEqualByComparingTo("10");
        assertThat((BigDecimal) stats.get("ambience_sum")).isEqualByComparingTo("0.5");
        assertThat((BigDecimal) stats.get("overall_score_sum")).isEqualByComparingTo("4.5");
        assertThat(
                        jdbc.queryForList(
                                "SELECT media_file_id FROM review_photos ORDER BY position",
                                Long.class))
                .containsExactlyElementsOf(
                        hasPhotos ? List.of(24L, 23L, 22L, 21L, 20L) : List.of());
        assertThat(count("review_submission_photos")).isZero();
        var pending = jdbc.queryForMap("SELECT * FROM review_submissions");
        assertThat(pending.get("status")).isEqualTo("PUBLISHED");
        assertThat(pending.get("published_review_id").toString()).isEqualTo(reviewId);
        assertThat(pending.get("published_at")).isNotNull();
        for (String field :
                List.of(
                        "body",
                        "recommendation",
                        "taste_score",
                        "value_score",
                        "service_score",
                        "ambience_score")) {
            assertThat(pending.get(field)).as(field).isNull();
        }
        assertThat(count("review_request_receipts")).isEqualTo(1);
        assertThat(count("restaurant_photos")).isEqualTo(1);
        assertThat(count("restaurant_change_photos")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT status FROM media_files", String.class))
                .containsOnly("BOUND");
        assertThat(jdbc.queryForList("SELECT user_id FROM growth_rewards", Long.class))
                .containsOnly(11L)
                .hasSize(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT experience FROM growth_accounts WHERE user_id=11",
                                Long.class))
                .isEqualTo(25L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT recipient_user_id FROM notification_messages", Long.class))
                .isEqualTo(11L);
        assertThat(count("restaurant_search_outbox")).isEqualTo(2);
        call(get("/api/me/restaurant-requests/" + id), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewSubmission.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.reviewSubmission.publishedReviewId").value(reviewId))
                .andExpect(jsonPath("$.data.reviewSubmission.body").doesNotExist())
                .andExpect(jsonPath("$.data.reviewSubmission.photoPositions.length()").value(0));
        call(get("/api/me/restaurant-requests/" + id + "/review/photos/0"), owner, null)
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/restaurants/" + restaurant + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].currentReview.body").value("真实用餐体验正文"));
        var beforeRetry = publicationSnapshot();
        submit(input)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.reviewSubmission.status").value("PUBLISHED"));
        assertThat(publicationSnapshot()).isEqualTo(beforeRetry);
        verifyNoInteractions(storage);
        if (hasPhotos) {
            byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
            when(storage.read("joint-test", "joint/24.jpg")).thenReturn(jpeg);
            mvc.perform(get("/api/reviews/" + reviewId + "/photos/0"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(jpeg));
        }
    }

    @Test
    void approvalRetriesKeepOriginalReceiptAndRejectDifferentShopContent() throws Exception {
        String id = submitWithReviewPhoto();
        var input = approval(shop());
        var first = data(approve(id, input).andExpect(status().isOk()).andReturn());
        var before = publicationSnapshot();
        var replay =
                data(
                        approve(id, input)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.replayed").value(true))
                                .andReturn());
        assertThat(replay.path("restaurantId")).isEqualTo(first.path("restaurantId"));
        assertThat(replay.path("reviewedAt")).isEqualTo(first.path("reviewedAt"));
        input.put("name", "不同的审核内容");
        approve(id, input).andExpect(status().isConflict());
        assertThat(publicationSnapshot()).isEqualTo(before);
        assertThat(count("reviews")).isEqualTo(1);
        assertThat(count("growth_rewards")).isEqualTo(2);
        assertThat(count("notification_messages")).isEqualTo(1);
    }

    /** 同一申请由行锁串行化；成功后再来的请求只读回执，不重复发布、计分或发奖。 */
    @Test
    void concurrentApprovalsPublishExactlyOnce() throws Exception {
        String id = submitWithReviewPhoto();
        var input = approval(shop());
        var gate = new CountDownLatch(1);
        int replayed = 0;
        try (var executor = Executors.newFixedThreadPool(3)) {
            var futures = new ArrayList<Future<MvcResult>>();
            for (int index = 0; index < 3; index++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return approve(id, input).andReturn();
                                }));
            }
            gate.countDown();
            for (var future : futures) {
                var response = future.get(20, TimeUnit.SECONDS);
                assertThat(response.getResponse().getStatus()).isEqualTo(200);
                replayed += data(response).path("replayed").asBoolean() ? 1 : 0;
            }
        }
        assertThat(replayed).isEqualTo(2);
        assertThat(count("restaurants")).isEqualTo(1);
        assertThat(count("reviews")).isEqualTo(1);
        assertThat(count("review_photos")).isEqualTo(1);
        assertThat(count("growth_rewards")).isEqualTo(2);
        assertThat(count("notification_messages")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT review_count FROM restaurant_review_statistics",
                                Long.class))
                .isEqualTo(1L);
    }

    /** 在不同写入阶段注入故障，确保连同已创建的新店、相册、搜索任务及奖励全部回滚。 */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "reviewInsert",
                "reviewPhoto",
                "score",
                "searchVersion",
                "reviewReward",
                "shopReward",
                "notice",
                "removePhotos",
                "receipt"
            })
    void publicationFailureRollsBackWholeApprovalAndCanRetry(String failure) throws Exception {
        media(10, 11, "RESTAURANT");
        media(20, 11, "REVIEW");
        media(21, 11, "REVIEW");
        var input = shop();
        input.put("photoIds", List.of("10"));
        input.put("review", review("NEUTRAL", "21", "20"));
        String id =
                data(submit(input).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        var before = publicationSnapshot();
        var fault = new IllegalStateException("injected approval publication failure");
        switch (failure) {
            case "reviewInsert" -> doThrow(fault).when(reviewWrites).insert(any());
            case "reviewPhoto" ->
                    doThrow(fault).when(reviewPhotos).insert(anyLong(), eq(1), anyLong());
            case "score" -> doThrow(fault).when(statistics).applyDelta(anyLong(), any());
            case "searchVersion" ->
                    doThrow(fault).when(statistics).advanceSearchProjectionVersion(anyLong());
            case "reviewReward" -> doThrow(fault).when(rewards).insertFirstStoreReview(any());
            case "shopReward" -> doThrow(fault).when(rewards).insertApproval(any());
            case "notice" -> doThrow(fault).when(notices).insert(any());
            case "removePhotos" -> doReturn(0).when(submissionMapper).deletePhotos(anyLong());
            case "receipt" ->
                    doReturn(0).when(submissionMapper).markPublished(anyLong(), anyLong());
            default -> throw new IllegalArgumentException(failure);
        }
        approve(id, approval(input)).andExpect(status().isInternalServerError());
        assertThat(publicationSnapshot()).isEqualTo(before);
        assertNoPublication();
        assertThat(jdbc.queryForList("SELECT status FROM media_files", String.class))
                .containsOnly("BOUND");
        reset(submissionMapper, reviewWrites, reviewPhotos, statistics, rewards, notices);
        approve(id, approval(input)).andExpect(status().isOk());
        assertThat(count("reviews")).isEqualTo(1);
        assertThat(count("review_submission_photos")).isZero();
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"otherOwner", "wrongPurpose", "temporary", "pendingDelete", "missing"})
    void invalidBoundReviewPhotoPreventsShopApproval(String failure) throws Exception {
        String id = submitWithReviewPhoto();
        switch (failure) {
            case "otherOwner" ->
                    jdbc.update("UPDATE media_files SET uploader_user_id=22 WHERE id=20");
            case "wrongPurpose" ->
                    jdbc.update("UPDATE media_files SET purpose='RESTAURANT' WHERE id=20");
            case "temporary" ->
                    jdbc.update(
                            "UPDATE media_files SET"
                                + " status='TEMPORARY',bound_at=NULL,expires_at=CURRENT_TIMESTAMP+INTERVAL"
                                + " 1 DAY WHERE id=20");
            case "pendingDelete" ->
                    jdbc.update("UPDATE media_files SET status='PENDING_DELETE' WHERE id=20");
            case "missing" -> jdbc.update("DELETE FROM media_files WHERE id=20");
            default -> throw new IllegalArgumentException(failure);
        }
        var before = publicationSnapshot();
        approve(id, approval(shop()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_NOT_BINDABLE"));
        assertThat(publicationSnapshot()).isEqualTo(before);
        assertNoPublication();
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"canteen", "author", "withdrawn", "contentHash"})
    void approvalCannotBypassAttachedReviewConstraints(String failure) throws Exception {
        String id = submitWithReviewPhoto();
        var input = approval(shop());
        switch (failure) {
            case "canteen" -> {
                input.put("restaurantType", "CANTEEN");
                input.put("categoryCode", null);
            }
            case "author" -> jdbc.update("UPDATE review_submissions SET author_user_id=22");
            case "withdrawn" ->
                    jdbc.update(
                            "UPDATE review_submissions SET"
                                    + " status='WITHDRAWN',withdrawn_at=CURRENT_TIMESTAMP(6)");
            case "contentHash" ->
                    jdbc.update(
                            "UPDATE review_submissions SET"
                                    + " content_hash=UNHEX(SHA2('invalid',256))");
            default -> throw new IllegalArgumentException(failure);
        }
        var before = publicationSnapshot();
        approve(id, input)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_SUBMISSION_STATE_CONFLICT"));
        assertThat(publicationSnapshot()).isEqualTo(before);
        assertNoPublication();
    }

    @Test
    void deletedPublishedReviewIsNeverRestoredByApprovalRetry() throws Exception {
        String id = submitWithReviewPhoto();
        var input = approval(shop());
        approve(id, input).andExpect(status().isOk());
        String formalId =
                jdbc.queryForObject(
                        "SELECT published_review_id FROM review_submissions", String.class);
        call(delete("/api/reviews/" + formalId), owner, null).andExpect(status().isNoContent());
        var before = publicationSnapshot();
        approve(id, input)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(publicationSnapshot()).isEqualTo(before);
        assertThat(count("reviews")).isZero();
        assertThat(jdbc.queryForObject("SELECT body FROM review_submissions", String.class))
                .isNull();
        call(get("/api/me/restaurant-requests/" + id), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewSubmission.publishedReviewId").value(formalId))
                .andExpect(jsonPath("$.data.reviewSubmission.body").doesNotExist());
    }

    @Test
    void ordinaryUsersCannotApproveAndAdministratorsCannotSupplyReviewContent() throws Exception {
        String id = submitWithReviewPhoto();
        var input = approval(shop());
        call(post("/api/admin/restaurant-requests/" + id + "/approve"), owner, input)
                .andExpect(status().isForbidden());
        input.put("review", review("RECOMMENDED"));
        approve(id, input).andExpect(status().isBadRequest());
        assertNoPublication();
    }

    /** 通过失败或重试前后比较受影响表的完整业务值（不计自增计数），验证无部分提交。 */
    private Map<String, List<Map<String, Object>>> publicationSnapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table :
                List.of(
                        "restaurants",
                        "restaurant_tags",
                        "restaurant_photos",
                        "restaurant_change_requests",
                        "restaurant_change_photos",
                        "review_submissions",
                        "review_submission_photos",
                        "reviews",
                        "review_photos",
                        "review_request_receipts",
                        "restaurant_review_statistics",
                        "growth_accounts",
                        "growth_rewards",
                        "restaurant_search_outbox",
                        "notification_messages",
                        "media_files")) {
            var rows = jdbc.queryForList("SELECT * FROM " + table);
            // JDBC每次生成新的字节数组，转为十六进制后按内容比较，避免把引用差异误判成写入。
            rows.forEach(
                    row ->
                            row.replaceAll(
                                    (field, value) ->
                                            value instanceof byte[] bytes
                                                    ? HexFormat.of().formatHex(bytes)
                                                    : value));
            result.put(table, rows);
        }
        return result;
    }

    /** 防御预留UUID碰撞：不能认领别处已存在的点评，也不能重放已有删除回执。 */
    @ParameterizedTest
    @ValueSource(strings = {"active", "deleted"})
    void publicationUuidCollisionNeverAdoptsOrRestoresAnotherReview(String state) throws Exception {
        String id = submitWithReviewPhoto();
        String reserved =
                jdbc.queryForObject(
                        "SELECT BIN_TO_UUID(publish_request_id) FROM review_submissions",
                        String.class);
        jdbc.update("INSERT INTO restaurants(id,name,category_code) VALUES(991,'已存在餐厅','OTHER')");
        var existing = review("NEUTRAL");
        existing.put("clientRequestId", reserved);
        String formalId =
                data(call(post("/api/restaurants/991/reviews"), owner, existing)
                                .andExpect(status().isCreated())
                                .andReturn())
                        .path("reviewId")
                        .asString();
        if ("deleted".equals(state)) {
            call(delete("/api/reviews/" + formalId), owner, null).andExpect(status().isNoContent());
        }
        var before = publicationSnapshot();
        approve(id, approval(shop()))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "deleted".equals(state)
                                                ? "REVIEW_REQUEST_DELETED"
                                                : "REVIEW_REQUEST_CONFLICT"));
        assertThat(publicationSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM review_submissions", String.class))
                .isEqualTo("WAITING");
        assertThat(count("restaurants")).isEqualTo(1);
        verifyNoInteractions(storage);
    }

    @Test
    void inconsistentHistoricalApprovalDoesNotBackfillPendingReview() throws Exception {
        String id = submitWithReviewPhoto();
        var input = approval(shop());
        jdbc.update("INSERT INTO restaurants(id,name,category_code) VALUES(991,'历史餐厅','OTHER')");
        jdbc.update(
                "UPDATE restaurant_change_requests SET"
                    + " status='APPROVED',restaurant_id=991,approved_data=?,reviewed_by=33,reviewed_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE id=?",
                json.writeValueAsString(input),
                id);
        var before = publicationSnapshot();
        approve(id, input)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_SUBMISSION_STATE_CONFLICT"));
        assertThat(publicationSnapshot()).isEqualTo(before);
        assertThat(count("reviews")).isZero();
        assertThat(count("growth_rewards")).isZero();
    }

    /** 已收录保留原投稿和私有店铺图，只将原作者点评发布到明确选择的目标。 */
    @Test
    void linksExistingWithoutOverwritingShopOrPublishingSubmissionShopPhotos() throws Exception {
        existingShop(991);
        media(10, 11, "RESTAURANT");
        media(20, 11, "REVIEW");
        var input = shop();
        input.put("photoIds", List.of("10"));
        input.put("review", review("NEUTRAL", "20"));
        String id =
                data(submit(input).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        var original =
                jdbc.queryForMap(
                        "SELECT proposed_data,HEX(request_hash) h FROM restaurant_change_requests"
                                + " WHERE id=?",
                        id);
        var first =
                data(
                        link(id, "991")
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.approvalMode").value("LINKED_EXISTING"))
                                .andExpect(jsonPath("$.data.restaurantId").value("991"))
                                .andExpect(jsonPath("$.data.publishedPhotos").isEmpty())
                                .andReturn());
        assertThat(count("restaurants")).isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT name,address,CAST(created_by AS CHAR)"
                                    + " created_by,CAST(updated_by AS CHAR) updated_by FROM"
                                    + " restaurants WHERE id=991"))
                .containsEntry("name", "已收录餐厅")
                .containsEntry("address", "原有地址")
                .containsEntry("created_by", "22")
                .containsEntry("updated_by", "22");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT proposed_data,HEX(request_hash) h FROM"
                                        + " restaurant_change_requests WHERE id=?",
                                id))
                .isEqualTo(original);
        assertThat(count("restaurant_change_photos")).isEqualTo(1);
        assertThat(count("restaurant_photos")).isZero();
        assertThat(count("reviews")).isEqualTo(1);
        assertThat(count("review_photos")).isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT CAST(restaurant_id AS CHAR)"
                                    + " restaurant_id,CAST(author_user_id AS CHAR) author_user_id"
                                    + " FROM reviews"))
                .containsEntry("restaurant_id", "991")
                .containsEntry("author_user_id", "11");
        assertThat(count("growth_rewards")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM growth_rewards WHERE"
                                        + " reward_type='NEW_STORE_APPROVED'",
                                Long.class))
                .isZero();
        call(get("/api/me/restaurant-requests/" + id), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.application.approvalMode").value("LINKED_EXISTING"))
                .andExpect(jsonPath("$.data.approved.name").value("已收录餐厅"))
                .andExpect(jsonPath("$.data.reviewSubmission.status").value("PUBLISHED"));
        call(get("/api/admin/restaurant-requests/" + id), admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.application.approvalMode").value("LINKED_EXISTING"))
                .andExpect(jsonPath("$.data.reviewSubmission").doesNotExist());
        call(get("/api/me/notifications"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].approvalMode").value("LINKED_EXISTING"))
                .andExpect(jsonPath("$.data.latestSystem.approvalMode").value("LINKED_EXISTING"));
        var before = publicationSnapshot();
        var replay =
                data(
                        link(id, "991")
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.replayed").value(true))
                                .andReturn());
        assertThat(replay.path("reviewedAt")).isEqualTo(first.path("reviewedAt"));
        approve(id, approval(input)).andExpect(status().isConflict());
        reject(id).andExpect(status().isConflict());
        link(id, "992").andExpect(status().isConflict());
        assertThat(publicationSnapshot()).isEqualTo(before);
        verifyNoInteractions(storage);
    }

    @Test
    void plainSubmissionCanLinkWithoutAnyReviewOrReward() throws Exception {
        existingShop(991);
        var beforeShop = jdbc.queryForMap("SELECT * FROM restaurants WHERE id=991");
        String id =
                data(submit(shop()).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        link(id, "991").andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT * FROM restaurants WHERE id=991"))
                .isEqualTo(beforeShop);
        assertThat(count("reviews")).isZero();
        assertThat(count("growth_rewards")).isZero();
        assertThat(count("notification_messages")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"review", "photos", "score", "reward", "notification", "receipt"})
    void linkFailureRollsBackWholeDecisionAndCanRetry(String failure) throws Exception {
        existingShop(991);
        String id = submitWithReviewPhoto();
        var before = publicationSnapshot();
        var fault = new IllegalStateException("injected link failure");
        switch (failure) {
            case "review" -> doThrow(fault).when(reviewWrites).insert(any());
            case "photos" -> doThrow(fault).when(reviewPhotos).insert(anyLong(), eq(0), anyLong());
            case "score" -> doThrow(fault).when(statistics).applyDelta(anyLong(), any());
            case "reward" -> doThrow(fault).when(rewards).insertFirstStoreReview(any());
            case "notification" -> doThrow(fault).when(notices).insert(any());
            case "receipt" ->
                    doReturn(0).when(submissionMapper).markPublished(anyLong(), anyLong());
            default -> throw new IllegalArgumentException(failure);
        }
        link(id, "991").andExpect(status().isInternalServerError());
        assertThat(publicationSnapshot()).isEqualTo(before);
        reset(reviewWrites, reviewPhotos, statistics, rewards, notices, submissionMapper);
        link(id, "991").andExpect(status().isOk());
        assertThat(count("reviews")).isEqualTo(1);
    }

    @Test
    void twoAuthorsCanLinkSameShopConcurrentlyWithoutCreatingDuplicateShops() throws Exception {
        existingShop(991);
        var first = shop();
        first.put("review", review("NEUTRAL"));
        var second = shop();
        second.put("review", review("RECOMMENDED"));
        String a =
                data(submit(first).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        String b =
                data(call(post(ENDPOINT), other, second)
                                .andExpect(status().isCreated())
                                .andReturn())
                        .path("id")
                        .asString();
        var results =
                race(List.of(() -> link(a, "991").andReturn(), () -> link(b, "991").andReturn()));
        assertThat(results)
                .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        assertThat(count("restaurants")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT restaurant_id FROM reviews", Long.class))
                .containsExactlyInAnyOrder(991L, 991L);
        assertThat(jdbc.queryForList("SELECT author_user_id FROM reviews", Long.class))
                .containsExactlyInAnyOrder(11L, 22L);
        assertThat(count("growth_rewards")).isEqualTo(2);
        assertThat(count("notification_messages")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject", "withdraw", "otherTarget", "sameTarget"})
    void linkCompetesSafelyWithOtherDecision(String action) throws Exception {
        existingShop(991);
        existingShop(992);
        String id = submitWithReviewPhoto();
        Callable<MvcResult> competing =
                switch (action) {
                    case "approve" -> () -> approve(id, approval(shop())).andReturn();
                    case "reject" -> () -> reject(id).andReturn();
                    case "withdraw" ->
                            () ->
                                    call(
                                                    post(
                                                            "/api/me/restaurant-requests/"
                                                                    + id
                                                                    + "/withdraw"),
                                                    owner,
                                                    null)
                                            .andReturn();
                    case "otherTarget" -> () -> link(id, "992").andReturn();
                    default -> () -> link(id, "991").andReturn();
                };
        var results = race(List.of(() -> link(id, "991").andReturn(), competing));
        var codes = results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(codes).contains(200).allMatch(code -> code == 200 || code == 409);
        assertThat(codes.stream().filter(code -> code == 200).count())
                .isEqualTo("sameTarget".equals(action) ? 2 : 1);
        assertThat(count("reviews")).isLessThanOrEqualTo(1);
        assertThat(count("notification_messages")).isLessThanOrEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "deleted",
                "wrongType",
                "rejected",
                "withdrawn",
                "created",
                "update"
            })
    void linkRejectsUnavailableTargetsAndIncompatibleApplications(String failure) throws Exception {
        existingShop(991);
        String id = submitWithReviewPhoto();
        switch (failure) {
            case "missing" -> {}
            case "deleted" ->
                    call(delete("/api/restaurants/991"), admin, null).andExpect(status().isOk());
            case "wrongType" ->
                    jdbc.update(
                            "UPDATE restaurants SET restaurant_type='CANTEEN',category_code=NULL"
                                    + " WHERE id=991");
            case "rejected" -> reject(id).andExpect(status().isOk());
            case "withdrawn" ->
                    call(post("/api/me/restaurant-requests/" + id + "/withdraw"), owner, null)
                            .andExpect(status().isOk());
            case "created" -> approve(id, approval(shop())).andExpect(status().isOk());
            case "update" -> {
                var correction = shop();
                id =
                        data(call(post("/api/restaurants/991/change-requests"), owner, correction)
                                        .andExpect(status().isCreated())
                                        .andReturn())
                                .path("id")
                                .asString();
            }
            default -> throw new IllegalArgumentException(failure);
        }
        var before = publicationSnapshot();
        link(id, "missing".equals(failure) ? "99999" : "991")
                .andExpect(
                        "missing".equals(failure) || "deleted".equals(failure)
                                ? status().isNotFound()
                                : status().isConflict());
        assertThat(publicationSnapshot()).isEqualTo(before);
    }

    @Test
    void linkReplayAfterTargetAndReviewDeletionDoesNotRestoreAnything() throws Exception {
        existingShop(991);
        String id = submitWithReviewPhoto();
        link(id, "991").andExpect(status().isOk());
        String reviewId = jdbc.queryForObject("SELECT id FROM reviews", String.class);
        call(delete("/api/reviews/" + reviewId), owner, null).andExpect(status().isNoContent());
        call(delete("/api/restaurants/991"), admin, null).andExpect(status().isOk());
        var before = publicationSnapshot();
        link(id, "991")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(publicationSnapshot()).isEqualTo(before);
    }

    @Test
    void linkRequiresAdministratorAndValidPositiveLongTarget() throws Exception {
        existingShop(991);
        String id = submitWithReviewPhoto();
        String url = "/api/admin/restaurant-requests/" + id + "/link-existing";
        mvc.perform(post(url).contentType("application/json").content("{\"restaurantId\":\"991\"}"))
                .andExpect(status().isUnauthorized());
        call(post(url), owner, Map.of("restaurantId", "991")).andExpect(status().isForbidden());
        for (String value : List.of("", "0", "-1", "1.2", "9223372036854775808")) {
            call(post(url), admin, Map.of("restaurantId", value))
                    .andExpect(status().isBadRequest());
        }
        call(post(url), admin, Map.of()).andExpect(status().isBadRequest());
        link("999999", "991").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM review_submissions", String.class))
                .isEqualTo("WAITING");
    }

    private void existingShop(long id) {
        jdbc.update(
                "INSERT INTO restaurants(id,name,category_code,address,created_by,updated_by)"
                        + " VALUES(?,'已收录餐厅','OTHER','原有地址',22,22)",
                id);
    }

    /** 作者已经点评过目标店时新增历史，正常替换计分代表，不能再次领取同店首评奖励。 */
    @Test
    void linkAddsReviewHistoryWithoutRepeatingExistingFirstReviewReward() throws Exception {
        existingShop(991);
        var existing = review("RECOMMENDED");
        existing.put("clientRequestId", UUID.randomUUID().toString());
        call(post("/api/restaurants/991/reviews"), owner, existing).andExpect(status().isCreated());
        String id = submitWithReviewPhoto();
        link(id, "991").andExpect(status().isOk());
        assertThat(count("reviews")).isEqualTo(2);
        assertThat(count("growth_rewards")).isEqualTo(1);
        mvc.perform(get("/api/restaurants/991/reviews"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.records[0].currentReview.recommendation")
                                .value("NEUTRAL"));
    }

    private ResultActions link(String id, String target) throws Exception {
        return call(
                post("/api/admin/restaurant-requests/" + id + "/link-existing"),
                admin,
                Map.of("restaurantId", target));
    }

    private Map<String, Object> approval(Map<String, Object> input) {
        var result = new LinkedHashMap<>(input);
        result.remove("clientRequestId");
        result.remove("photoIds");
        result.remove("review");
        return result;
    }

    private ResultActions approve(String id, Map<String, Object> input) throws Exception {
        return call(post("/api/admin/restaurant-requests/" + id + "/approve"), admin, input);
    }

    /** 同一待发布记录修改后保留首次来源和发布编号；图片交集不重绑，删除图与新图同事务替换。 */
    @ParameterizedTest
    @ValueSource(strings = {"RECOMMENDED", "NEUTRAL", "NOT_RECOMMENDED"})
    void ownerCanEditAttachedReviewAndPublishTheUpdatedContent(String attitude) throws Exception {
        String id = submitWithReviewPhoto();
        media(21, 11, "REVIEW");
        var original =
                jdbc.queryForMap(
                        "SELECT id,origin_request_id,HEX(request_hash)"
                            + " hash,BIN_TO_UUID(publish_request_id) uuid FROM review_submissions");
        var input = editInput(shop());
        input.put("name", "修改后的新店");
        var attached = review(attitude, "21", "20");
        attached.put("body", "修改后保留的真实用餐体验");
        attached.put("scores", Map.of("taste", 0, "value", 7.5, "service", 10, "ambience", 0.5));
        input.put("review", attached);
        call(put("/api/me/restaurant-requests/" + id), owner, input).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForMap(
                                "SELECT id,origin_request_id,HEX(request_hash)"
                                        + " hash,BIN_TO_UUID(publish_request_id) uuid FROM"
                                        + " review_submissions"))
                .isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT body FROM review_submissions", String.class))
                .isEqualTo("修改后保留的真实用餐体验");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT proposed_data FROM restaurant_change_requests",
                                String.class))
                .doesNotContain("review", "修改后保留的真实用餐体验");
        var before = publicationSnapshot();
        call(put("/api/me/restaurant-requests/" + id), owner, input).andExpect(status().isOk());
        assertThat(publicationSnapshot()).isEqualTo(before);
        approve(id, approval(input)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT recommendation FROM reviews", String.class))
                .isEqualTo(attitude);
        assertThat(jdbc.queryForObject("SELECT body FROM reviews", String.class))
                .isEqualTo("修改后保留的真实用餐体验");
        assertThat(
                        jdbc.queryForList(
                                "SELECT media_file_id FROM review_photos ORDER BY position",
                                Long.class))
                .containsExactly(21L, 20L);
        assertThat(
                        (BigDecimal)
                                jdbc.queryForMap("SELECT * FROM restaurant_review_statistics")
                                        .get("overall_score_sum"))
                .isEqualByComparingTo("4.5");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"omitted", "null"})
    void editingShopWithoutReviewPreservesOriginalReview(String mode) throws Exception {
        String id = submitWithReviewPhoto();
        var before = snapshot();
        var input = editInput(shop());
        if ("null".equals(mode)) {
            input.put("review", null);
        }
        call(put("/api/me/restaurant-requests/" + id), owner, input).andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(count("review_submission_photos")).isEqualTo(1);
    }

    @Test
    void plainPendingNewShopCanAttachReviewWhenEditing() throws Exception {
        String id =
                data(submit(shop()).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        var input = editInput(shop());
        input.put("review", review("NEUTRAL"));
        call(put("/api/me/restaurant-requests/" + id), owner, input).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT origin_request_id FROM review_submissions", String.class))
                .isEqualTo(id);
        approve(id, approval(input)).andExpect(status().isOk());
        assertThat(count("reviews")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "purpose", "expired", "bound", "missing", "deleting"})
    void badReplacementPhotoRollsBackShopAndReviewEdit(String failure) throws Exception {
        String id = submitWithReviewPhoto();
        if (!"missing".equals(failure)) {
            media(
                    21,
                    "foreign".equals(failure) ? 22 : 11,
                    "purpose".equals(failure) ? "RESTAURANT" : "REVIEW");
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
            case "deleting" ->
                    jdbc.update("UPDATE media_files SET status='PENDING_DELETE' WHERE id=21");
            default -> {}
        }
        var input = editInput(shop());
        input.put("name", "失败不应保存的店名");
        input.put("review", review("RECOMMENDED", "21"));
        var before = publicationSnapshot();
        call(put("/api/me/restaurant-requests/" + id), owner, input)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_NOT_BINDABLE"));
        assertThat(publicationSnapshot()).isEqualTo(before);
        verifyNoInteractions(storage);
    }

    @Test
    void replacingAndRemovingReviewPhotosSchedulesOnlyRemovedFilesForCleanup() throws Exception {
        String id = submitWithReviewPhoto();
        media(21, 11, "REVIEW");
        var input = editInput(shop());
        input.put("review", review("NEUTRAL", "21"));
        call(put("/api/me/restaurant-requests/" + id), owner, input).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=20", String.class))
                .isEqualTo("PENDING_DELETE");
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=21", String.class))
                .isEqualTo("BOUND");
        input.put("review", review("NEUTRAL"));
        call(put("/api/me/restaurant-requests/" + id), owner, input).andExpect(status().isOk());
        assertThat(count("review_submission_photos")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=21", String.class))
                .isEqualTo("PENDING_DELETE");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reviewUpdate", "photoInsert", "shopUpdate"})
    void editWriteFailureRollsBackAllContentAndPhotoChanges(String failure) throws Exception {
        String id = submitWithReviewPhoto();
        media(21, 11, "REVIEW");
        media(30, 11, "RESTAURANT");
        var input = editInput(shop());
        input.put("photoIds", List.of("30"));
        input.put("review", review("RECOMMENDED", "21"));
        var before = publicationSnapshot();
        var fault = new IllegalStateException("injected lifecycle edit failure");
        switch (failure) {
            case "reviewUpdate" -> doThrow(fault).when(submissionMapper).updateContent(any());
            case "photoInsert" ->
                    doThrow(fault).when(submissionMapper).insertPhoto(anyLong(), eq(0), anyLong());
            case "shopUpdate" ->
                    doThrow(fault)
                            .when(ownerRequests)
                            .updateContent(anyLong(), anyLong(), anyString(), any());
            default -> throw new IllegalArgumentException(failure);
        }
        call(put("/api/me/restaurant-requests/" + id), owner, input)
                .andExpect(status().isInternalServerError());
        assertThat(publicationSnapshot()).isEqualTo(before);
        reset(submissionMapper, ownerRequests);
        call(put("/api/me/restaurant-requests/" + id), owner, input).andExpect(status().isOk());
    }

    /** 沿用或替换点评都不能绕过食堂整体不可评规则，失败保留原申请及图片。 */
    @ParameterizedTest
    @ValueSource(strings = {"inherit", "replace"})
    void editingAttachedReviewCannotConvertShopToCanteen(String mode) throws Exception {
        String id = submitWithReviewPhoto();
        var input = editInput(shop());
        input.put("restaurantType", "CANTEEN");
        input.put("categoryCode", null);
        if ("replace".equals(mode)) {
            input.put("review", review("NEUTRAL", "20"));
        }
        var before = publicationSnapshot();
        call(put("/api/me/restaurant-requests/" + id), owner, input)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_SUBMISSION_STATE_CONFLICT"));
        assertThat(publicationSnapshot()).isEqualTo(before);
    }

    /** 扩展的新店DTO不能让纠错通过修改或重提附带点评。 */
    @ParameterizedTest
    @ValueSource(strings = {"edit", "resubmit"})
    void correctionLifecycleCannotAttachReview(String operation) throws Exception {
        jdbc.update("INSERT INTO restaurants(id,name,category_code) VALUES(991,'已有餐厅','OTHER')");
        String id =
                data(call(post("/api/restaurants/991/change-requests"), owner, shop())
                                .andExpect(status().isCreated())
                                .andReturn())
                        .path("id")
                        .asString();
        if ("resubmit".equals(operation)) {
            reject(id).andExpect(status().isOk());
        }
        var input = "edit".equals(operation) ? editInput(shop()) : shop();
        input.put("review", review("NEUTRAL"));
        var before = publicationSnapshot();
        if ("edit".equals(operation)) {
            call(put("/api/me/restaurant-requests/" + id), owner, input)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REVIEW_SUBMISSION_STATE_CONFLICT"));
        } else {
            resubmit(id, input)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RESTAURANT_INVALID_INPUT"));
        }
        assertThat(publicationSnapshot()).isEqualTo(before);
    }

    /** 两个新增点评写入口复用完整校验，非法嵌套对象不能仅保存店铺部分。 */
    @ParameterizedTest
    @ValueSource(strings = {"edit", "resubmit"})
    void lifecycleRejectsIncompleteNestedReviewAtomically(String operation) throws Exception {
        String id = submitWithReviewPhoto();
        if ("resubmit".equals(operation)) {
            reject(id).andExpect(status().isOk());
        }
        var input = "edit".equals(operation) ? editInput(shop()) : shop();
        input.put("name", "无效点评不应保存店名");
        input.put("review", Map.of("body", "只有正文缺少完整评分"));
        var before = publicationSnapshot();
        if ("edit".equals(operation)) {
            call(put("/api/me/restaurant-requests/" + id), owner, input)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REVIEW_INVALID_INPUT"));
        } else {
            resubmit(id, input)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REVIEW_INVALID_INPUT"));
        }
        assertThat(publicationSnapshot()).isEqualTo(before);
    }

    @Test
    void withdrawalRetainsOwnerContentAndIsIdempotentWithoutPublishing() throws Exception {
        String id = submitWithReviewPhoto();
        call(post("/api/me/restaurant-requests/" + id + "/withdraw"), owner, null)
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM review_submissions", String.class))
                .isEqualTo("WITHDRAWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT withdrawn_at FROM review_submissions", Instant.class))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=20", String.class))
                .isEqualTo("BOUND");
        var before = publicationSnapshot();
        call(post("/api/me/restaurant-requests/" + id + "/withdraw"), owner, null)
                .andExpect(status().isOk());
        assertThat(publicationSnapshot()).isEqualTo(before);
        call(get("/api/me/restaurant-requests/" + id), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewSubmission.body").value("真实用餐体验正文"))
                .andExpect(jsonPath("$.data.reviewSubmission.status").value("WITHDRAWN"));
        when(storage.read("joint-test", "joint/20.jpg")).thenReturn(new byte[] {1});
        call(get("/api/me/restaurant-requests/" + id + "/review/photos/0"), owner, null)
                .andExpect(status().isOk());
        call(put("/api/me/restaurant-requests/" + id), owner, editInput(shop()))
                .andExpect(status().isConflict());
        resubmit(id, shop()).andExpect(status().isConflict());
        approve(id, approval(shop())).andExpect(status().isConflict());
        reject(id).andExpect(status().isConflict());
        assertNoPublication();
    }

    @ParameterizedTest
    @ValueSource(strings = {"review", "shop", "rejectionNotice"})
    void withdrawalAndRejectionFaultsNeverPartiallyCommit(String failure) throws Exception {
        String id = submitWithReviewPhoto();
        var before = publicationSnapshot();
        var fault = new IllegalStateException("injected lifecycle failure");
        switch (failure) {
            case "review" -> doThrow(fault).when(submissionMapper).withdraw(anyLong(), anyLong());
            case "shop" -> doThrow(fault).when(ownerRequests).withdraw(anyLong(), anyLong());
            case "rejectionNotice" -> doThrow(fault).when(notices).insert(any());
            default -> throw new IllegalArgumentException(failure);
        }
        if ("rejectionNotice".equals(failure)) {
            reject(id).andExpect(status().isInternalServerError());
        } else {
            call(post("/api/me/restaurant-requests/" + id + "/withdraw"), owner, null)
                    .andExpect(status().isInternalServerError());
        }
        assertThat(publicationSnapshot()).isEqualTo(before);
    }

    /** 点评迁移而非复制；旧申请保留驳回快照，新申请继承或更新点评，最终只发布一次。 */
    @ParameterizedTest
    @ValueSource(strings = {"inherit", "replace"})
    void resubmissionTransfersOriginalReviewAndKeepsRetriesSafe(String mode) throws Exception {
        media(20, 11, "REVIEW");
        var original = shop();
        original.put("review", review("NEUTRAL", "20"));
        String id =
                data(submit(original).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        reject(id).andExpect(status().isOk());
        var oldRequest =
                jdbc.queryForMap(
                        "SELECT status,reject_reason,proposed_data,reviewed_at FROM"
                                + " restaurant_change_requests WHERE id=?",
                        id);
        var identity =
                jdbc.queryForMap(
                        "SELECT id,origin_request_id,HEX(request_hash)"
                            + " hash,BIN_TO_UUID(publish_request_id) uuid FROM review_submissions");
        var input = shop();
        if ("replace".equals(mode)) {
            media(21, 11, "REVIEW");
            input.put("review", review("NOT_RECOMMENDED", "21"));
        }
        String next =
                data(resubmit(id, input).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        assertThat(next).isNotEqualTo(id);
        assertThat(count("review_submissions")).isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT id,origin_request_id,HEX(request_hash)"
                                        + " hash,BIN_TO_UUID(publish_request_id) uuid FROM"
                                        + " review_submissions"))
                .isEqualTo(identity);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT restaurant_request_id FROM review_submissions",
                                String.class))
                .isEqualTo(next);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT status,reject_reason,proposed_data,reviewed_at FROM"
                                        + " restaurant_change_requests WHERE id=?",
                                id))
                .isEqualTo(oldRequest);
        call(get("/api/me/restaurant-requests/" + id), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewSubmission").doesNotExist())
                .andExpect(jsonPath("$.data.resubmittedRequestId").value(next));
        call(get("/api/me/restaurant-requests/" + id + "/review/photos/0"), owner, null)
                .andExpect(status().isNotFound());
        call(get("/api/me/restaurant-requests/" + next), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewSubmission.status").value("WAITING"));
        var before = publicationSnapshot();
        resubmit(id, input)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(next));
        assertThat(publicationSnapshot()).isEqualTo(before);
        resubmit(id, shop()).andExpect(status().isConflict());
        // 最初投稿重放仍返回原点评回执，不能在旧申请下重建或重新绑定原图片。
        submit(original)
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.data.reviewSubmission.id")
                                .value(identity.get("id").toString()));
        assertThat(publicationSnapshot()).isEqualTo(before);
        approve(next, approval(input)).andExpect(status().isOk());
        var published = publicationSnapshot();
        resubmit(id, input)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(next));
        assertThat(publicationSnapshot()).isEqualTo(published);
        assertThat(count("reviews")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT BIN_TO_UUID(client_request_id) FROM reviews", String.class))
                .isEqualToIgnoringCase(identity.get("uuid").toString());
        assertThat(jdbc.queryForObject("SELECT recommendation FROM reviews", String.class))
                .isEqualTo("replace".equals(mode) ? "NOT_RECOMMENDED" : "NEUTRAL");
        assertThat(count("notification_messages")).isEqualTo(2);
        verifyNoInteractions(storage);
    }

    @Test
    void plainRejectedNewShopCanAddReviewOnResubmission() throws Exception {
        String id =
                data(submit(shop()).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        reject(id).andExpect(status().isOk());
        var input = shop();
        input.put("review", review("NEUTRAL"));
        String next =
                data(resubmit(id, input).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT origin_request_id FROM review_submissions", String.class))
                .isEqualTo(next);
        approve(next, approval(input)).andExpect(status().isOk());
    }

    @Test
    void multipleRejectionsKeepOneReviewAcrossTheWholeChain() throws Exception {
        String first = submitWithReviewPhoto();
        reject(first).andExpect(status().isOk());
        var secondInput = shop();
        String second =
                data(resubmit(first, secondInput).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        reject(second).andExpect(status().isOk());
        String third =
                data(resubmit(second, shop()).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        resubmit(first, secondInput)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(second));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT restaurant_request_id FROM review_submissions",
                                String.class))
                .isEqualTo(third);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT origin_request_id FROM review_submissions", String.class))
                .isEqualTo(first);
        approve(third, approval(shop())).andExpect(status().isOk());
        assertThat(count("review_submissions")).isEqualTo(1);
        assertThat(count("reviews")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"move", "update", "invalidPhoto", "canteen"})
    void failedResubmissionPreservesOldApplicationReviewAndFiles(String failure) throws Exception {
        String id = submitWithReviewPhoto();
        reject(id).andExpect(status().isOk());
        media(21, "invalidPhoto".equals(failure) ? 22 : 11, "REVIEW");
        var input = shop();
        input.put("review", review("NOT_RECOMMENDED", "21"));
        if ("canteen".equals(failure)) {
            input.remove("review");
            input.put("restaurantType", "CANTEEN");
            input.put("categoryCode", null);
        }
        var fault = new IllegalStateException("injected review transfer failure");
        if ("move".equals(failure)) {
            doThrow(fault).when(submissionMapper).move(anyLong(), anyLong(), anyLong(), anyLong());
        } else if ("update".equals(failure)) {
            doThrow(fault).when(submissionMapper).updateContent(any());
        }
        var before = publicationSnapshot();
        resubmit(id, input)
                .andExpect(
                        "move".equals(failure) || "update".equals(failure)
                                ? status().isInternalServerError()
                                : status().isConflict());
        assertThat(publicationSnapshot()).isEqualTo(before);
        assertThat(count("restaurant_change_requests")).isEqualTo(1);
        if ("move".equals(failure) || "update".equals(failure)) {
            reset(submissionMapper);
            resubmit(id, input).andExpect(status().isCreated());
        }
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sameUuid", "differentUuid"})
    void concurrentResubmissionsCreateOnlyOneSuccessor(String mode) throws Exception {
        String id = submitWithReviewPhoto();
        reject(id).andExpect(status().isOk());
        var first = shop();
        var second = "sameUuid".equals(mode) ? first : shop();
        var results =
                race(
                        List.of(
                                () -> resubmit(id, first).andReturn(),
                                () -> resubmit(id, second).andReturn()));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(201, "sameUuid".equals(mode) ? 201 : 409);
        assertThat(count("restaurant_change_requests")).isEqualTo(2);
        assertThat(count("review_submissions")).isEqualTo(1);
    }

    @Test
    void concurrentApprovalAndWithdrawalCannotPartiallyPublish() throws Exception {
        String id = submitWithReviewPhoto();
        var results =
                race(
                        List.of(
                                () -> approve(id, approval(shop())).andReturn(),
                                () ->
                                        call(
                                                        post(
                                                                "/api/me/restaurant-requests/"
                                                                        + id
                                                                        + "/withdraw"),
                                                        owner,
                                                        null)
                                                .andReturn()));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(200, 409);
        String state =
                jdbc.queryForObject("SELECT status FROM restaurant_change_requests", String.class);
        assertThat(jdbc.queryForObject("SELECT status FROM review_submissions", String.class))
                .isEqualTo("APPROVED".equals(state) ? "PUBLISHED" : "WITHDRAWN");
        assertThat(count("reviews")).isEqualTo("APPROVED".equals(state) ? 1 : 0);
    }

    @Test
    void concurrentApprovalAndEditPublishOneConsistentVersion() throws Exception {
        String id = submitWithReviewPhoto();
        var input = editInput(shop());
        var changed = review("NOT_RECOMMENDED", "20");
        changed.put("body", "并发修改后的真实体验正文");
        input.put("review", changed);
        var results =
                race(
                        List.of(
                                () -> approve(id, approval(shop())).andReturn(),
                                () ->
                                        call(put("/api/me/restaurant-requests/" + id), owner, input)
                                                .andReturn()));
        assertThat(results.getFirst().getResponse().getStatus()).isEqualTo(200);
        int editStatus = results.get(1).getResponse().getStatus();
        assertThat(editStatus).isIn(200, 409);
        assertThat(jdbc.queryForObject("SELECT body FROM reviews", String.class))
                .isEqualTo(editStatus == 200 ? "并发修改后的真实体验正文" : "真实用餐体验正文");
        assertThat(count("reviews")).isEqualTo(1);
    }

    @Test
    void parentResubmissionRetryAndChildApprovalDoNotDeadlockOrMoveReviewBack() throws Exception {
        String id = submitWithReviewPhoto();
        reject(id).andExpect(status().isOk());
        var input = shop();
        String next =
                data(resubmit(id, input).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asString();
        var results =
                race(
                        List.of(
                                () -> resubmit(id, input).andReturn(),
                                () -> approve(next, approval(input)).andReturn()));
        assertThat(results.getFirst().getResponse().getStatus()).isEqualTo(201);
        assertThat(results.get(1).getResponse().getStatus()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT restaurant_request_id FROM review_submissions",
                                String.class))
                .isEqualTo(next);
        assertThat(count("reviews")).isEqualTo(1);
    }

    @Test
    void lifecycleEndpointsKeepOwnershipAndAdministratorBoundaries() throws Exception {
        String id = submitWithReviewPhoto();
        var edit = editInput(shop());
        edit.put("review", review("RECOMMENDED", "20"));
        for (String viewer : List.of(other, admin)) {
            call(put("/api/me/restaurant-requests/" + id), viewer, edit)
                    .andExpect(status().isNotFound());
            call(post("/api/me/restaurant-requests/" + id + "/withdraw"), viewer, null)
                    .andExpect(status().isNotFound());
            call(post("/api/me/restaurant-requests/" + id + "/resubmit"), viewer, shop())
                    .andExpect(status().isNotFound());
        }
        call(
                        post("/api/admin/restaurant-requests/" + id + "/reject"),
                        owner,
                        Map.of("reason", "信息不实"))
                .andExpect(status().isForbidden());
        assertNoPublication();
    }

    /** 使用同一隔离数据库并发发起真实HTTP服务流程，不在测试线程包裹事务。 */
    private List<MvcResult> race(List<Callable<MvcResult>> actions) throws Exception {
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(actions.size())) {
            var futures = new ArrayList<Future<MvcResult>>();
            for (var action : actions) {
                futures.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return action.call();
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

    private ResultActions reject(String id) throws Exception {
        return call(
                post("/api/admin/restaurant-requests/" + id + "/reject"),
                admin,
                Map.of("reason", "信息不实"));
    }

    private ResultActions resubmit(String id, Map<String, Object> input) throws Exception {
        return call(post("/api/me/restaurant-requests/" + id + "/resubmit"), owner, input);
    }

    private Map<String, Object> editInput(Map<String, Object> input) {
        var result = new LinkedHashMap<>(input);
        result.remove("clientRequestId");
        return result;
    }

    private String submitWithReviewPhoto() throws Exception {
        media(20, 11, "REVIEW");
        var input = shop();
        input.put("review", review("NEUTRAL", "20"));
        return data(submit(input).andExpect(status().isCreated()).andReturn())
                .path("id")
                .asString();
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
