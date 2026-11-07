package com.ginkgo.foodmap.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ginkgo.foodmap.restaurant.mapper.RestaurantReviewTargetMapper;
import com.ginkgo.foodmap.restaurant.service.RestaurantReviewTargetService;
import com.ginkgo.foodmap.review.mapper.MyReviewReadMapper;
import com.ginkgo.foodmap.review.mapper.ReviewPhotoMapper;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 真实MySQL验证私有读取、分组分页和删评闭环；随机回环临时库，只有Redis会话使用替身。 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0"
        })
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named = "review.integration", matches = "true")
class MyReviewApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @MockitoBean LoginSessionService sessions;
    @MockitoSpyBean MyReviewReadMapper reads;
    @MockitoSpyBean ReviewPhotoMapper photos;
    @MockitoSpyBean RestaurantReviewTargetMapper targets;
    @MockitoSpyBean RestaurantReviewTargetService targetService;
    private RestaurantReviewTargetService targetServiceImpl;
    private String owner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_my_reviews_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MyReviewApiIntegrationTest::password);
    }

    @BeforeEach
    void setup() {
        targetServiceImpl = AopTestUtils.getUltimateTargetObject(targetService);
        reset(sessions, reads, photos, targets, targetServiceImpl);
        for (String table :
                List.of(
                        "growth_rewards",
                        "growth_accounts",
                        "restaurant_search_outbox",
                        "review_replies",
                        "review_photos",
                        "review_request_receipts",
                        "reviews",
                        "media_files",
                        "restaurant_review_statistics",
                        "restaurants",
                        "user_roles",
                        "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (long id : List.of(11L, 22L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(?,?,?,?,TRUE)",
                    id,
                    "my_reviewer_" + id,
                    "my" + id + "@example.test",
                    "private-password");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",
                    id);
        }
        jdbc.update(
                "INSERT INTO restaurants(id,name,category_code,created_by) VALUES(1,'第一家店','OTHER',11),(2,'第二家店','OTHER',11)");
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        owner = token(11);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        if (!database.matches("ginkgo_my_reviews_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected database");
        }
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void authenticatesIdentityIgnoresClientOwnerAndDoesNotExposePrivateFields() throws Exception {
        review(101, 1, 11, 8, "2026-11-01 01:00:00");
        review(201, 2, 22, 9, "2026-11-02 01:00:00");
        mvc.perform(get("/api/me/reviews")).andExpect(status().isUnauthorized());
        var response =
                mvc.perform(
                                get("/api/me/reviews")
                                        .header("Authorization", "Bearer " + owner)
                                        .param("userId", "22")
                                        .param("authorUserId", "22")
                                        .param("offset", "999"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.author.id").value("11"))
                        .andExpect(jsonPath("$.data.records[0].restaurant.id").value("1"))
                        .andExpect(jsonPath("$.data.records[0].currentReview.id").value("101"))
                        .andExpect(jsonPath("$.data.totalGroups").value(1))
                        .andReturn();
        assertThat(response.getResponse().getContentAsString())
                .doesNotContain(
                        "private-password",
                        "passwordHash",
                        "credentialVersion",
                        "email",
                        "clientRequestId",
                        "requestHash",
                        "bucketName",
                        "storageKey",
                        "canDelete");
        mine(token(22))
                .andExpect(jsonPath("$.data.author.id").value("22"))
                .andExpect(jsonPath("$.data.records[0].currentReview.id").value("201"));
        mvc.perform(post("/api/me/reviews").header("Authorization", "Bearer " + owner))
                .andExpect(status().isMethodNotAllowed());
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=11");
        mine(owner).andExpect(status().isUnauthorized());
        assertThat(count("growth_accounts")).isZero();
    }

    @Test
    void groupsBeforePaginationUsesTimestampIdOrderAndKeepsZeroScores() throws Exception {
        review(101, 1, 11, 8, "2026-11-01 01:00:00.123456");
        review(102, 1, 11, 0, "2026-11-01 01:00:00.123456");
        review(103, 2, 11, 6, "2026-11-01 01:00:00.123456");
        review(201, 1, 22, 10, "2026-11-02 01:00:00");
        mine(owner)
                .andExpect(jsonPath("$.data.totalGroups").value(2))
                .andExpect(jsonPath("$.data.totalReviews").value(3))
                .andExpect(jsonPath("$.data.records[0].currentReview.id").value("103"))
                .andExpect(jsonPath("$.data.records[1].currentReview.id").value("102"))
                .andExpect(jsonPath("$.data.records[1].currentReview.overallScore").value(0))
                .andExpect(jsonPath("$.data.records[1].historyCount").value(1));
        mvc.perform(
                        get("/api/me/reviews")
                                .header("Authorization", "Bearer " + owner)
                                .param("pageSize", "1")
                                .param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].currentReview.id").value("102"))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void filtersMissingAndDeletedParentsBeforeCountsAndPaginationButKeepsClosedStores()
            throws Exception {
        for (int id = 3; id <= 11; id++) {
            jdbc.update(
                    "INSERT INTO restaurants(id,name,category_code,created_by) VALUES(?,?,'OTHER',11)",
                    id,
                    "店铺" + id);
        }
        for (int id = 1; id <= 11; id++) {
            review(100 + id, id, 11, 8, "2026-11-01 01:00:00");
        }
        review(999, 999, 11, 8, "2026-11-03 01:00:00");
        jdbc.update(
                "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=11");
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=10");
        mine(owner)
                .andExpect(jsonPath("$.data.records.length()").value(8))
                .andExpect(jsonPath("$.data.totalGroups").value(10))
                .andExpect(jsonPath("$.data.totalReviews").value(10))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.records[0].restaurant.id").value("10"))
                .andExpect(jsonPath("$.data.records[0].restaurant.businessStatus").value("CLOSED"));
        mvc.perform(
                        get("/api/me/reviews")
                                .header("Authorization", "Bearer " + owner)
                                .param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(2));
        verify(targets, times(2)).visible(anyList());
        jdbc.update("UPDATE restaurants SET deleted_at=NULL,delete_reason_code=NULL WHERE id=11");
        mine(owner)
                .andExpect(jsonPath("$.data.totalGroups").value(11))
                .andExpect(jsonPath("$.data.records[0].restaurant.id").value("11"));
    }

    @Test
    void emptyAndOutOfRangePagesValidateParametersAndSkipPhotoQueries() throws Exception {
        mine(owner)
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.pageSize").value(8));
        verifyNoInteractions(photos, targets);
        verify(reads, never()).counts(anyLong(), anyList());
        review(101, 1, 11, 8, "2026-11-01 01:00:00");
        mvc.perform(
                        get("/api/me/reviews")
                                .header("Authorization", "Bearer " + owner)
                                .param("page", "2147483647")
                                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalGroups").value(1))
                .andExpect(jsonPath("$.data.records").isEmpty());
        verify(reads, never()).page(anyLong(), anyList(), anyInt(), anyLong());
        verifyNoInteractions(photos);
        for (var pair :
                List.of(
                        new String[] {"page", "0"},
                        new String[] {"page", "-1"},
                        new String[] {"page", "2147483648"},
                        new String[] {"pageSize", "0"},
                        new String[] {"pageSize", "51"},
                        new String[] {"pageSize", "x"})) {
            mvc.perform(
                            get("/api/me/reviews")
                                    .header("Authorization", "Bearer " + owner)
                                    .param(pair[0], pair[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void reusesOrderedPhotosAndExistingHistoryCursor() throws Exception {
        review(101, 1, 11, 8, "2026-11-01 01:00:00");
        review(102, 1, 11, 9, "2026-11-02 01:00:00");
        jdbc.update(
                "INSERT INTO review_photos(review_id,position,media_file_id) VALUES(102,1,502),(102,0,501)");
        var response =
                mine(owner)
                        .andExpect(
                                jsonPath("$.data.records[0].currentReview.photos[0].url")
                                        .value("/api/reviews/102/photos/0"))
                        .andExpect(
                                jsonPath("$.data.records[0].currentReview.photos[1].id")
                                        .value("502"))
                        .andReturn();
        String cursor =
                json.readTree(response.getResponse().getContentAsString())
                        .get("data")
                        .get("records")
                        .get(0)
                        .get("nextHistoryCursor")
                        .asText();
        mvc.perform(get("/api/restaurants/1/reviews/authors/11/history").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentReviewId").value("102"))
                .andExpect(jsonPath("$.data.records[0].id").value("101"));
        mvc.perform(get("/api/restaurants/2/reviews/authors/11/history").param("cursor", cursor))
                .andExpect(status().isBadRequest());
        verify(photos).references(List.of(102L));
    }

    @Test
    void latestAndHistoryDeletionRefreshGroupsWithoutRevokingExperience() throws Exception {
        long first = publish(owner, 1), latest = publish(owner, 1), second = publish(owner, 2);
        mine(owner)
                .andExpect(jsonPath("$.data.totalGroups").value(2))
                .andExpect(jsonPath("$.data.totalReviews").value(3));
        mvc.perform(delete("/api/reviews/" + latest).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        var result = mine(owner).andExpect(jsonPath("$.data.totalReviews").value(2)).andReturn();
        assertThat(
                        json.readTree(result.getResponse().getContentAsString())
                                .get("data")
                                .get("records")
                                .toString())
                .contains("\"id\":\"" + first + "\"");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT experience FROM growth_accounts WHERE user_id=11",
                                Long.class))
                .isEqualTo(10);
        long newer = publish(owner, 1);
        mvc.perform(delete("/api/reviews/" + first).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        mine(owner)
                .andExpect(
                        jsonPath("$.data.records[0].currentReview.id").value(Long.toString(newer)))
                .andExpect(jsonPath("$.data.records[0].historyCount").value(0));
        mvc.perform(delete("/api/reviews/" + newer).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        mine(owner)
                .andExpect(jsonPath("$.data.totalGroups").value(1))
                .andExpect(
                        jsonPath("$.data.records[0].currentReview.id")
                                .value(Long.toString(second)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT experience FROM growth_accounts WHERE user_id=11",
                                Long.class))
                .isEqualTo(10);
        mvc.perform(delete("/api/reviews/" + second).header("Authorization", "Bearer " + token(22)))
                .andExpect(status().isForbidden());
    }

    @Test
    void sameReadSnapshotSurvivesConcurrentParentDeletion() throws Exception {
        review(101, 1, 11, 8, "2026-11-01 01:00:00");
        doAnswer(
                        invocation -> {
                            CompletableFuture.runAsync(
                                            () ->
                                                    jdbc.update(
                                                            "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=1"))
                                    .get(5, TimeUnit.SECONDS);
                            return invocation.callRealMethod();
                        })
                .when(targetServiceImpl)
                .visible(anyList());
        mine(owner)
                .andExpect(jsonPath("$.data.totalGroups").value(1))
                .andExpect(jsonPath("$.data.records[0].restaurant.name").value("第一家店"));
        reset(targetServiceImpl);
        mine(owner)
                .andExpect(jsonPath("$.data.totalGroups").value(0))
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test
    void batchesTargetMetadataAndCreatesAuthorAccessIndex() throws Exception {
        var stores = new ArrayList<Object[]>();
        var reviews = new ArrayList<Object[]>();
        for (int id = 3; id <= 503; id++) {
            stores.add(new Object[] {id, "批量店" + id});
            reviews.add(new Object[] {id, id, UUID.randomUUID().toString()});
        }
        jdbc.batchUpdate(
                "INSERT INTO restaurants(id,name,category_code,created_by) VALUES(?,?,'OTHER',11)",
                stores);
        jdbc.batchUpdate(
                "INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash) VALUES(?,?,11,8,8,8,8,TRUE,'批量点评正文',UUID_TO_BIN(?),UNHEX(SHA2('test',256)))",
                reviews);
        mine(owner)
                .andExpect(jsonPath("$.data.totalGroups").value(501))
                .andExpect(jsonPath("$.data.records.length()").value(8));
        verify(targets, times(2)).visible(anyList());
        verify(reads, times(1)).page(eq(11L), anyList(), eq(8), eq(0L));
        verify(photos, times(1)).references(anyList());
        String columns =
                jdbc.queryForObject(
                        "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='reviews' AND index_name='idx_reviews_author_restaurant_created_id'",
                        String.class);
        assertThat(columns).isEqualTo("author_user_id,restaurant_id,created_at,id");
    }

    @Test
    void readAndAuthenticationFailuresDoNotReturnFakeEmptySuccess() throws Exception {
        doThrow(new DataAccessResourceFailureException("test-only")).when(reads).targets(11);
        mine(owner).andExpect(status().isInternalServerError());
        reset(reads);
        when(sessions.isActive(anyString(), anyLong(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("test-only"));
        mine(owner).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(reads);
    }

    private ResultActions mine(String token) throws Exception {
        return mvc.perform(get("/api/me/reviews").header("Authorization", "Bearer " + token));
    }

    private void review(long id, long restaurant, long author, double score, String time) {
        jdbc.update(
                "INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash,created_at) VALUES(?,?,?,?,?,?,?,TRUE,'本人点评测试正文',UUID_TO_BIN(?),UNHEX(SHA2('private-hash',256)),?)",
                id,
                restaurant,
                author,
                score,
                score,
                score,
                score,
                UUID.randomUUID().toString(),
                LocalDateTime.parse(time.replace(' ', 'T')));
    }

    private long publish(String token, long restaurant) throws Exception {
        var body =
                Map.of(
                        "clientRequestId",
                        UUID.randomUUID().toString(),
                        "scores",
                        Map.of("taste", 8, "value", 8, "service", 8, "ambience", 8),
                        "recommended",
                        true,
                        "body",
                        "我的点评完整闭环测试正文");
        var result =
                mvc.perform(
                                post("/api/restaurants/" + restaurant + "/reviews")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isCreated())
                        .andReturn();
        return Long.parseLong(
                json.readTree(result.getResponse().getContentAsString())
                        .get("data")
                        .get("reviewId")
                        .asText());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String token(long user) {
        var now = Instant.now();
        var jwt = authentication.getJwt();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(jwt.getIssuer())
                        .audience(List.of(jwt.getAudience()))
                        .subject(Long.toString(user))
                        .issuedAt(now.minusSeconds(60))
                        .expiresAt(now.plusSeconds(600))
                        .id(UUID.randomUUID().toString())
                        .claim("credential_version", 0)
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static String password() {
        return Objects.requireNonNull(
                System.getenv("REVIEW_TEST_DB_PASSWORD"), "Set isolated review test password");
    }

    private static String url(String name) {
        String server =
                System.getenv()
                        .getOrDefault("REVIEW_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback only");
        }
        return server
                + name
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), "root", password());
    }
}
