package com.foodmap.restaurant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** 隔离MySQL验证管理员商户相册与媒体生命周期；不写开发业务库。 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "app.file.oss.bucket=test-restaurant-bucket",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0"
        })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named = "review.integration", matches = "true")
class RestaurantPhotoManagementApiIntegrationTest {
    private static final java.util.concurrent.atomic.AtomicLong ids =
            new java.util.concurrent.atomic.AtomicLong(10000);
    private long shop;

    void fixture() {
        shop = ids.incrementAndGet();
        jdbc.update(
                "INSERT INTO"
                    + " restaurants(id,name,category_code,price_kind,latitude,longitude,needs_location,created_by)"
                    + " VALUES(?,'相册店','OTHER','UNKNOWN',30,104,FALSE,22)",
                shop);
        org.mockito.Mockito.reset(photoStorage);
        when(photoStorage.read(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new byte[] {1, 2, 3});
    }

    @Test
    void savesPhotosAndCoverAtomicallyReplaysAndCleansOnlyRemovedAdminFiles() throws Exception {
        long first = file(22, "RESTAURANT", "TEMPORARY"),
                second = file(22, "RESTAURANT", "TEMPORARY");
        String selection = "[{\"fileId\":\"" + first + "\"},{\"fileId\":\"" + second + "\"}]";
        save(selection, other).andExpect(status().isOk());
        var photoIds = photoIds();
        assertThat(photoIds).hasSize(2);
        mvc.perform(get("/api/restaurants/" + shop + "/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].cover").value(true));
        mvc.perform(get("/api/restaurants/" + shop + "/photos/" + photoIds.getFirst()))
                .andExpect(status().isOk());
        save(selection, other).andExpect(status().isOk());
        assertThat(photoIds()).isEqualTo(photoIds);
        save(
                        "[{\"photoId\":\""
                                + photoIds.getLast()
                                + "\"},{\"photoId\":\""
                                + photoIds.getFirst()
                                + "\"}]",
                        other)
                .andExpect(status().isOk());
        assertThat(photoIds()).containsExactly(photoIds.getLast(), photoIds.getFirst());
        save("[{\"photoId\":\"" + photoIds.getLast() + "\"}]", other).andExpect(status().isOk());
        assertThat(state(first)).isEqualTo("PENDING_DELETE");
        assertThat(state(second)).isEqualTo("BOUND");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT position FROM restaurant_photos WHERE restaurant_id=?",
                                Integer.class,
                                shop))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_search_outbox WHERE"
                                    + " restaurant_id=?",
                                Integer.class,
                                shop))
                .isEqualTo(4);
    }

    @Test
    void rejectsForeignFilesAndPhotosWithoutSavingPartialRestaurantOrMediaState() throws Exception {
        long valid = file(22, "RESTAURANT", "TEMPORARY"),
                foreign = file(11, "RESTAURANT", "TEMPORARY");
        save("[{\"fileId\":\"" + valid + "\"},{\"fileId\":\"" + foreign + "\"}]", other)
                .andExpect(status().isConflict());
        assertThat(photoIds()).isEmpty();
        assertThat(state(valid)).isEqualTo("TEMPORARY");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT name FROM restaurants WHERE id=?", String.class, shop))
                .isEqualTo("相册店");
        save("[{\"photoId\":\"999999\"}]", other).andExpect(status().isConflict());
        save("[{\"fileId\":\"" + valid + "\"}]", owner).andExpect(status().isForbidden());
        save("[{\"photoId\":\"1\",\"fileId\":\"2\"}]", other).andExpect(status().isBadRequest());
        long wrong = file(22, "REVIEW", "TEMPORARY");
        save("[{\"fileId\":\"" + wrong + "\"}]", other).andExpect(status().isConflict());
    }

    @Test
    void candidatesOnlyIncludeSameShopAndAdoptedCopiesOutliveOriginalReview() throws Exception {
        long review = ids.incrementAndGet(), original = file(11, "REVIEW", "BOUND");
        jdbc.update(
                "INSERT INTO"
                    + " reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,recommendation,body,client_request_id,request_hash)"
                    + " VALUES(?,?,11,8,8,8,8,'RECOMMENDED','原点评照片',UUID_TO_BIN(UUID()),UNHEX(REPEAT('ab',32)))",
                review,
                shop);
        jdbc.update(
                "INSERT INTO review_photos(review_id,position,media_file_id) VALUES(?,0,?)",
                review,
                original);
        mvc.perform(
                        get("/api/admin/restaurants/" + shop + "/review-photos")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].reviewId").value(Long.toString(review)));
        mvc.perform(
                        get("/api/admin/restaurants/" + shop + "/review-photos")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/admin/restaurants/" + shop + "/review-photos")
                                .param("page", "0")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isBadRequest());
        long copy = file(22, "RESTAURANT", "TEMPORARY");
        save("[{\"fileId\":\"" + copy + "\"}]", other).andExpect(status().isOk());
        jdbc.update("DELETE FROM review_photos WHERE review_id=?", review);
        jdbc.update("DELETE FROM reviews WHERE id=?", review);
        mvc.perform(get("/api/restaurants/" + shop + "/photos/" + photoIds().getFirst()))
                .andExpect(status().isOk());
        assertThat(state(copy)).isEqualTo("BOUND");
        mvc.perform(
                        get("/api/admin/restaurants/" + shop + "/review-photos")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty());
        mvc.perform(
                        get("/api/admin/restaurants/999999/review-photos")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
    }

    @Test
    void removingApprovedSubmissionPhotoPreservesItsPrivateHistoricalFile() throws Exception {
        long request = ids.incrementAndGet(), file = file(11, "RESTAURANT", "BOUND");
        jdbc.update(
                "INSERT INTO"
                    + " restaurant_change_requests(id,restaurant_id,request_type,submitted_by,client_request_id,request_hash,proposed_data,status,reviewed_by,reviewed_at,approved_data)"
                    + " VALUES(?,?,'CREATE',11,UUID(),UNHEX(REPEAT('ab',32)),'{}','APPROVED',22,NOW(6),'{}')",
                request,
                shop);
        jdbc.update(
                "INSERT INTO restaurant_change_photos(request_id,position,media_file_id)"
                    + " VALUES(?,0,?)",
                request,
                file);
        jdbc.update(
                "INSERT INTO"
                    + " restaurant_photos(restaurant_id,position,media_file_id,source_request_id)"
                    + " VALUES(?,0,?,?)",
                shop,
                file,
                request);
        save("[]", other).andExpect(status().isOk());
        assertThat(photoIds()).isEmpty();
        assertThat(state(file)).isEqualTo("BOUND");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_change_photos WHERE request_id=?",
                                Integer.class,
                                request))
                .isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions save(String photos, String token)
            throws Exception {
        return mvc.perform(
                put("/api/restaurants/" + shop)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(
                                "{\"name\":\"更新相册店\",\"restaurantType\":\"REGULAR\",\"supportsDineIn\":null,\"supportsDelivery\":null,\"categoryCode\":\"OTHER\",\"priceKind\":\"UNKNOWN\",\"pricePerPerson\":null,\"latitude\":30,\"longitude\":104,\"locationConfirmed\":true,\"businessStatus\":\"OPERATING\",\"tags\":[],\"photos\":"
                                        + photos
                                        + "}"));
    }

    private long file(long owner, String purpose, String status) {
        long id = ids.incrementAndGet();
        jdbc.update(
                "INSERT INTO"
                    + " media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,purpose,expires_at,bound_at)"
                    + " VALUES(?,?,'test-restaurant-bucket',?,'image/jpeg',100,10,10,UNHEX(REPEAT('ab',32)),?,?,IF(?='TEMPORARY',DATE_ADD(NOW(6),INTERVAL"
                    + " 1 DAY),NULL),IF(?='BOUND',NOW(6),NULL))",
                id,
                owner,
                "restaurant/test-" + id + ".jpg",
                status,
                purpose,
                status,
                status);
        return id;
    }

    private List<Long> photoIds() {
        return jdbc.queryForList(
                "SELECT id FROM restaurant_photos WHERE restaurant_id=? ORDER BY position",
                Long.class,
                shop);
    }

    private String state(long file) {
        return jdbc.queryForObject("SELECT status FROM media_files WHERE id=?", String.class, file);
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
        database = "foodmap_photo_manage_it_" + UUID.randomUUID().toString().replace("-", "");
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
        registry.add(
                "spring.datasource.password",
                RestaurantPhotoManagementApiIntegrationTest::password);
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
        fixture();
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (created) {
            drop(database);
        }
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
        if (!name.matches("foodmap_photo_manage_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + name + "`");
        }
    }
}
