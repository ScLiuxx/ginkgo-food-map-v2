package com.ginkgo.foodmap.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ginkgo.foodmap.restaurant.service.RestaurantReviewApplicationService;
import com.ginkgo.foodmap.review.mapper.ReplyMapper;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 真实MySQL/Flyway/认证链验证，仅替换Redis会话；随机测试库与业务库隔离。 */
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
class ReplyApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @Autowired org.mybatis.spring.SqlSessionTemplate sql;
    @MockitoBean LoginSessionService sessions;
    @MockitoSpyBean ReplyMapper mapper;
    @MockitoSpyBean RestaurantReviewApplicationService restaurants;
    @MockitoSpyBean com.ginkgo.foodmap.notification.mapper.NotificationMapper notifications;
    private String owner, other;
    private ReplyMapper delegate;

    /** 仅接受显式回环服务器地址，不加载local配置或已有库。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_reply_it_" + UUID.randomUUID().toString().replace("-", "");
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
        registry.add("spring.datasource.password", ReplyApiIntegrationTest::password);
    }

    @BeforeEach
    void setup() {
        reset(sessions, mapper, notifications);
        jdbc.update("DELETE FROM notification_messages");
        jdbc.update("DELETE FROM content_likes");
        jdbc.update("DELETE FROM growth_rewards");
        jdbc.update("DELETE FROM growth_accounts");
        // MyBatis的Mapper是接口代理，故障注入使用独立代理执行真实SQL，不能调用抽象realMethod。
        delegate = sql.getMapper(ReplyMapper.class);
        RestaurantReviewApplicationService target =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(restaurants);
        reset(target);
        jdbc.update("DELETE FROM review_replies");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM restaurant_review_statistics");
        jdbc.update("DELETE FROM restaurants");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
        for (long id : List.of(11L, 22L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(?,?,?,?,TRUE)",
                    id,
                    "reply_" + id,
                    "reply" + id + "@example.test",
                    "test-only");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",
                    id);
        }
        jdbc.update(
                "INSERT INTO restaurants(id,name,category_code,created_by) VALUES(1,'回复测试店','OTHER',11),(2,'另一店','OTHER',11)");
        for (long id : List.of(1L, 2L, 3L)) {
            jdbc.update(
                    "INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash) VALUES(?,?,11,7,7,7,7,TRUE,'测试点评正文',UUID_TO_BIN(?),UNHEX(REPEAT('01',32)))",
                    id,
                    id == 3 ? 2 : 1,
                    UUID.randomUUID().toString());
        }
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        owner = token(11);
        other = token(22);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        if (!database.matches("ginkgo_reply_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    private Map<String, Object> input(String body) {
        return new LinkedHashMap<>(
                Map.of("body", body, "clientRequestId", UUID.randomUUID().toString()));
    }

    private org.springframework.test.web.servlet.ResultActions publish(
            String token, long review, Map<String, Object> input) throws Exception {
        return mvc.perform(
                post("/api/reviews/" + review + "/replies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(input)));
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review_replies", Long.class);
    }

    @Test
    void normalizesReplayAndRejectsChangedContentOrParent() throws Exception {
        var input = input("　回复🥟\u00a0");
        var first =
                publish(owner, 1, input)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.replayed").value(false))
                        .andReturn();
        var id =
                json.readTree(first.getResponse().getContentAsString())
                        .get("data")
                        .get("replyId")
                        .asText();
        input.put("body", "回复🥟");
        publish(owner, 1, input)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replyId").value(id))
                .andExpect(jsonPath("$.data.replayed").value(true));
        publish(owner, 2, input)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPLY_REQUEST_CONFLICT"));
        input.put("body", "不同正文");
        publish(owner, 1, input).andExpect(status().isConflict());
        publish(other, 1, input).andExpect(status().isCreated());
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void rejectsAnonymousInactiveDisabledAndForgedAuthor() throws Exception {
        mvc.perform(
                        post("/api/reviews/1/replies")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(input("回复"))))
                .andExpect(status().isUnauthorized());
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(false);
        publish(owner, 1, input("回复")).andExpect(status().isUnauthorized());
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=11");
        publish(owner, 1, input("回复")).andExpect(status().isUnauthorized());
        var forged = input("回复");
        forged.put("authorUserId", 11);
        publish(other, 1, forged).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }

    @Test
    void validatesLengthUuidAndPageBounds() throws Exception {
        for (String body : List.of("", "　 ", "🥟".repeat(201))) {
            publish(owner, 1, input(body)).andExpect(status().isBadRequest());
        }
        var bad = input("回复");
        bad.put("clientRequestId", "1-1-1-1-1");
        publish(owner, 1, bad).andExpect(status().isBadRequest());
        for (String field : List.of("body", "clientRequestId")) {
            bad = input("回复");
            bad.remove(field);
            publish(owner, 1, bad).andExpect(status().isBadRequest());
        }
        publish(owner, 1, input("🥟".repeat(200))).andExpect(status().isCreated());
        for (String size : List.of("0", "51", "abc")) {
            mvc.perform(get("/api/reviews/1/replies").param("pageSize", size))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/reviews/01/replies")).andExpect(status().isBadRequest());
    }

    @Test
    void readsPublicPagesWithStableTiesAndNoPrivateAuthorFields() throws Exception {
        for (int n = 0; n < 5; n++) {
            publish(owner, 1, input("回复" + n)).andExpect(status().isCreated());
        }
        jdbc.update("UPDATE review_replies SET created_at='2026-10-30 00:00:00.123456'");
        var ids = new ArrayList<String>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            var request = get("/api/reviews/1/replies").param("pageSize", "2");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            var response =
                    mvc.perform(request)
                            .andExpect(status().isOk())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(
                                    jsonPath("$.data.records[0].author.username").value("reply_11"))
                            .andExpect(jsonPath("$.data.records[0].author.email").doesNotExist())
                            .andReturn();
            var data = json.readTree(response.getResponse().getContentAsString()).get("data");
            for (var row : data.get("records")) {
                ids.add(row.get("id").asText());
            }
            assertThat(data.get("hasMore").asBoolean()).isEqualTo(page < 2);
            cursor = data.get("nextCursor").isNull() ? null : data.get("nextCursor").asText();
        }
        assertThat(ids).doesNotHaveDuplicates().hasSize(5);
        assertThat(ids)
                .isEqualTo(
                        jdbc.queryForList(
                                "SELECT CAST(id AS CHAR) FROM review_replies ORDER BY created_at,id",
                                String.class));
    }

    @Test
    void rejectsCrossParentForgedAndMissingAnchors() throws Exception {
        publish(owner, 1, input("回复一")).andExpect(status().isCreated());
        publish(owner, 1, input("回复二")).andExpect(status().isCreated());
        var response =
                mvc.perform(get("/api/reviews/1/replies").param("pageSize", "1")).andReturn();
        String cursor =
                json.readTree(response.getResponse().getContentAsString())
                        .get("data")
                        .get("nextCursor")
                        .asText();
        mvc.perform(get("/api/reviews/2/replies").param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reviews/1/replies").param("cursor", cursor + "="))
                .andExpect(status().isBadRequest());
        jdbc.update("DELETE FROM review_replies ORDER BY id LIMIT 1");
        mvc.perform(get("/api/reviews/1/replies").param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPLY_INVALID_CURSOR"));
    }

    @Test
    void historyRepliesStayOnOriginalReviewAndNeverChangeScores() throws Exception {
        String snapshot =
                "SELECT id,restaurant_id,author_user_id,body,created_at,updated_at,taste_score,value_score,service_score,ambience_score,overall_score,is_recommended,HEX(client_request_id),HEX(request_hash) FROM reviews ORDER BY id";
        var before = jdbc.queryForList(snapshot);
        publish(owner, 1, input("旧点评回复")).andExpect(status().isCreated());
        mvc.perform(get("/api/reviews/1/replies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].body").value("旧点评回复"));
        mvc.perform(get("/api/reviews/2/replies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty());
        assertThat(jdbc.queryForList(snapshot)).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_review_statistics", Long.class))
                .isZero();
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<RestaurantReviewApplicationService>getUltimateTargetObject(
                                        restaurants),
                        never())
                .applyDelta(anyLong(), any());
    }

    @Test
    void missingAndDeletedParentsAreHiddenButClosedStoreAllowsReplies() throws Exception {
        publish(owner, 999, input("回复")).andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/999/replies")).andExpect(status().isNotFound());
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=1");
        var input = input("停业也可回复");
        publish(owner, 1, input).andExpect(status().isCreated());
        jdbc.update(
                "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=1");
        publish(owner, 1, input).andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/1/replies")).andExpect(status().isNotFound());
    }

    @Test
    void concurrentSameUuidHasOnlyOneInsertAndCrossStoreReuseConflicts() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            var input = input("并发回复");
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 6; i++) {
                futures.add(
                        pool.submit(
                                () ->
                                        publish(owner, 1, input)
                                                .andReturn()
                                                .getResponse()
                                                .getStatus()));
            }
            var statuses = new ArrayList<Integer>();
            for (var f : futures) {
                statuses.add(f.get(20, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(200, 201);
            assertThat(Collections.frequency(statuses, 201)).isEqualTo(1);
            assertThat(count()).isEqualTo(1);
            var otherInput = input("跨店竞争");
            var a =
                    pool.submit(
                            () ->
                                    publish(owner, 1, otherInput)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            var b =
                    pool.submit(
                            () ->
                                    publish(owner, 3, otherInput)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(count()).isEqualTo(2);
        }
    }

    @Test
    void rollbackBeforeRetryAndBoundedFailureDoNotLeaveReplies() throws Exception {
        doAnswer(
                        i -> {
                            delegate.insert(i.getArgument(0));
                            throw new org.springframework.dao.CannotAcquireLockException(
                                    "test-only");
                        })
                .doAnswer(i -> delegate.insert(i.getArgument(0)))
                .when(mapper)
                .insert(any());
        publish(owner, 1, input("失败重试")).andExpect(status().isCreated());
        assertThat(count()).isEqualTo(1);
        reset(mapper);
        doAnswer(
                        i -> {
                            delegate.insert(i.getArgument(0));
                            throw new org.springframework.dao.CannotAcquireLockException(
                                    "test-only");
                        })
                .when(mapper)
                .insert(any());
        publish(owner, 1, input("持续失败"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPLY_TEMPORARILY_UNAVAILABLE"));
        assertThat(count()).isEqualTo(1);
        verify(mapper, times(3)).insert(any());
    }

    @Test
    void laterRepliesRemainAfterCursorWhenClockMovesBackwards() throws Exception {
        publish(owner, 1, input("第一条")).andExpect(status().isCreated());
        publish(owner, 1, input("第二条")).andExpect(status().isCreated());
        jdbc.update("UPDATE review_replies SET created_at=CURRENT_TIMESTAMP(6)+INTERVAL 1 HOUR");
        var response =
                mvc.perform(get("/api/reviews/1/replies").param("pageSize", "1")).andReturn();
        String cursor =
                json.readTree(response.getResponse().getContentAsString())
                        .get("data")
                        .get("nextCursor")
                        .asText();
        publish(owner, 1, input("第三条")).andExpect(status().isCreated());
        mvc.perform(get("/api/reviews/1/replies").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].body").value("第二条"))
                .andExpect(jsonPath("$.data.records[1].body").value("第三条"));
    }

    @Test
    void missingAuthorKeepsStableIdAndFallbackName() throws Exception {
        publish(other, 1, input("账号后来不存在")).andExpect(status().isCreated());
        jdbc.update("DELETE FROM user_roles WHERE user_id=22");
        jdbc.update("DELETE FROM users WHERE id=22");
        mvc.perform(get("/api/reviews/1/replies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].author.id").value("22"))
                .andExpect(jsonPath("$.data.records[0].author.username").value("用户不可用"));
    }

    @Test
    void replyAndDeleteShareParentLock() throws Exception {
        // 正式商户删除现已限定管理员；此用例验证锁顺序，需要给删店操作者真实数据库角色。
        jdbc.update(
                "INSERT INTO user_roles(user_id,role_id) SELECT 11,id FROM roles WHERE code='ROLE_ADMIN'");
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        i -> {
                            reached.countDown();
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test timeout");
                            }
                            return delegate.insert(i.getArgument(0));
                        })
                .when(mapper)
                .insert(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reply =
                    pool.submit(
                            () ->
                                    publish(owner, 1, input("与删店竞争"))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
            var deletion =
                    pool.submit(
                            () ->
                                    mvc.perform(
                                                    delete("/api/restaurants/1")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + owner))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            try {
                assertThat(deletion.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(reply.get(20, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(deletion.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            mvc.perform(get("/api/reviews/1/replies")).andExpect(status().isNotFound());
        } finally {
            release.countDown();
        }
    }

    /** 每条回复只通知点评作者，自己回复不通知；不同回复独立记录，重放与并发都不重复发送。 */
    @Test
    void shouldNotifyOnlyReviewAuthorOncePerNewReply() throws Exception {
        publish(owner, 1, input("自己回复不提醒")).andExpect(status().isCreated());
        assertThat(noticeCount()).isZero();
        var body = input("只通知点评作者");
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 4; i++) {
                tasks.add(
                        pool.submit(
                                () ->
                                        publish(other, 1, body)
                                                .andReturn()
                                                .getResponse()
                                                .getStatus()));
            }
            for (var task : tasks) {
                assertThat(task.get(20, TimeUnit.SECONDS)).isIn(200, 201);
            }
        }
        assertThat(noticeCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT recipient_user_id FROM notification_messages", Long.class))
                .isEqualTo(11);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT actor_user_id FROM notification_messages", Long.class))
                .isEqualTo(22);
        publish(other, 1, input("第二条真实回复")).andExpect(status().isCreated());
        inbox(owner, "COMMENTS")
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.unreadCount").value(2))
                .andExpect(jsonPath("$.data.records[0].actorUsername").value("reply_22"))
                .andExpect(jsonPath("$.data.records[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].replyBody").doesNotExist());
        inbox(other, "ALL").andExpect(jsonPath("$.data.total").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_rewards", Long.class)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_review_statistics", Long.class))
                .isZero();
    }

    /** 通知真实写入后故障也回滚回复；瞬态失败完整重试后只留一条，耗尽则不留下新通知。 */
    @Test
    void shouldRollbackReplyAndNotificationTogetherAndRetryWholeTransaction() throws Exception {
        var real = sql.getMapper(com.ginkgo.foodmap.notification.mapper.NotificationMapper.class);
        doAnswer(
                        call -> {
                            real.insertReply(call.getArgument(0));
                            throw new IllegalStateException("injected after notification insert");
                        })
                .when(notifications)
                .insertReply(any());
        publish(other, 1, input("计入失败必须回滚")).andExpect(status().isInternalServerError());
        assertThat(count()).isZero();
        assertThat(noticeCount()).isZero();
        reset(notifications);
        doAnswer(
                        call -> {
                            real.insertReply(call.getArgument(0));
                            throw new org.springframework.dao.CannotAcquireLockException(
                                    "test retry");
                        })
                .doAnswer(call -> real.insertReply(call.getArgument(0)))
                .when(notifications)
                .insertReply(any());
        var body = input("完整事务重试");
        publish(other, 1, body).andExpect(status().isCreated());
        publish(other, 1, body).andExpect(status().isOk());
        assertThat(count()).isEqualTo(1);
        assertThat(noticeCount()).isEqualTo(1);
        reset(notifications);
        doAnswer(
                        call -> {
                            real.insertReply(call.getArgument(0));
                            throw new org.springframework.dao.CannotAcquireLockException(
                                    "test exhausted");
                        })
                .when(notifications)
                .insertReply(any());
        publish(other, 1, input("重试耗尽")).andExpect(status().isServiceUnavailable());
        assertThat(count()).isEqualTo(1);
        assertThat(noticeCount()).isEqualTo(1);
    }

    /** 分类读写独立：系统全部已读不影响评论，评论全部已读不影响系统，首页处理全部。 */
    @Test
    void shouldIsolateCategoryCountsPaginationAndReadAllScopes() throws Exception {
        publish(other, 1, input("回复一")).andExpect(status().isCreated());
        publish(other, 2, input("回复二")).andExpect(status().isCreated());
        jdbc.update(
                "INSERT INTO notification_messages(recipient_user_id,source_id,request_id,request_type,decision,restaurant_name) VALUES(11,900,900,'CREATE','APPROVED','审核店')");
        inbox(owner, "ALL")
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.unreadCounts.SYSTEM").value(1))
                .andExpect(jsonPath("$.data.unreadCounts.COMMENTS").value(2))
                .andExpect(jsonPath("$.data.latestSystem.restaurantName").value("审核店"));
        mvc.perform(
                        get("/api/me/notifications")
                                .param("category", "COMMENTS")
                                .param("pageSize", "1")
                                .param("page", "2")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].category").value("COMMENTS"));
        readAll(owner, "SYSTEM");
        inbox(owner, "COMMENTS").andExpect(jsonPath("$.data.unreadCount").value(2));
        inbox(owner, "SYSTEM").andExpect(jsonPath("$.data.unreadCount").value(0));
        jdbc.update("UPDATE notification_messages SET read_at=NULL WHERE request_id=900");
        readAll(owner, "COMMENTS");
        inbox(owner, "SYSTEM").andExpect(jsonPath("$.data.unreadCount").value(1));
        inbox(owner, "COMMENTS").andExpect(jsonPath("$.data.unreadCount").value(0));
        readAll(owner, "ALL");
        inbox(owner, "ALL").andExpect(jsonPath("$.data.unreadCount").value(0));
    }

    /** 历史点评和分页外回复均按ID直接定位；只读原作者可见，删除目标/父店后不泄露正文。 */
    @Test
    void shouldLocateHistoricalReviewAndLateReplyAndRejectOtherReaders() throws Exception {
        long reply = 0;
        for (int i = 0; i < 12; i++) {
            var response =
                    publish(other, 1, input("目标回复" + i))
                            .andExpect(status().isCreated())
                            .andReturn();
            reply =
                    Long.parseLong(
                            json.readTree(response.getResponse().getContentAsString())
                                    .at("/data/replyId")
                                    .asString());
        }
        String path = "/api/me/review-replies/" + reply + "/target";
        mvc.perform(get(path).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.userId").value("11"))
                .andExpect(jsonPath("$.data.reviewId").value("1"))
                .andExpect(jsonPath("$.data.replyBody").value("目标回复11"))
                .andExpect(jsonPath("$.data.review.body").value("测试点评正文"))
                .andExpect(jsonPath("$.data.reviewAuthor.email").doesNotExist());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=1");
        mvc.perform(get(path).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        jdbc.update(
                "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=1");
        mvc.perform(get(path).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE restaurants SET deleted_at=NULL,delete_reason_code=NULL WHERE id=1");
        jdbc.update("DELETE FROM review_replies WHERE id=?", reply);
        mvc.perform(get(path).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        assertThat(noticeCount()).isEqualTo(12);
        readAll(owner, "COMMENTS");
        inbox(owner, "COMMENTS")
                .andExpect(jsonPath("$.data.total").value(12))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        jdbc.update("DELETE FROM review_replies WHERE review_id=1");
        jdbc.update("DELETE FROM reviews WHERE id=1");
        mvc.perform(get(path).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        assertThat(noticeCount()).isEqualTo(12);
    }

    /** V28非空升级保留审核快照和阅读时间，加入来源键；复跑不重复迁移，也不补发旧回复。 */
    @Test
    void shouldUpgradeV28InboxWithoutChangingExistingMessages() throws Exception {
        String name = "ginkgo_reply_it_" + UUID.randomUUID().toString().replace("-", "");
        try {
            try (var c = connect("");
                    var st = c.createStatement()) {
                st.executeUpdate(
                        "CREATE DATABASE `"
                                + name
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            org.flywaydb.core.Flyway.configure()
                    .dataSource(url(name), "root", password())
                    .locations("classpath:db/migration")
                    .target("28")
                    .load()
                    .migrate();
            var db =
                    new JdbcTemplate(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                    url(name), "root", password()));
            db.update(
                    "INSERT INTO notification_messages(id,recipient_user_id,request_id,request_type,decision,restaurant_name,created_at,read_at) VALUES(91,11,71,'CREATE','APPROVED','旧通知','2026-11-01 01:00:00','2026-11-01 02:00:00')");
            String original =
                    "SELECT id,recipient_user_id,request_id,request_type,decision,restaurant_name,reject_reason,created_at,read_at FROM notification_messages";
            var before = db.queryForList(original);
            var flyway =
                    org.flywaydb.core.Flyway.configure()
                            .dataSource(url(name), "root", password())
                            .locations("classpath:db/migration")
                            .target("29")
                            .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(db.queryForList(original)).isEqualTo(before);
            assertThat(
                            db.queryForObject(
                                    "SELECT source_id FROM notification_messages WHERE id=91",
                                    Long.class))
                    .isEqualTo(71);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            // 同一数字来源可分别属于审核和回复；同类型来源不能重复，也不能构造混合或缺字段的消息。
            db.update(
                    "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id) VALUES(11,'REVIEW_REPLY',71,1,22)");
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () ->
                            db.update(
                                    "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id) VALUES(11,'REVIEW_REPLY',71,1,22)"));
            for (String invalid :
                    List.of(
                            "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id) VALUES(11,'REVIEW_REPLY',72,NULL,22)",
                            "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id) VALUES(11,'REVIEW_REPLY',72,1,11)",
                            "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id) VALUES(11,'review_reply',72,1,22)")) {
                var failure =
                        org.junit.jupiter.api.Assertions.assertThrows(
                                org.springframework.dao.DataAccessException.class,
                                () -> db.update(invalid));
                // MySQL 8.0 CHECK错误3819未必被Spring翻译为DataIntegrityViolationException，核验真实错误码。
                assertThat(failure.getMostSpecificCause()).isInstanceOf(SQLException.class);
                assertThat(((SQLException) failure.getMostSpecificCause()).getErrorCode())
                        .isEqualTo(3819);
            }
            var oldNotifications =
                    db.queryForList("SELECT * FROM notification_messages ORDER BY id");
            db.update("INSERT INTO growth_accounts(user_id,experience) VALUES(11,5)");
            db.update(
                    "INSERT INTO growth_rewards(user_id,reward_type,business_key,source_type,source_id,amount) VALUES(11,'FIRST_STORE_REVIEW','1','REVIEW',1,5)");
            var oldRewards =
                    db.queryForList(
                            "SELECT id,user_id,reward_type,business_key,source_type,source_id,amount,status,granted_at,revoked_at,disposition_id FROM growth_rewards");
            var next =
                    org.flywaydb.core.Flyway.configure()
                            .dataSource(url(name), "root", password())
                            .locations("classpath:db/migration")
                            .target("30")
                            .load();
            assertThat(next.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(next.migrate().migrationsExecuted).isZero();
            assertThat(db.queryForList("SELECT * FROM notification_messages ORDER BY id"))
                    .isEqualTo(oldNotifications);
            assertThat(
                            db.queryForList(
                                    "SELECT id,user_id,reward_type,business_key,source_type,source_id,amount,status,granted_at,revoked_at,disposition_id FROM growth_rewards"))
                    .isEqualTo(oldRewards);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM content_likes", Long.class))
                    .isZero();
            db.update(
                    "INSERT INTO content_likes(target_type,target_id,user_id) VALUES('REVIEW',1,22)");
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () ->
                            db.update(
                                    "INSERT INTO content_likes(target_type,target_id,user_id) VALUES('REVIEW',1,22)"));
            for (String invalid :
                    List.of(
                            "INSERT INTO content_likes(target_type,target_id,user_id) VALUES('review',1,33)",
                            "INSERT INTO content_likes(target_type,target_id,user_id,active) VALUES('REVIEW',1,33,2)",
                            "INSERT INTO content_likes(target_type,target_id,user_id) VALUES('REVIEW',0,33)")) {
                var failure =
                        org.junit.jupiter.api.Assertions.assertThrows(
                                org.springframework.dao.DataAccessException.class,
                                () -> db.update(invalid));
                assertThat(((SQLException) failure.getMostSpecificCause()).getErrorCode())
                        .isEqualTo(3819);
            }
        } finally {
            if (!name.matches("ginkgo_reply_it_[a-f0-9]{32}")) {
                throw new IllegalStateException("Unexpected test database");
            }
            try (var c = connect("");
                    var st = c.createStatement()) {
                st.executeUpdate("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }

    /** 点赞、取消与恢复沿用同一永久关系，通知和经验各只发生一次。 */
    @Test
    void likesAreIdempotentAndReactivationNeverRenotifiesOrRewards() throws Exception {
        like(other, "REVIEW", 1, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.liked").value(true));
        like(other, "REVIEW", 1, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
        long notice = jdbc.queryForObject("SELECT id FROM notification_messages", Long.class);
        mvc.perform(
                        post("/api/me/notifications/" + notice + "/read")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        var read = jdbc.queryForObject("SELECT read_at FROM notification_messages", Instant.class);
        like(other, "REVIEW", 1, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0));
        like(other, "REVIEW", 1, false).andExpect(status().isOk());
        like(other, "REVIEW", 1, true).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_likes", Long.class))
                .isEqualTo(1);
        assertThat(noticeCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification_messages", Instant.class))
                .isEqualTo(read);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT experience FROM growth_accounts WHERE user_id=11",
                                Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_rewards", Long.class))
                .isEqualTo(1);
    }

    @Test
    void selfLikesAndBadOrMissingTargetsAreRejectedServerSide() throws Exception {
        like(owner, "REVIEW", 1, true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LIKE_SELF_NOT_ALLOWED"));
        mvc.perform(put("/api/reviews/1/like")).andExpect(status().isUnauthorized());
        like(other, "REVIEW", 999, true).andExpect(status().isNotFound());
        mvc.perform(put("/api/reviews/01/like").header("Authorization", "Bearer " + other))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/content-likes").param("type", "BAD").param("ids", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/content-likes").param("type", "REVIEW").param("ids", "1,"))
                .andExpect(status().isBadRequest());
        assertThat(noticeCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_likes", Long.class)).isZero();
    }

    @Test
    void replyLikesNotifyReplyAuthorAndNeverAwardExperience() throws Exception {
        var published = publish(other, 1, input("别人发表的回复")).andReturn();
        long reply =
                json.readTree(published.getResponse().getContentAsString())
                        .get("data")
                        .get("replyId")
                        .asLong();
        like(other, "REPLY", reply, true).andExpect(status().isForbidden());
        like(owner, "REPLY", reply, true).andExpect(status().isOk());
        inbox(other, "LIKES")
                .andExpect(jsonPath("$.data.records[0].notificationType").value("REPLY_LIKED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_rewards", Long.class)).isZero();
        long relation = jdbc.queryForObject("SELECT id FROM content_likes", Long.class);
        mvc.perform(
                        get("/api/me/received-likes/" + relation + "/target")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("别人发表的回复"));
        mvc.perform(
                        get("/api/me/received-likes/" + relation + "/target")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void batchReadsAndCurrentReceivedTotalExcludeDeletedContent() throws Exception {
        like(other, "REVIEW", 1, true).andExpect(status().isOk());
        mvc.perform(get("/api/content-likes").param("type", "REVIEW").param("ids", "1,2,999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].count").value(1))
                .andExpect(jsonPath("$.data.records[0].liked").value(false))
                .andExpect(jsonPath("$.data.records[2].available").value(false));
        mvc.perform(
                        get("/api/content-likes")
                                .param("type", "REVIEW")
                                .param("ids", "1")
                                .header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.data.records[0].liked").value(true));
        mvc.perform(get("/api/me/received-likes").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.data.count").value(1));
        long relation = jdbc.queryForObject("SELECT id FROM content_likes", Long.class);
        jdbc.update("DELETE FROM reviews WHERE id=1");
        mvc.perform(get("/api/me/received-likes").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.data.count").value(0));
        mvc.perform(
                        get("/api/me/received-likes/" + relation + "/target")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        assertThat(noticeCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT experience FROM growth_accounts WHERE user_id=11",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void likesScopeReadAllDoesNotReadCommentsAndViceVersa() throws Exception {
        publish(other, 1, input("评论通知"));
        like(other, "REVIEW", 1, true).andExpect(status().isOk());
        readAll(owner, "LIKES");
        inbox(owner, "COMMENTS").andExpect(jsonPath("$.data.unreadCount").value(1));
        inbox(owner, "LIKES").andExpect(jsonPath("$.data.unreadCount").value(0));
        inbox(owner, "ALL")
                .andExpect(jsonPath("$.data.unreadCounts.LIKES").value(0))
                .andExpect(jsonPath("$.data.unreadCounts.COMMENTS").value(1));
    }

    @Test
    void notificationFailureRollsBackLikeGrowthAndNotificationTogether() throws Exception {
        var real = sql.getMapper(com.ginkgo.foodmap.notification.mapper.NotificationMapper.class);
        doAnswer(
                        call -> {
                            real.insertLike(call.getArgument(0));
                            throw new IllegalStateException("after like notice");
                        })
                .when(notifications)
                .insertLike(any());
        like(other, "REVIEW", 1, true).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_likes", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_rewards", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_accounts", Long.class))
                .isZero();
        assertThat(noticeCount()).isZero();
    }

    @Test
    void transientNoticeFailureRetriesWholeLikeAndExhaustionLeavesNothing() throws Exception {
        var real = sql.getMapper(com.ginkgo.foodmap.notification.mapper.NotificationMapper.class);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(
                        call -> {
                            int result = real.insertLike(call.getArgument(0));
                            if (attempts.getAndIncrement() == 0) {
                                throw new org.springframework.dao.CannotAcquireLockException(
                                        "retry after insert");
                            }
                            return result;
                        })
                .when(notifications)
                .insertLike(any());
        like(other, "REVIEW", 1, true).andExpect(status().isOk());
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(noticeCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT experience FROM growth_accounts WHERE user_id=11",
                                Long.class))
                .isEqualTo(1);
        doAnswer(
                        call -> {
                            real.insertLike(call.getArgument(0));
                            throw new org.springframework.dao.CannotAcquireLockException(
                                    "exhaust after insert");
                        })
                .when(notifications)
                .insertLike(any());
        like(other, "REVIEW", 2, true).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_likes", Long.class))
                .isEqualTo(1);
        assertThat(noticeCount()).isEqualTo(1);
    }

    @Test
    void closedTargetAllowsLikesButHiddenTargetAndOversizeBatchDoNot() throws Exception {
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=1");
        like(other, "REVIEW", 1, true).andExpect(status().isOk());
        jdbc.update(
                "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=1");
        like(other, "REVIEW", 1, false).andExpect(status().isNotFound());
        mvc.perform(get("/api/content-likes").param("type", "REVIEW").param("ids", "1"))
                .andExpect(jsonPath("$.data.records[0].available").value(false))
                .andExpect(jsonPath("$.data.records[0].count").value(0));
        mvc.perform(
                        get("/api/content-likes")
                                .param("type", "REVIEW")
                                .param(
                                        "ids",
                                        String.join(",", java.util.Collections.nCopies(51, "1"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameActorDailyLimitDoesNotPreventOtherLikesOrLaterDayRewards() throws Exception {
        like(other, "REVIEW", 1, true).andExpect(status().isOk());
        like(other, "REVIEW", 2, true).andExpect(status().isOk());
        assertThat(noticeCount()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT experience FROM growth_accounts WHERE user_id=11",
                                Long.class))
                .isEqualTo(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(likeTransactions);
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        var growth = likeGrowth;
        tx.executeWithoutResult(
                status -> growth.grantReviewLike(11, 22, 3, Instant.now().plusSeconds(86400)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT experience FROM growth_accounts WHERE user_id=11",
                                Long.class))
                .isEqualTo(2);
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager likeTransactions;
    @Autowired com.ginkgo.foodmap.growth.service.GrowthRewardApplicationService likeGrowth;

    @Test
    void concurrentDifferentActorsCannotExceedAuthorDailyCap() throws Exception {
        var pool = Executors.newFixedThreadPool(7);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Integer>>();
        try {
            for (long id = 30; id < 37; id++) {
                jdbc.update(
                        "INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(?,?,?,'test',1)",
                        id,
                        "like_" + id,
                        "like" + id + "@example.test");
                jdbc.update(
                        "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",
                        id);
                String actor = token(id);
                long review = id % 2 == 0 ? 1 : 3;
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return like(actor, "REVIEW", review, true)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            start.countDown();
            for (var future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM content_likes WHERE active=1", Long.class))
                .isEqualTo(7);
        assertThat(noticeCount()).isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT experience FROM growth_accounts WHERE user_id=11",
                                Long.class))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM growth_rewards", Long.class))
                .isEqualTo(5);
    }

    @Test
    void concurrentSameLikeCreatesOneRelationNotificationAndReward() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return like(other, "REVIEW", 1, true)
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return like(other, "REVIEW", 1, true)
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_likes", Long.class))
                .isEqualTo(1);
        assertThat(noticeCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM growth_rewards", Long.class))
                .isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions like(
            String token, String type, long id, boolean active) throws Exception {
        String path =
                "/api/"
                        + ("REVIEW".equals(type) ? "reviews" : "review-replies")
                        + "/"
                        + id
                        + "/like";
        return mvc.perform(
                (active ? put(path) : delete(path)).header("Authorization", "Bearer " + token));
    }

    private long noticeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_messages", Long.class);
    }

    private org.springframework.test.web.servlet.ResultActions inbox(String token, String category)
            throws Exception {
        return mvc.perform(
                        get("/api/me/notifications")
                                .param("category", category)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void readAll(String token, String category) throws Exception {
        mvc.perform(
                        post("/api/me/notifications/read-all")
                                .param("category", category)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    private String token(long user) {
        var now = Instant.now();
        var p = authentication.getJwt();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(p.getIssuer())
                        .audience(List.of(p.getAudience()))
                        .subject(Long.toString(user))
                        .issuedAt(now.minusSeconds(120))
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
        var server =
                Objects.requireNonNull(
                        System.getenv("REVIEW_TEST_DB_SERVER"),
                        "Set isolated loopback test server");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback only");
        }
        return server
                + name
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(url(db), "root", password());
    }
}
