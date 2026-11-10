package com.ginkgo.foodmap.restaurant;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;

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

/** 隔离MySQL验证窗口身份、点评互动、评分汇总与生命周期；不写开发业务库。 */
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
class CanteenWindowApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @Autowired PlatformTransactionManager transactions;

    @Autowired SqlSessionTemplate sql;
    @MockitoBean LoginSessionService sessions;
    @MockitoBean com.ginkgo.foodmap.file.storage.OssStorageClient photoStorage;

    private String owner;
    private String other;

    /** 开启集成测试仍必须显式提供回环地址，拒绝连接开发业务库。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_window_api_it_" + UUID.randomUUID().toString().replace("-", "");
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
        registry.add("spring.datasource.password", CanteenWindowApiIntegrationTest::password);
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

    /** 同值ID的窗口和餐厅仍是独立对象；重复点评仅替换本窗口代表。 */
    @Test
    void windowLifecycleStatisticsAndReviewHistory() throws Exception {
        jdbc.update(
                "INSERT INTO"
                    + " restaurants(id,name,restaurant_type,price_kind,address,latitude,longitude,needs_location,business_status,created_by)"
                    + " VALUES(901,'集成食堂','CANTEEN','UNKNOWN','测试地址',30,104,FALSE,'OPERATING',22)");
        String request =
                "{\"name\":\"测试窗口\",\"floorNumber\":1,\"priceKind\":\"UNKNOWN\",\"businessStatus\":\"OPERATING\",\"clientRequestId\":\""
                        + UUID.randomUUID()
                        + "\"}";
        mvc.perform(
                        post("/api/admin/restaurants/901/windows")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content(request))
                .andExpect(status().isForbidden());
        String json =
                mvc.perform(
                                post("/api/admin/restaurants/901/windows")
                                        .header("Authorization", "Bearer " + other)
                                        .contentType("application/json")
                                        .content(request))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id =
                new tools.jackson.databind.ObjectMapper()
                        .readTree(json)
                        .path("data")
                        .path("id")
                        .asString();
        mvc.perform(
                        post("/api/admin/restaurants/901/windows")
                                .header("Authorization", "Bearer " + other)
                                .contentType("application/json")
                                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.replayed").value(true));
        mvc.perform(get("/api/restaurants/901/windows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id));
        String review = publishReview(id, 8);
        publishReview(id, 4);
        mvc.perform(get("/api/canteen-windows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(4))
                .andExpect(jsonPath("$.data.participantCount").value(1))
                .andExpect(jsonPath("$.data.reviewCount").value(2));
        mvc.perform(get("/api/canteen-windows/" + id + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalGroups").value(1))
                .andExpect(jsonPath("$.data.records[0].historyCount").value(1));
        mvc.perform(get("/api/canteen-windows/" + id + "/reviews/authors/11/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(review));
        mvc.perform(get("/api/me/reviews").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].restaurant.targetType").value("WINDOW"));
        mvc.perform(get("/api/restaurants/901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(4));
        mvc.perform(
                        post("/api/restaurants/901/reviews")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content(reviewBody(7)))
                .andExpect(status().isConflict());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/admin/canteen-windows/" + id)
                                .header("Authorization", "Bearer " + other)
                                .contentType("application/json")
                                .content(request.replace("OPERATING", "CLOSED")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/restaurants/901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").isEmpty());
        mvc.perform(
                        post("/api/canteen-windows/" + id + "/reviews")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content(reviewBody(7)))
                .andExpect(status().isConflict());
        mvc.perform(
                        delete("/api/admin/canteen-windows/" + id)
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/canteen-windows/" + id)).andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/admin/canteen-windows/" + id + "/restore")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/canteen-windows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCount").value(2));
    }

    /** 两个ID数值相同的对象必须分组、分页和计奖隔离，不能串用历史游标。 */
    @Test
    void sameNumericIdentityDoesNotMixRestaurantAndWindow() throws Exception {
        String window = createWindow(910, "身份隔离窗口");
        jdbc.update(
                "INSERT INTO"
                    + " restaurants(id,name,restaurant_type,category_code,price_kind,address,latitude,longitude,needs_location,business_status,created_by)"
                    + " VALUES(?,'同号普通餐厅','REGULAR','OTHER','UNKNOWN','测试',30,104,FALSE,'OPERATING',22)",
                Long.parseLong(window));
        publishReview(window, 8);
        publishReview(window, 6);
        mvc.perform(
                        post("/api/restaurants/" + window + "/reviews")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content(reviewBody(2)))
                .andExpect(status().isCreated());
        String page =
                mvc.perform(get("/api/canteen-windows/" + window + "/reviews"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.totalReviews").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor =
                new tools.jackson.databind.ObjectMapper()
                        .readTree(page)
                        .at("/data/records/0/nextHistoryCursor")
                        .asString();
        mvc.perform(
                        get("/api/restaurants/" + window + "/reviews/authors/11/history")
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/restaurants/" + window))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(2));
        mvc.perform(get("/api/canteen-windows/" + window))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(6));
        org.junit.jupiter.api.Assertions.assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM growth_rewards WHERE user_id=11 AND business_key IN"
                                + " (?,?)",
                        Integer.class,
                        window,
                        "WINDOW:" + window));
    }

    /** 不同窗口人数不改变等权规则；零分参与、无分跳过，最新点评删除后回退历史。 */
    @Test
    void equalWindowWeightsAndDeletionRollback() throws Exception {
        String first = createWindow(920, "多人窗口");
        String second = createWindowUnder(920, "单人窗口");
        createWindowUnder(920, "无评分窗口");
        publishReview(first, 8);
        mvc.perform(
                        post("/api/canteen-windows/" + first + "/reviews")
                                .header("Authorization", "Bearer " + other)
                                .contentType("application/json")
                                .content(reviewBody(8)))
                .andExpect(status().isOk());
        publishReview(second, 0);
        expectScore(920, 4);
        String latest = publishReview(second, 4);
        expectScore(920, 6);
        mvc.perform(delete("/api/reviews/" + latest).header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/reviews/" + latest).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/reviews/" + latest).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        expectScore(920, 4);
        mvc.perform(
                        delete("/api/admin/canteen-windows/" + second)
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNoContent());
        expectScore(920, 8);
        mvc.perform(
                        post("/api/admin/canteen-windows/" + second + "/restore")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNoContent());
        expectScore(920, 4);
    }

    /** 窗口互动复用真实回复和点赞；源定位返回windowId，删除父层后内容停止公开。 */
    @Test
    void windowRepliesLikesAndParentVisibility() throws Exception {
        String window = createWindow(930, "互动窗口");
        String review = publishReview(window, 7);
        String response =
                mvc.perform(
                                post("/api/reviews/" + review + "/replies")
                                        .header("Authorization", "Bearer " + other)
                                        .contentType("application/json")
                                        .content(
                                                "{\"clientRequestId\":\""
                                                        + UUID.randomUUID()
                                                        + "\",\"body\":\"窗口真实回复\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String reply =
                new tools.jackson.databind.ObjectMapper()
                        .readTree(response)
                        .at("/data/replyId")
                        .asString();
        mvc.perform(
                        get("/api/me/review-replies/" + reply + "/target")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.windowId").value(window))
                .andExpect(jsonPath("$.data.restaurantId").isEmpty());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/reviews/" + review + "/like")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reviews/" + review + "/replies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].body").value("窗口真实回复"));
        jdbc.update(
                "UPDATE restaurants SET"
                    + " deleted_at=CURRENT_TIMESTAMP(6),deleted_by=22,delete_reason_code='ADMIN_REMOVAL'"
                    + " WHERE id=930");
        mvc.perform(get("/api/canteen-windows/" + window)).andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/" + review + "/replies")).andExpect(status().isNotFound());
    }

    /** 客户端不能篡改评分、所属食堂或管理身份；请求UUID跨食堂复用必须冲突。 */
    @Test
    void rejectsInvalidOwnershipAndCreateRequestConflicts() throws Exception {
        createWindow(940, "校验窗口");
        createWindow(941, "另一食堂窗口");
        String body = windowBody("请求窗口", UUID.randomUUID());
        mvc.perform(
                        post("/api/admin/restaurants/940/windows")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/admin/restaurants/940/windows")
                                .header("Authorization", "Bearer " + other)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/admin/restaurants/941/windows")
                                .header("Authorization", "Bearer " + other)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isConflict());
        mvc.perform(
                        post("/api/admin/restaurants/940/windows")
                                .header("Authorization", "Bearer " + other)
                                .contentType("application/json")
                                .content(body.replace("请求窗口", "不同内容")))
                .andExpect(status().isConflict());
        mvc.perform(
                        post("/api/admin/restaurants/940/windows")
                                .header("Authorization", "Bearer " + other)
                                .contentType("application/json")
                                .content(body.replace("\"floorNumber\":1", "\"floorNumber\":101")))
                .andExpect(status().isBadRequest());
    }

    /** 并发相同请求只创建一条点评及一份贡献，统计与奖励不会累加四次。 */
    @Test
    void concurrentReviewReplayKeepsOneContribution() throws Exception {
        String window = createWindow(950, "并发窗口");
        String body = reviewBody(9);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 4; i++) {
                tasks.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return mvc.perform(
                                                    post("/api/canteen-windows/"
                                                                    + window
                                                                    + "/reviews")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + owner)
                                                            .contentType("application/json")
                                                            .content(body))
                                            .andExpect(status().isOk())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString();
                                }));
            }
            start.countDown();
            var ids = new java.util.HashSet<String>();
            for (var task : tasks) {
                ids.add(
                        new tools.jackson.databind.ObjectMapper()
                                .readTree(task.get(20, java.util.concurrent.TimeUnit.SECONDS))
                                .at("/data/reviewId")
                                .asString());
            }
            org.junit.jupiter.api.Assertions.assertEquals(1, ids.size());
        }
        mvc.perform(get("/api/canteen-windows/" + window))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCount").value(1))
                .andExpect(jsonPath("$.data.participantCount").value(1));
        expectScore(950, 9);
    }

    /** 带无效图片的发评在写入点评后失败，窗口/父食堂统计、奖励与搜索版本均回滚。 */
    @Test
    void invalidPhotoRollsBackEntireWindowPublication() throws Exception {
        String window = createWindow(960, "回滚窗口");
        Long version =
                jdbc.queryForObject(
                        "SELECT search_projection_version FROM restaurants WHERE id=960",
                        Long.class);
        String body =
                reviewBody(9)
                        .replace(
                                "\"scores\":",
                                "\"photoIds\":[\"9223372036854775807\"],\"scores\":");
        mvc.perform(
                        post("/api/canteen-windows/" + window + "/reviews")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().is4xxClientError());
        org.junit.jupiter.api.Assertions.assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reviews WHERE window_id=?", Integer.class, window));
        org.junit.jupiter.api.Assertions.assertEquals(
                version,
                jdbc.queryForObject(
                        "SELECT search_projection_version FROM restaurants WHERE id=960",
                        Long.class));
        mvc.perform(get("/api/canteen-windows/" + window))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCount").value(0));
    }

    /** 从有分类窗口/旧食堂统计的V36升级，移除分类而保留身份及评分；重复迁移无新增写入。 */
    @Test
    void migrationUpgradesNonEmptyV36AndCanRepeat() throws Exception {
        String isolated = "ginkgo_window_api_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + isolated
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        try {
            org.flywaydb.core.Flyway.configure()
                    .dataSource(url(isolated), "root", password())
                    .target("36")
                    .load()
                    .migrate();
            var source =
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                            url(isolated), "root", password());
            var data = new JdbcTemplate(source);
            data.update(
                    "INSERT INTO"
                        + " restaurants(id,name,restaurant_type,price_kind,address,latitude,longitude,needs_location,business_status,created_by)"
                        + " VALUES(1,'存量食堂','CANTEEN','UNKNOWN','测试',30,104,FALSE,'OPERATING',22)");
            data.update(
                    "INSERT INTO"
                        + " canteen_windows(id,canteen_id,name,floor_number,created_by,category_code)"
                        + " VALUES(1,1,'存量窗口',1,22,'FAST_FOOD'),(2,1,'缺统计窗口',2,22,NULL)");
            data.update(
                    "INSERT INTO"
                        + " window_review_statistics(window_id,review_count,participant_count,recommend_count,taste_sum,value_sum,service_sum,ambience_sum,overall_score_sum)"
                        + " VALUES(1,1,1,1,8,8,8,8,8)");
            data.update(
                    "INSERT INTO"
                        + " restaurant_review_statistics(restaurant_id,review_count,participant_count,recommend_count,taste_sum,value_sum,service_sum,ambience_sum,overall_score_sum)"
                        + " VALUES(1,1,1,1,2,2,2,2,2)");
            var flyway =
                    org.flywaydb.core.Flyway.configure()
                            .dataSource(url(isolated), "root", password())
                            .load();
            // V37–V42为V36之后新增的迁移；V41/V42增加内容举报及举报结果通知，不改变窗口结构。
            org.junit.jupiter.api.Assertions.assertEquals(6, flyway.migrate().migrationsExecuted);
            org.junit.jupiter.api.Assertions.assertEquals(0, flyway.migrate().migrationsExecuted);
            org.junit.jupiter.api.Assertions.assertEquals(
                    2,
                    data.queryForObject(
                            "SELECT COUNT(*) FROM window_review_statistics", Integer.class));
            org.junit.jupiter.api.Assertions.assertEquals(
                    8,
                    data.queryForObject(
                            "SELECT overall_score_sum/participant_count FROM"
                                    + " restaurant_review_statistics WHERE restaurant_id=1",
                            Integer.class));
            org.junit.jupiter.api.Assertions.assertNull(
                    data.queryForObject(
                            "SELECT client_request_id FROM canteen_windows WHERE id=1",
                            byte[].class));
            org.junit.jupiter.api.Assertions.assertEquals(
                    0,
                    data.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE"
                                + " table_schema=DATABASE() AND table_name='canteen_windows' AND"
                                + " column_name='category_code'",
                            Integer.class));
            org.junit.jupiter.api.Assertions.assertEquals(
                    3,
                    data.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.statistics WHERE"
                                + " table_schema=DATABASE() AND table_name='canteen_windows' AND"
                                + " index_name='idx_canteen_windows_floor'",
                            Integer.class));
        } finally {
            drop(isolated);
        }
    }

    /** 真实上传入口只接受管理员WINDOW用途，存储目录与商家图、点评图隔离。 */
    @Test
    void windowImageUploadIsAdminOnlyAndUsesDedicatedPrefix() throws Exception {
        var image =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpg", bytes);
        var file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "photo.jpg", "image/jpeg", bytes.toByteArray());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart("/api/files/images")
                                .file(file)
                                .param("purpose", "WINDOW")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
        String response =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .multipart("/api/files/images")
                                        .file(file)
                                        .param("purpose", "WINDOW")
                                        .header("Authorization", "Bearer " + other))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long id =
                new tools.jackson.databind.ObjectMapper()
                        .readTree(response)
                        .path("data")
                        .path("fileId")
                        .asLong();
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT storage_key FROM media_files WHERE id=?", String.class, id))
                .matches("windows-images/[a-f0-9-]{36}\\.jpg");
    }

    /** 入册防重、公开读取、首张删除接替及回收意图与关联同步验证。 */
    @Test
    void windowAlbumLifecycleAndVisibility() throws Exception {
        String window = createWindow(981, "图片窗口");
        long first = photoFile(22, "WINDOW"), second = photoFile(22, "WINDOW");
        addPhoto(window, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cover").value(true));
        addPhoto(window, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        addPhoto(window, second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/canteen-windows/" + window))
                .andExpect(
                        jsonPath("$.data.coverUrl")
                                .value("/api/canteen-windows/" + window + "/photos/" + first));
        when(photoStorage.read(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new byte[] {1, 2, 3});
        mvc.perform(get("/api/canteen-windows/" + window + "/photos/" + first))
                .andExpect(status().isOk());
        mvc.perform(
                        delete("/api/admin/canteen-windows/" + window + "/photos/" + first)
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(
                            delete("/api/admin/canteen-windows/" + window + "/photos/" + first)
                                    .header("Authorization", "Bearer " + other))
                    .andExpect(status().isNoContent());
        }
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM media_files WHERE id=?", String.class, first))
                .isEqualTo("PENDING_DELETE");
        mvc.perform(get("/api/canteen-windows/" + window + "/photos/" + first))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/canteen-windows/" + window))
                .andExpect(
                        jsonPath("$.data.coverUrl")
                                .value("/api/canteen-windows/" + window + "/photos/" + second));
        mvc.perform(
                        delete("/api/admin/canteen-windows/" + window)
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/canteen-windows/" + window + "/photos"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/canteen-windows/" + window + "/photos/" + second))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/admin/canteen-windows/" + window + "/restore")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/canteen-windows/" + window + "/photos"))
                .andExpect(jsonPath("$.data[0].cover").value(true));
        jdbc.update(
                "UPDATE restaurants SET"
                    + " deleted_at=CURRENT_TIMESTAMP(6),deleted_by=22,delete_reason_code='ADMIN_REMOVAL'"
                    + " WHERE id=981");
        mvc.perform(get("/api/canteen-windows/" + window + "/photos/" + second))
                .andExpect(status().isNotFound());
    }

    /** 拒绝其他用途、其他上传者、其他窗口关联、过期文件及第六张，不损坏既有相册。 */
    @Test
    void windowAlbumRejectsForeignExpiredAndExcessFiles() throws Exception {
        String window = createWindow(982, "权限窗口"), another = createWindowUnder(982, "另一个窗口");
        long review = photoFile(22, "REVIEW"),
                foreign = photoFile(11, "WINDOW"),
                expired = photoFile(22, "WINDOW");
        jdbc.update(
                "UPDATE media_files SET expires_at=TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP(6)) WHERE"
                    + " id=?",
                expired);
        for (long id : List.of(review, foreign, expired))
            addPhoto(window, id).andExpect(status().isConflict());
        long attached = photoFile(22, "WINDOW");
        addPhoto(window, attached).andExpect(status().isOk());
        addPhoto(another, attached).andExpect(status().isConflict());
        mvc.perform(get("/api/canteen-windows/" + another + "/photos/" + attached))
                .andExpect(status().isNotFound());
        for (int i = 0; i < 4; i++)
            addPhoto(window, photoFile(22, "WINDOW")).andExpect(status().isOk());
        long sixth = photoFile(22, "WINDOW");
        addPhoto(window, sixth).andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM media_files WHERE id=?", String.class, sixth))
                .isEqualTo("TEMPORARY");
        mvc.perform(get("/api/canteen-windows/" + window + "/photos"))
                .andExpect(jsonPath("$.data.length()").value(5));
    }

    /** 两个管理员并发添加只留下五张，失败文件保持临时状态供回收。 */
    @Test
    void concurrentAlbumAdditionHonorsFivePhotoLimit() throws Exception {
        String window = createWindow(983, "并发相册");
        for (int i = 0; i < 4; i++)
            addPhoto(window, photoFile(22, "WINDOW")).andExpect(status().isOk());
        long one = photoFile(22, "WINDOW"), two = photoFile(22, "WINDOW");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (long id : List.of(one, two))
                tasks.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return addPhoto(window, id)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            start.countDown();
            var results = new java.util.ArrayList<Integer>();
            for (var task : tasks) results.add(task.get(15, java.util.concurrent.TimeUnit.SECONDS));
            org.assertj.core.api.Assertions.assertThat(results).containsExactlyInAnyOrder(200, 409);
        }
        mvc.perform(get("/api/canteen-windows/" + window + "/photos"))
                .andExpect(jsonPath("$.data.length()").value(5));
    }

    private org.springframework.test.web.servlet.ResultActions addPhoto(String window, long file)
            throws Exception {
        return mvc.perform(
                post("/api/admin/canteen-windows/" + window + "/photos")
                        .header("Authorization", "Bearer " + other)
                        .contentType("application/json")
                        .content("{\"fileId\":\"" + file + "\"}"));
    }

    /** 仅在随机隔离库登记测试图片；云端读写由独立的真实OSS检查覆盖。 */
    private long photoFile(long user, String purpose) {
        String key = "windows-images/" + UUID.randomUUID() + ".jpg";
        jdbc.update(
                "INSERT INTO"
                    + " media_files(uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,expires_at,purpose)"
                    + " VALUES(?,'test-window-bucket',?,'image/jpeg',10,8,8,UNHEX(REPEAT('a',64)),'TEMPORARY',TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP(6)),?)",
                user,
                key,
                purpose);
        return jdbc.queryForObject(
                "SELECT id FROM media_files WHERE storage_key=?", Long.class, key);
    }

    private String createWindow(long canteen, String name) throws Exception {
        jdbc.update(
                "INSERT INTO"
                    + " restaurants(id,name,restaurant_type,price_kind,address,latitude,longitude,needs_location,business_status,created_by)"
                    + " VALUES(?,'测试食堂','CANTEEN','UNKNOWN','测试地址',30,104,FALSE,'OPERATING',22)",
                canteen);
        return createWindowUnder(canteen, name);
    }

    private String createWindowUnder(long canteen, String name) throws Exception {
        String response =
                mvc.perform(
                                post("/api/admin/restaurants/" + canteen + "/windows")
                                        .header("Authorization", "Bearer " + other)
                                        .contentType("application/json")
                                        .content(windowBody(name, UUID.randomUUID())))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return new tools.jackson.databind.ObjectMapper()
                .readTree(response)
                .at("/data/id")
                .asString();
    }

    private String windowBody(String name, UUID request) {
        return "{\"name\":\""
                + name
                + "\",\"floorNumber\":1,\"priceKind\":\"UNKNOWN\",\"businessStatus\":\"OPERATING\",\"clientRequestId\":\""
                + request
                + "\"}";
    }

    private void expectScore(long canteen, int score) throws Exception {
        mvc.perform(get("/api/restaurants/" + canteen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(score));
    }

    private String publishReview(String id, int score) throws Exception {
        String response =
                mvc.perform(
                                post("/api/canteen-windows/" + id + "/reviews")
                                        .header("Authorization", "Bearer " + owner)
                                        .contentType("application/json")
                                        .content(reviewBody(score)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.windowId").value(id))
                        .andExpect(jsonPath("$.data.restaurantId").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return new tools.jackson.databind.ObjectMapper()
                .readTree(response)
                .path("data")
                .path("reviewId")
                .asString();
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
                + "},\"recommended\":true,\"body\":\"窗口真实点评测试\"}";
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
        if (!name.matches("ginkgo_window_api_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + name + "`");
        }
    }
}
