package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 真正通过JWT安全链和MySQL验证收藏、隐私及并发幂等，仅替换Redis会话并使用随机隔离库。 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0"
        })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named = "favorites.integration", matches = "true")
class RestaurantFavoriteApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @MockitoBean LoginSessionService sessions;
    private String owner, other;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_favorites_api_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", RestaurantFavoriteApiIntegrationTest::username);
        registry.add("spring.datasource.password", RestaurantFavoriteApiIntegrationTest::password);
    }

    @BeforeEach
    void setup() {
        reset(sessions);
        db.update("DELETE FROM restaurant_favorites");
        db.update("DELETE FROM restaurants");
        db.update("DELETE FROM user_roles");
        db.update("DELETE FROM users");
        for (long id : List.of(11L, 22L)) {
            db.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified,bio) VALUES(?,?,?,'fixture-hash',TRUE,'简介')",
                    id,
                    "favorite_" + id,
                    id + "@example.test");
            db.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",
                    id);
        }
        db.update(
                "INSERT INTO restaurants(id,name,category_code) VALUES(1,'普通店','OTHER'),(3,'保留历史的失效店','OTHER')");
        db.update(
                "INSERT INTO restaurants(id,name,category_code,restaurant_type) VALUES(2,'食堂',NULL,'CANTEEN')");
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        owner = token(11);
        other = token(22);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        if (!database.matches("ginkgo_favorites_api_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void requiresLoginForEveryReadAndWrite() throws Exception {
        for (String path :
                List.of(
                        "/api/restaurants/1/favorite",
                        "/api/me/favorites",
                        "/api/users/11/favorites",
                        "/api/me/favorites/visibility")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(put("/api/restaurants/1/favorite")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/restaurants/1/favorite")).andExpect(status().isUnauthorized());
        mvc.perform(
                        patch("/api/me/favorites/visibility")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"favoritesPublic\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatOperationsKeepTimeAndNeverAcceptAnotherUser() throws Exception {
        mvc.perform(get("/api/restaurants/1/favorite").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(false));
        add(1, owner);
        var before = db.queryForMap("SELECT * FROM restaurant_favorites WHERE user_id=11");
        mvc.perform(
                        put("/api/restaurants/1/favorite?userId=22")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(db.queryForMap("SELECT * FROM restaurant_favorites WHERE user_id=11"))
                .isEqualTo(before);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_favorites WHERE user_id=22",
                                Long.class))
                .isZero();
        for (int i = 0; i < 2; i++) {
            mvc.perform(
                            delete("/api/restaurants/1/favorite")
                                    .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.favorited").value(false));
        }
        add(2, owner);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_messages", Long.class))
                .isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM growth_rewards", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM restaurant_search_outbox", Long.class))
                .isZero();
    }

    @Test
    void invalidTargetsAndPaginationAreRejected() throws Exception {
        for (String id : List.of("0", "01", "-1", "abc", "9223372036854775808")) {
            mvc.perform(
                            put("/api/restaurants/" + id + "/favorite")
                                    .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/restaurants/999/favorite").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        for (var pair :
                List.of(
                        new String[] {"page", "0"},
                        new String[] {"pageSize", "51"},
                        new String[] {"page", "2147483648"})) {
            mvc.perform(
                            get("/api/me/favorites")
                                    .param(pair[0], pair[1])
                                    .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void deletedStoresRemainOnlyInOwnListAndCanBeCancelled() throws Exception {
        add(1, owner);
        add(3, owner);
        removeStore();
        mvc.perform(get("/api/me/favorites").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].available").value(false))
                .andExpect(jsonPath("$.data.records[0].name").isEmpty());
        mvc.perform(put("/api/restaurants/3/favorite").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/restaurants/3/favorite").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        visibility(true);
        mvc.perform(get("/api/users/11/favorites").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("普通店"));
        mvc.perform(
                        delete("/api/restaurants/3/favorite")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        restoreStore();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_favorites WHERE restaurant_id=3",
                                Long.class))
                .isZero();
    }

    @Test
    void unremovedFavoriteReturnsAfterStoreRestoredAndClosedIsStillVisible() throws Exception {
        add(3, owner);
        removeStore();
        restoreStore();
        db.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=3");
        mvc.perform(get("/api/me/favorites").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].available").value(true))
                .andExpect(jsonPath("$.data.records[0].businessStatus").value("CLOSED"));
    }

    @Test
    void privacyDefaultsPrivateAndRevocationAppliesToNextRequest() throws Exception {
        add(1, owner);
        mvc.perform(get("/api/users/11/favorites").header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RESTAURANT_FAVORITES_PRIVATE"));
        mvc.perform(get("/api/users/11/favorites").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        var before = db.queryForMap("SELECT * FROM users WHERE id=11");
        visibility(true);
        var after = db.queryForMap("SELECT * FROM users WHERE id=11");
        assertThat(after.get("credential_version")).isEqualTo(before.get("credential_version"));
        assertThat(after.get("bio")).isEqualTo(before.get("bio"));
        assertThat(after.get("password_hash")).isEqualTo(before.get("password_hash"));
        var version = after.get("row_version");
        visibility(true);
        assertThat(db.queryForObject("SELECT row_version FROM users WHERE id=11", Long.class))
                .isEqualTo(((Number) version).longValue());
        mvc.perform(get("/api/users/11/favorites").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk());
        visibility(false);
        mvc.perform(get("/api/users/11/favorites").header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/999/favorites").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void privacyRejectsCoercionUnknownFieldsAndMissingValue() throws Exception {
        for (String body :
                List.of(
                        "{}",
                        "{\"favoritesPublic\":null}",
                        "{\"favoritesPublic\":1}",
                        "{\"favoritesPublic\":\"true\"}",
                        "{\"favoritesPublic\":true,\"userId\":22}")) {
            mvc.perform(
                            patch("/api/me/favorites/visibility")
                                    .header("Authorization", "Bearer " + owner)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        get("/api/me/favorites/visibility?userId=22")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("11"))
                .andExpect(jsonPath("$.data.favoritesPublic").value(false));
    }

    @Test
    void paginationUsesStableTimeAndIdBeforeLimit() throws Exception {
        add(1, owner);
        add(2, owner);
        add(3, owner);
        db.update("UPDATE restaurant_favorites SET created_at='2026-11-07 00:00:00'");
        mvc.perform(
                        get("/api/me/favorites?page=2&pageSize=1")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].restaurantId").value("2"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3));
        mvc.perform(
                        get("/api/me/favorites?page=2147483647&pageSize=50")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    void concurrentDuplicateFavoritesProduceOneRelation() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 6; i++) {
                jobs.add(
                        pool.submit(
                                () -> {
                                    try {
                                        add(1, owner);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }));
            }
            for (var job : jobs) {
                job.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_favorites WHERE user_id=11 AND restaurant_id=1",
                                Long.class))
                .isEqualTo(1);
    }

    private void add(long id, String token) throws Exception {
        mvc.perform(
                        put("/api/restaurants/" + id + "/favorite")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true));
    }

    private void visibility(boolean value) throws Exception {
        mvc.perform(
                        patch("/api/me/favorites/visibility")
                                .header("Authorization", "Bearer " + owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"favoritesPublic\":" + value + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favoritesPublic").value(value));
    }

    private void removeStore() {
        db.update(
                "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),deleted_by=11,delete_reason_code='ADMIN_REMOVAL' WHERE id=3");
    }

    private void restoreStore() {
        db.update(
                "UPDATE restaurants SET deleted_at=NULL,deleted_by=NULL,delete_reason_code=NULL WHERE id=3");
    }

    private String token(long id) {
        var now = Instant.now();
        var jwt = authentication.getJwt();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(jwt.getIssuer())
                        .audience(List.of(jwt.getAudience()))
                        .subject(Long.toString(id))
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

    private static String username() {
        return System.getenv().getOrDefault("FAVORITES_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        return Objects.requireNonNull(
                System.getenv("FAVORITES_TEST_DB_PASSWORD"), "Set isolated test password");
    }

    private static String url(String name) {
        var server =
                System.getenv()
                        .getOrDefault("FAVORITES_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback only");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), username(), password());
    }
}
