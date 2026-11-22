package com.foodmap.restaurant;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foodmap.security.TestJwtKeys;
import com.foodmap.security.config.AuthenticationProperties;
import com.foodmap.security.service.LoginSessionService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 隔离MySQL验证食堂直接点评、互动与评分；不写开发业务库。 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "app.file.oss.bucket=test-window-bucket",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0"
        })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named = "review.integration", matches = "true")
class CanteenReviewApiIntegrationTest {
    @Test
    void canteenReviewsPublishReplayReplaceAndDeleteWithUnifiedStatistics() throws Exception {
        jdbc.update(
                "INSERT INTO"
                    + " restaurants(id,name,restaurant_type,price_kind,address,latitude,longitude,needs_location,business_status,created_by)"
                    + " VALUES(901,'集成食堂','CANTEEN','UNKNOWN','测试地址',30,104,FALSE,'OPERATING',22)");
        String request = reviewBody(8);
        String json =
                mvc.perform(
                                post("/api/restaurants/901/reviews")
                                        .header("Authorization", "Bearer " + owner)
                                        .contentType("application/json")
                                        .content(request))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.restaurantId").value("901"))
                        .andExpect(jsonPath("$.data.windowId").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String first =
                new tools.jackson.databind.ObjectMapper()
                        .readTree(json)
                        .path("data")
                        .path("reviewId")
                        .asString();
        mvc.perform(
                        post("/api/restaurants/901/reviews")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        String secondJson =
                mvc.perform(
                                post("/api/restaurants/901/reviews")
                                        .header("Authorization", "Bearer " + owner)
                                        .contentType("application/json")
                                        .content(reviewBody(6)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String second =
                new tools.jackson.databind.ObjectMapper()
                        .readTree(secondJson)
                        .path("data")
                        .path("reviewId")
                        .asString();
        mvc.perform(get("/api/restaurants/901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(6))
                .andExpect(jsonPath("$.data.participantCount").value(1))
                .andExpect(jsonPath("$.data.reviewCount").value(2));
        mvc.perform(get("/api/restaurants/901/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalReviews").value(2));
        mvc.perform(delete("/api/reviews/" + second).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/restaurants/901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(8));
        mvc.perform(delete("/api/reviews/" + first).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/restaurants/901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participantCount").value(0));
        mvc.perform(get("/api/canteen-windows/1").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/canteen-windows/1/reviews")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content(reviewBody(8)))
                .andExpect(status().isNotFound());
    }

    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @Autowired PlatformTransactionManager transactions;

    @Autowired SqlSessionTemplate sql;
    @MockitoBean LoginSessionService sessions;
    @MockitoBean com.foodmap.file.storage.ImageStorageClient photoStorage;

    private String owner;
    private String other;

    /** 开启集成测试仍必须显式提供回环地址，拒绝连接开发业务库。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "foodmap_window_api_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", CanteenReviewApiIntegrationTest::password);
    }

    @BeforeEach
    void setup() {
        when(sessions.isActive(org.mockito.ArgumentMatchers.anyString(), anyLong(), anyLong()))
                .thenReturn(true);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=11", Long.class) == 0) {
            for (long user : List.of(11L, 22L)) {
                jdbc.update(
                        "INSERT INTO users(id,username,email,password_hash,email_verified)"
                                + " VALUES(?,?,?,?,TRUE)",
                        user,
                        "window_" + user,
                        "window" + user + "@example.test",
                        "test-only");
                jdbc.update(
                        "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE"
                                + " code=?",
                        user,
                        user == 22 ? "ROLE_ADMIN" : "ROLE_USER");
            }
        }
        owner = token(11);
        other = token(22);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (created) {
            drop(database);
        }
    }

    private String reviewBody(int score) {
        return "{\"clientRequestId\":\""
                + UUID.randomUUID()
                + "\",\"scores\":{\"taste\":"
                + score
                + ",\"value\":"
                + score
                + ",\"service\":"
                + score
                + ",\"ambience\":"
                + score
                + "},\"recommendation\":\"RECOMMENDED\",\"body\":\"好\"}";
    }

    private String token(long user) {
        var jwt = authentication.getJwt();
        var now = Instant.now();
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

    private static String password() {
        return System.getenv().getOrDefault("REVIEW_TEST_DB_PASSWORD", "");
    }

    private static String url(String name) {
        String server = System.getenv("REVIEW_TEST_DB_SERVER");
        if (server == null
                || !server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Expected explicit loopback test server");
        }
        return server
                + name
                + "?serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), "root", password());
    }

    private static void drop(String name) throws SQLException {
        if (!name.matches("foodmap_window_api_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + name + "`");
        }
    }
}
