package com.ginkgo.foodmap.review;

import com.ginkgo.foodmap.restaurant.service.RestaurantReviewApplicationService;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;
import com.ginkgo.foodmap.review.mapper.ReplyMapper;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实MySQL/Flyway/认证链验证，仅替换Redis会话；随机测试库与业务库隔离。 */
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.datasource.hikari.minimum-idle=0"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named="review.integration",matches="true")
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
    private String owner, other;
    private ReplyMapper delegate;

    /** 仅接受显式回环服务器地址，不加载local配置或已有库。 */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_reply_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = connect(""); var s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", ReplyApiIntegrationTest::password);
    }

    @BeforeEach void setup() {
        reset(sessions, mapper);
        // MyBatis的Mapper是接口代理，故障注入使用独立代理执行真实SQL，不能调用抽象realMethod。
        delegate = sql.getMapper(ReplyMapper.class);
        RestaurantReviewApplicationService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(restaurants);
        reset(target);
        jdbc.update("DELETE FROM review_replies"); jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM restaurant_review_statistics"); jdbc.update("DELETE FROM restaurants");
        jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM users");
        for (long id : List.of(11L,22L)) {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(?,?,?,?,TRUE)", id,"reply_"+id,"reply"+id+"@example.test","test-only");
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",id);
        }
        jdbc.update("INSERT INTO restaurants(id,name,category_code,created_by) VALUES(1,'回复测试店','OTHER',11),(2,'另一店','OTHER',11)");
        for (long id : List.of(1L,2L,3L))
            jdbc.update("INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash) VALUES(?,?,11,7,7,7,7,TRUE,'测试点评正文',UUID_TO_BIN(?),UNHEX(REPEAT('01',32)))",id,id==3?2:1,UUID.randomUUID().toString());
        when(sessions.isActive(anyString(),anyLong(),anyLong())).thenReturn(true);
        owner = token(11); other = token(22);
    }

    @AfterAll static void cleanup() throws SQLException {
        if (!created) return;
        if (!database.matches("ginkgo_reply_it_[a-f0-9]{32}")) throw new IllegalStateException("Unexpected test database");
        try (var c = connect(""); var s = c.createStatement()) { s.executeUpdate("DROP DATABASE `"+database+"`"); }
    }

    private Map<String,Object> input(String body) { return new LinkedHashMap<>(Map.of("body",body,"clientRequestId",UUID.randomUUID().toString())); }
    private org.springframework.test.web.servlet.ResultActions publish(String token, long review, Map<String,Object> input) throws Exception {
        return mvc.perform(post("/api/reviews/"+review+"/replies").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));
    }
    private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM review_replies",Long.class); }

    @Test void normalizesReplayAndRejectsChangedContentOrParent() throws Exception {
        var input = input("　回复🥟\u00a0");
        var first = publish(owner,1,input).andExpect(status().isCreated()).andExpect(jsonPath("$.data.replayed").value(false)).andReturn();
        var id = json.readTree(first.getResponse().getContentAsString()).get("data").get("replyId").asText();
        input.put("body","回复🥟");
        publish(owner,1,input).andExpect(status().isOk()).andExpect(jsonPath("$.data.replyId").value(id)).andExpect(jsonPath("$.data.replayed").value(true));
        publish(owner,2,input).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPLY_REQUEST_CONFLICT"));
        input.put("body","不同正文"); publish(owner,1,input).andExpect(status().isConflict());
        publish(other,1,input).andExpect(status().isCreated()); assertThat(count()).isEqualTo(2);
    }

    @Test void rejectsAnonymousInactiveDisabledAndForgedAuthor() throws Exception {
        mvc.perform(post("/api/reviews/1/replies").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("回复")))).andExpect(status().isUnauthorized());
        when(sessions.isActive(anyString(),anyLong(),anyLong())).thenReturn(false);
        publish(owner,1,input("回复")).andExpect(status().isUnauthorized());
        when(sessions.isActive(anyString(),anyLong(),anyLong())).thenReturn(true);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=11");
        publish(owner,1,input("回复")).andExpect(status().isUnauthorized());
        var forged = input("回复"); forged.put("authorUserId",11);
        publish(other,1,forged).andExpect(status().isBadRequest()); assertThat(count()).isZero();
    }

    @Test void validatesLengthUuidAndPageBounds() throws Exception {
        for (String body : List.of("", "　 ", "🥟".repeat(201))) publish(owner,1,input(body)).andExpect(status().isBadRequest());
        var bad = input("回复"); bad.put("clientRequestId","1-1-1-1-1"); publish(owner,1,bad).andExpect(status().isBadRequest());
        for (String field : List.of("body","clientRequestId")) { bad = input("回复"); bad.remove(field); publish(owner,1,bad).andExpect(status().isBadRequest()); }
        publish(owner,1,input("🥟".repeat(200))).andExpect(status().isCreated());
        for (String size : List.of("0","51","abc")) mvc.perform(get("/api/reviews/1/replies").param("pageSize",size)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reviews/01/replies")).andExpect(status().isBadRequest());
    }

    @Test void readsPublicPagesWithStableTiesAndNoPrivateAuthorFields() throws Exception {
        for (int n=0;n<5;n++) publish(owner,1,input("回复"+n)).andExpect(status().isCreated());
        jdbc.update("UPDATE review_replies SET created_at='2026-10-30 00:00:00.123456'");
        var ids = new ArrayList<String>(); String cursor = null;
        for (int page=0;page<3;page++) {
            var request = get("/api/reviews/1/replies").param("pageSize","2"); if(cursor!=null) request.param("cursor",cursor);
            var response = mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.records[0].author.username").value("reply_11"))
                .andExpect(jsonPath("$.data.records[0].author.email").doesNotExist()).andReturn();
            var data = json.readTree(response.getResponse().getContentAsString()).get("data");
            for (var row : data.get("records")) ids.add(row.get("id").asText());
            assertThat(data.get("hasMore").asBoolean()).isEqualTo(page<2);
            cursor = data.get("nextCursor").isNull()?null:data.get("nextCursor").asText();
        }
        assertThat(ids).doesNotHaveDuplicates().hasSize(5);
        assertThat(ids).isEqualTo(jdbc.queryForList("SELECT CAST(id AS CHAR) FROM review_replies ORDER BY created_at,id",String.class));
    }

    @Test void rejectsCrossParentForgedAndMissingAnchors() throws Exception {
        publish(owner,1,input("回复一")).andExpect(status().isCreated()); publish(owner,1,input("回复二")).andExpect(status().isCreated());
        var response = mvc.perform(get("/api/reviews/1/replies").param("pageSize","1")).andReturn();
        String cursor = json.readTree(response.getResponse().getContentAsString()).get("data").get("nextCursor").asText();
        mvc.perform(get("/api/reviews/2/replies").param("cursor",cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reviews/1/replies").param("cursor",cursor+"=")).andExpect(status().isBadRequest());
        jdbc.update("DELETE FROM review_replies ORDER BY id LIMIT 1");
        mvc.perform(get("/api/reviews/1/replies").param("cursor",cursor)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPLY_INVALID_CURSOR"));
    }

    @Test void historyRepliesStayOnOriginalReviewAndNeverChangeScores() throws Exception {
        String snapshot = "SELECT id,restaurant_id,author_user_id,body,created_at,updated_at,taste_score,value_score,service_score,ambience_score,overall_score,is_recommended,HEX(client_request_id),HEX(request_hash) FROM reviews ORDER BY id";
        var before = jdbc.queryForList(snapshot);
        publish(owner,1,input("旧点评回复")).andExpect(status().isCreated());
        mvc.perform(get("/api/reviews/1/replies")).andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].body").value("旧点评回复"));
        mvc.perform(get("/api/reviews/2/replies")).andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty());
        assertThat(jdbc.queryForList(snapshot)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_review_statistics",Long.class)).isZero();
        verify(org.springframework.test.util.AopTestUtils.<RestaurantReviewApplicationService>getUltimateTargetObject(restaurants),never()).applyDelta(anyLong(),any());
    }

    @Test void missingAndDeletedParentsAreHiddenButClosedStoreAllowsReplies() throws Exception {
        publish(owner,999,input("回复")).andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/999/replies")).andExpect(status().isNotFound());
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=1");
        var input = input("停业也可回复"); publish(owner,1,input).andExpect(status().isCreated());
        jdbc.update("UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=1");
        publish(owner,1,input).andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/1/replies")).andExpect(status().isNotFound());
    }

    @Test void concurrentSameUuidHasOnlyOneInsertAndCrossStoreReuseConflicts() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            var input = input("并发回复"); var futures = new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++) futures.add(pool.submit(() -> publish(owner,1,input).andReturn().getResponse().getStatus()));
            var statuses = new ArrayList<Integer>(); for(var f:futures) statuses.add(f.get(20,TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(200,201); assertThat(Collections.frequency(statuses,201)).isEqualTo(1); assertThat(count()).isEqualTo(1);
            var otherInput = input("跨店竞争");
            var a = pool.submit(() -> publish(owner,1,otherInput).andReturn().getResponse().getStatus());
            var b = pool.submit(() -> publish(owner,3,otherInput).andReturn().getResponse().getStatus());
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
            assertThat(count()).isEqualTo(2);
        }
    }

    @Test void rollbackBeforeRetryAndBoundedFailureDoNotLeaveReplies() throws Exception {
        doAnswer(i -> { delegate.insert(i.getArgument(0)); throw new org.springframework.dao.CannotAcquireLockException("test-only"); })
            .doAnswer(i -> delegate.insert(i.getArgument(0))).when(mapper).insert(any());
        publish(owner,1,input("失败重试")).andExpect(status().isCreated()); assertThat(count()).isEqualTo(1);
        reset(mapper);
        doAnswer(i -> { delegate.insert(i.getArgument(0)); throw new org.springframework.dao.CannotAcquireLockException("test-only"); }).when(mapper).insert(any());
        publish(owner,1,input("持续失败")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("REPLY_TEMPORARILY_UNAVAILABLE"));
        assertThat(count()).isEqualTo(1); verify(mapper,times(3)).insert(any());
    }

    @Test void laterRepliesRemainAfterCursorWhenClockMovesBackwards() throws Exception {
        publish(owner,1,input("第一条")).andExpect(status().isCreated());
        publish(owner,1,input("第二条")).andExpect(status().isCreated());
        jdbc.update("UPDATE review_replies SET created_at=CURRENT_TIMESTAMP(6)+INTERVAL 1 HOUR");
        var response = mvc.perform(get("/api/reviews/1/replies").param("pageSize","1")).andReturn();
        String cursor = json.readTree(response.getResponse().getContentAsString()).get("data").get("nextCursor").asText();
        publish(owner,1,input("第三条")).andExpect(status().isCreated());
        mvc.perform(get("/api/reviews/1/replies").param("cursor",cursor)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].body").value("第二条"))
            .andExpect(jsonPath("$.data.records[1].body").value("第三条"));
    }

    @Test void missingAuthorKeepsStableIdAndFallbackName() throws Exception {
        publish(other,1,input("账号后来不存在")).andExpect(status().isCreated());
        jdbc.update("DELETE FROM user_roles WHERE user_id=22"); jdbc.update("DELETE FROM users WHERE id=22");
        mvc.perform(get("/api/reviews/1/replies")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].author.id").value("22"))
            .andExpect(jsonPath("$.data.records[0].author.username").value("用户不可用"));
    }

    @Test void replyAndDeleteShareParentLock() throws Exception {
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(i -> { reached.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); return delegate.insert(i.getArgument(0)); }).when(mapper).insert(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reply = pool.submit(() -> publish(owner,1,input("与删店竞争")).andReturn().getResponse().getStatus());
            assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();
            var deletion = pool.submit(() -> mvc.perform(delete("/api/restaurants/1").header("Authorization","Bearer "+owner)).andReturn().getResponse().getStatus());
            try { assertThat(deletion.isDone()).isFalse(); } finally { release.countDown(); }
            assertThat(reply.get(20,TimeUnit.SECONDS)).isEqualTo(201); assertThat(deletion.get(20,TimeUnit.SECONDS)).isEqualTo(200);
            mvc.perform(get("/api/reviews/1/replies")).andExpect(status().isNotFound());
        } finally { release.countDown(); }
    }

    private String token(long user) {
        var now=Instant.now(); var p=authentication.getJwt();
        var claims=JwtClaimsSet.builder().issuer(p.getIssuer()).audience(List.of(p.getAudience())).subject(Long.toString(user))
            .issuedAt(now.minusSeconds(120)).expiresAt(now.plusSeconds(600)).id(UUID.randomUUID().toString()).claim("credential_version",0).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }
    private static String password() { return System.getenv().getOrDefault("REVIEW_TEST_DB_PASSWORD", ""); }
    private static String url(String name) {
        var server = Objects.requireNonNull(System.getenv("REVIEW_TEST_DB_SERVER"), "Set isolated loopback test server");
        if(!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) throw new IllegalArgumentException("Loopback only");
        return server+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }
    private static Connection connect(String db) throws SQLException { return DriverManager.getConnection(url(db),"root",password()); }
}
