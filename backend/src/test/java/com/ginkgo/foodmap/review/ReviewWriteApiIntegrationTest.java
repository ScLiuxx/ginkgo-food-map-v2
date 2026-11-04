package com.ginkgo.foodmap.review;

import com.ginkgo.foodmap.restaurant.service.RestaurantReviewApplicationService;
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
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实MySQL/Flyway/RS256测试；只替换Redis会话依赖，不绕过数据库账号状态。 */
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.datasource.hikari.minimum-idle=0"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named="review.integration",matches="true")
class ReviewWriteApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @MockitoBean LoginSessionService sessions;
    @MockitoSpyBean RestaurantReviewApplicationService statistics;
    private String owner,other,admin;
    private RestaurantReviewApplicationService statisticsTarget;

    /** 测试仅访问显式回环实例，创建成功才登记可清理随机库。 */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database="ginkgo_review_write_it_"+UUID.randomUUID().toString().replace("-", "");
        try(var c=connect("");var s=c.createStatement()) {
            s.executeUpdate("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");created=true;
        }
        registry.add("spring.datasource.url",()->url(database));
        registry.add("spring.datasource.username",()->"root");
        registry.add("spring.datasource.password",ReviewWriteApiIntegrationTest::password);
    }
    @BeforeEach void setup() {
        statisticsTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(statistics);
        reset(sessions,statisticsTarget);
        jdbc.update("DELETE FROM review_replies");jdbc.update("DELETE FROM review_photos");
        jdbc.update("DELETE FROM media_files");jdbc.update("DELETE FROM review_request_receipts");
        jdbc.update("DELETE FROM reviews");jdbc.update("DELETE FROM restaurant_review_statistics");
        jdbc.update("DELETE FROM restaurants");jdbc.update("DELETE FROM user_roles");jdbc.update("DELETE FROM users");
        for(long id:List.of(11L,22L,33L)) {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(?,?,?,?,TRUE)",id,"reviewer_"+id,"reviewer"+id+"@example.test","test-only");
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",id);
        }
        // 正式商户管理已收紧为管理员；普通点评作者仍只具有自己的点评权限。
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT 33,id FROM roles WHERE code='ROLE_ADMIN'");
        jdbc.update("INSERT INTO restaurants(id,name,category_code,created_by) VALUES(1,'发布店','OTHER',11),(2,'另一店','OTHER',11)");
        when(sessions.isActive(anyString(),anyLong(),anyLong())).thenReturn(true);
        owner=token(11,false);other=token(22,false);admin=token(33,false);
    }
    @AfterAll static void cleanup() throws SQLException {
        if(!created)return;
        if(!database.matches("ginkgo_review_write_it_[a-f0-9]{32}"))throw new IllegalStateException("Unexpected database");
        try(var c=connect("");var s=c.createStatement()){s.executeUpdate("DROP DATABASE `"+database+"`");}
    }
    private Map<String,Object> input(double score,boolean recommended,String uuid) {
        return new LinkedHashMap<>(Map.of("clientRequestId",uuid,"scores",Map.of("taste",score,"value",score,"service",score,"ambience",score),"recommended",recommended,"body","这是一条真实测试正文"));
    }
    private org.springframework.test.web.servlet.ResultActions publish(String token,long restaurant,Map<String,Object> data) throws Exception {
        return mvc.perform(post("/api/restaurants/"+restaurant+"/reviews").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data)));
    }
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private void assertStats(long total,long people,long recommended,String sum) {
        var row=jdbc.queryForMap("SELECT * FROM restaurant_review_statistics WHERE restaurant_id=1");
        assertThat(((Number)row.get("review_count")).longValue()).isEqualTo(total);
        assertThat(((Number)row.get("participant_count")).longValue()).isEqualTo(people);
        assertThat(((Number)row.get("recommend_count")).longValue()).isEqualTo(recommended);
        for(String column:List.of("taste_sum","value_sum","service_sum","ambience_sum","overall_score_sum"))
            assertThat((BigDecimal)row.get(column)).isEqualByComparingTo(sum);
    }
    /** 统计推进版本但不伪造资料编辑时间；版本耗尽时评价、回执和统计全部回滚。 */
    @Test void advancesSearchVersionWithoutChangingBusinessAuditAndRollsBackOnOverflow() throws Exception {
        var before = jdbc.queryForMap("SELECT updated_at,updated_by FROM restaurants WHERE id=1");
        var data = input(8,true,UUID.randomUUID().toString());
        publish(owner,1,data).andExpect(status().isCreated());
        assertThat(projectionVersion()).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT updated_at,updated_by FROM restaurants WHERE id=1")).isEqualTo(before);
        jdbc.update("UPDATE restaurants SET search_projection_version=? WHERE id=1", Long.MAX_VALUE);
        publish(owner,1,input(6,false,UUID.randomUUID().toString())).andExpect(status().isInternalServerError());
        assertThat(projectionVersion()).isEqualTo(Long.MAX_VALUE);
        assertThat(count("reviews")).isEqualTo(1);
        assertThat(count("review_request_receipts")).isEqualTo(1);
        assertStats(1,1,1,"8");
        // 原成功请求的幂等重试不需要新版本，耗尽时仍可返回原回执。
        publish(owner,1,data).andExpect(status().isOk());
        long id = jdbc.queryForObject("SELECT id FROM reviews",Long.class);
        removeReview(owner,id).andExpect(status().isInternalServerError());
        assertThat(count("reviews")).isEqualTo(1);assertStats(1,1,1,"8");
    }

    private long projectionVersion() {
        return jdbc.queryForObject("SELECT search_projection_version FROM restaurants WHERE id=1", Long.class);
    }

    @Test void preservesHistoryAndCountsPeopleNotReviews() throws Exception {
        publish(owner,1,input(8,true,UUID.randomUUID().toString())).andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.replayed").value(false));
        publish(owner,1,input(6,false,UUID.randomUUID().toString())).andExpect(status().isCreated());
        publish(other,1,input(9,true,UUID.randomUUID().toString())).andExpect(status().isCreated());
        assertThat(count("reviews")).isEqualTo(3);assertStats(3,2,1,"15");assertThat(projectionVersion()).isEqualTo(4);
        mvc.perform(get("/api/restaurants/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewCount").value(3))
            .andExpect(jsonPath("$.data.participantCount").value(2))
            .andExpect(jsonPath("$.data.score").value(7.5))
            .andExpect(jsonPath("$.data.recommendRate").value(0.5))
            .andExpect(jsonPath("$.data.tier").doesNotExist());
        mvc.perform(get("/api/restaurants/1/management").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.restaurant.score").value(7.5))
            .andExpect(jsonPath("$.data.restaurant.participantCount").value(2));
    }
    @Test void canonicalReplayAndContentConflict() throws Exception {
        String uuid=UUID.randomUUID().toString();var data=input(7,true,uuid);data.put("body","　测试正文五个字\u00a0");
        var response=publish(owner,1,data).andExpect(status().isCreated()).andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).get("data").get("reviewId").asText();
        data.put("body","测试正文五个字");
        publish(owner,1,data).andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewId").value(id)).andExpect(jsonPath("$.data.replayed").value(true));
        data.put("body","这次是不同的正文内容");publish(owner,1,data).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_REQUEST_CONFLICT"));
        publish(owner,2,input(7,true,uuid)).andExpect(status().isConflict());
        publish(other,1,input(7,true,uuid)).andExpect(status().isCreated());assertStats(2,2,2,"14");assertThat(projectionVersion()).isEqualTo(3);
    }
    @Test void rejectsAnonymousExpiredDisabledAndInvalidSession() throws Exception {
        mvc.perform(post("/api/restaurants/1/reviews").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input(7,true,UUID.randomUUID().toString())))).andExpect(status().isUnauthorized());
        when(sessions.isActive(anyString(),anyLong(),anyLong())).thenReturn(false);
        publish(owner,1,input(7,true,UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        when(sessions.isActive(anyString(),anyLong(),anyLong())).thenReturn(true);
        publish(token(11,true),1,input(7,true,UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=11");
        publish(owner,1,input(7,true,UUID.randomUUID().toString())).andExpect(status().isUnauthorized());assertThat(count("reviews")).isZero();
    }
    @Test void validatesFieldsScoresAndUnicode() throws Exception {
        for(double value:List.of(-0.5,10.5,7.1,7.4))publish(owner,1,input(value,true,UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        for(String field:List.of("authorUserId","photos","createdAt","overallScore")) {
            var data=input(7,true,UUID.randomUUID().toString());data.put(field,List.of());publish(owner,1,data).andExpect(status().isBadRequest());
        }
        for(String body:List.of("1234","🥟".repeat(501),"   ")) {
            var data=input(7,true,UUID.randomUUID().toString());data.put("body",body);publish(owner,1,data).andExpect(status().isBadRequest());
        }
        for(String field:List.of("body","recommended","scores","clientRequestId")) {
            var data=input(7,true,UUID.randomUUID().toString());data.remove(field);publish(owner,1,data).andExpect(status().isBadRequest());
        }
        var data=input(0,false,UUID.randomUUID().toString());data.put("body","🥟".repeat(500));publish(owner,1,data).andExpect(status().isCreated());
        data=input(10,true,UUID.randomUUID().toString());data.put("recommended","true");publish(owner,1,data).andExpect(status().isBadRequest());assertStats(1,1,0,"0");
    }
    @Test void allowsClosedButRejectsDeletedMissingAndMalformedRestaurant() throws Exception {
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=1");
        var data=input(7,true,UUID.randomUUID().toString());publish(owner,1,data).andExpect(status().isCreated());
        publish(owner,999,data).andExpect(status().isNotFound());
        mvc.perform(post("/api/restaurants/01/reviews").header("Authorization","Bearer "+owner).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data))).andExpect(status().isBadRequest());
        jdbc.update("UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=1");
        publish(owner,1,input(7,true,UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }
    @Test void rollsBackStatisticsAndReviewAndAllowsRetry() throws Exception {
        // 真实执行统计和版本递增后再失败，证明已推进的版本也随整个发布事务撤销。
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new org.springframework.dao.DataIntegrityViolationException("injected failure after version advance");
        }).when(statisticsTarget).applyDelta(anyLong(),any());
        var data=input(7,true,UUID.randomUUID().toString());publish(owner,1,data).andExpect(status().isConflict());
        assertThat(count("reviews")).isZero();assertThat(count("restaurant_review_statistics")).isZero();assertThat(projectionVersion()).isEqualTo(1);
        reset(statisticsTarget);publish(owner,1,data).andExpect(status().isCreated());assertStats(1,1,1,"7");
    }
    @Test void concurrentReplayAndAuthorUpdatesRemainConsistent() throws Exception {
        try(var pool=Executors.newFixedThreadPool(6)) {
            var data=input(8,true,UUID.randomUUID().toString());var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++)futures.add(pool.submit(()->publish(owner,1,data).andReturn().getResponse().getStatus()));
            var statuses=new ArrayList<Integer>();for(var f:futures)statuses.add(f.get(20,TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(200,201);assertThat(Collections.frequency(statuses,201)).isEqualTo(1);assertStats(1,1,1,"8");futures.clear();
            for(int i=0;i<6;i++){int n=i;futures.add(pool.submit(()->publish(owner,1,input(n,n%2==0,UUID.randomUUID().toString())).andReturn().getResponse().getStatus()));}
            for(var f:futures)assertThat(f.get(20,TimeUnit.SECONDS)).isEqualTo(201);
            var latest=jdbc.queryForMap("SELECT overall_score,is_recommended FROM reviews WHERE restaurant_id=1 ORDER BY created_at DESC,id DESC LIMIT 1");
            long recommended=Boolean.TRUE.equals(latest.get("is_recommended"))?1:0;
            assertStats(7,1,recommended,latest.get("overall_score").toString());
            assertThat(projectionVersion()).isEqualTo(8);
        }
    }
    @Test void concurrentCrossRestaurantUuidHasOneWinner() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var data=input(7,true,UUID.randomUUID().toString());
            var a=pool.submit(()->publish(owner,1,data).andReturn().getResponse().getStatus());
            var b=pool.submit(()->publish(owner,2,data).andReturn().getResponse().getStatus());
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);assertThat(count("reviews")).isEqualTo(1);
        }
    }
    @Test void publishAndDeleteShareParentLock() throws Exception {
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{reached.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");return i.callRealMethod();}).when(statisticsTarget).applyDelta(anyLong(),any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->publish(owner,1,input(7,true,UUID.randomUUID().toString())).andReturn().getResponse().getStatus());
            assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(()->mvc.perform(delete("/api/restaurants/1").header("Authorization","Bearer "+admin)).andReturn().getResponse().getStatus());
            try{assertThat(b.isDone()).isFalse();}finally{release.countDown();}
            assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(201);assertThat(b.get(20,TimeUnit.SECONDS)).isEqualTo(200);
            mvc.perform(get("/api/restaurants/1")).andExpect(status().isNotFound());
            publish(owner,1,input(8,true,UUID.randomUUID().toString())).andExpect(status().isNotFound());assertStats(1,1,1,"7");
        }finally{release.countDown();}
    }
    /** 有界重试必须退出旧事务，耗尽后返回可重试503而不是重计或无限等待。 */
    @Test void retriesWholeTransactionAndStopsAfterThreeFailures() throws Exception {
        doThrow(new org.springframework.dao.CannotAcquireLockException("test-only"))
            .doCallRealMethod().when(statisticsTarget).applyDelta(anyLong(),any());
        publish(owner,1,input(7,true,UUID.randomUUID().toString())).andExpect(status().isCreated());
        assertThat(count("reviews")).isEqualTo(1);assertStats(1,1,1,"7");
        verify(statisticsTarget,times(2)).applyDelta(anyLong(),any());
        reset(statisticsTarget);
        doThrow(new org.springframework.dao.CannotAcquireLockException("test-only"))
            .when(statisticsTarget).applyDelta(anyLong(),any());
        publish(owner,1,input(8,true,UUID.randomUUID().toString())).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("REVIEW_TEMPORARILY_UNAVAILABLE"));
        verify(statisticsTarget,times(3)).applyDelta(anyLong(),any());assertStats(1,1,1,"7");
        assertThat(count("reviews")).isEqualTo(1);
    }
    @Test void futurePreviousTimestampStillAllowsNewRepresentative() throws Exception {
        publish(owner,1,input(8,true,UUID.randomUUID().toString())).andExpect(status().isCreated());
        jdbc.update("UPDATE reviews SET created_at=CURRENT_TIMESTAMP(6)+INTERVAL 1 HOUR");
        publish(owner,1,input(6,false,UUID.randomUUID().toString())).andExpect(status().isCreated());
        assertStats(2,1,0,"6");
        assertThat(jdbc.queryForObject("SELECT overall_score FROM reviews ORDER BY created_at DESC,id DESC LIMIT 1",BigDecimal.class)).isEqualByComparingTo("6");
    }
    /** 删除代表、历史、最后一条分别验证差量，以及匿名/越权和本人重试。 */
    @Test void deletesOnlyOwnReviewsAndRestoresPreviousRepresentative() throws Exception {
        long first = publishId(owner, 8, true);
        long second = publishId(owner, 6, false);
        publishId(other, 9, true);
        mvc.perform(delete("/api/reviews/"+second)).andExpect(status().isUnauthorized());
        removeReview(other, second).andExpect(status().isForbidden());assertStats(3,2,1,"15");assertThat(projectionVersion()).isEqualTo(4);
        removeReview(owner, second).andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"));
        assertStats(2,2,2,"17");
        mvc.perform(get("/api/restaurants/1/reviews")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalReviews").value(2)).andExpect(jsonPath("$.data.totalGroups").value(2));
        removeReview(owner, second).andExpect(status().isNoContent());assertStats(2,2,2,"17");assertThat(projectionVersion()).isEqualTo(5);
        removeReview(other, second).andExpect(status().isForbidden());
        removeReview(owner, first).andExpect(status().isNoContent());assertStats(1,1,1,"9");
        long last=jdbc.queryForObject("SELECT id FROM reviews",Long.class);
        removeReview(other,last).andExpect(status().isNoContent());assertStats(0,0,0,"0");
        mvc.perform(get("/api/restaurants/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewCount").value(0))
            .andExpect(jsonPath("$.data.participantCount").value(0)).andExpect(jsonPath("$.data.score").isEmpty());
        removeReview(owner,999999).andExpect(status().isNotFound());
        mvc.perform(delete("/api/reviews/01").header("Authorization","Bearer "+owner)).andExpect(status().isBadRequest());
    }

    @Test void deletingHistoryDoesNotChangeScoresAndOldPublishCannotResurrect() throws Exception {
        String uuid=UUID.randomUUID().toString();var data=input(8,true,uuid);
        long history=Long.parseLong(json.readTree(publish(owner,1,data).andReturn().getResponse().getContentAsString()).get("data").get("reviewId").asText());
        publishId(owner,6,false);
        removeReview(owner,history).andExpect(status().isNoContent());assertStats(1,1,0,"6");
        publish(owner,1,data).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_REQUEST_DELETED"));
        publish(owner,2,data).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_REQUEST_DELETED"));
        assertThat(count("reviews")).isEqualTo(1);
        publishId(owner,7,true);assertStats(2,1,1,"7");
    }

    /** 注入统计失败证明回执、回复、关联和媒体状态均原子回滚，之后可以安全重试。 */
    @Test void deletionRollsBackChildrenMediaAndReceiptThenSchedulesCleanup() throws Exception {
        long id=publishId(owner,8,true);
        jdbc.update("INSERT INTO media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,bound_at) VALUES(991,11,'test-bucket','review/delete.jpg','image/jpeg',100,10,10,UNHEX(REPEAT('00',32)),'BOUND',CURRENT_TIMESTAMP(6))");
        jdbc.update("INSERT INTO review_photos(review_id,position,media_file_id) VALUES(?,0,991)",id);
        jdbc.update("INSERT INTO review_replies(review_id,author_user_id,body,client_request_id,request_hash) VALUES(?,22,'回复正文',UUID_TO_BIN(?),UNHEX(REPEAT('00',32)))",id,UUID.randomUUID().toString());
        // 删除已完成统计和版本更新后失败，回滚时不得消耗版本或删除媒体关联。
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new org.springframework.dao.DataIntegrityViolationException("injected failure after version advance");
        }).when(statisticsTarget).applyDelta(anyLong(),any());
        removeReview(owner,id).andExpect(status().isConflict());
        assertThat(count("reviews")).isEqualTo(1);assertThat(count("review_replies")).isEqualTo(1);assertThat(count("review_photos")).isEqualTo(1);
        assertThat(projectionVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=991",String.class)).isEqualTo("BOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_request_receipts WHERE deleted_at IS NOT NULL",Long.class)).isZero();assertStats(1,1,1,"8");
        reset(statisticsTarget);removeReview(owner,id).andExpect(status().isNoContent());
        assertThat(count("reviews")).isZero();assertThat(count("review_replies")).isZero();assertThat(count("review_photos")).isZero();assertStats(0,0,0,"0");
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=991",String.class)).isEqualTo("PENDING_DELETE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE expires_at<=CURRENT_TIMESTAMP(6)",Long.class)).isEqualTo(1);
        mvc.perform(get("/api/reviews/"+id+"/photos/0")).andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/"+id+"/replies")).andExpect(status().isNotFound());
    }

    @Test void concurrentDeletionAndPublishingKeepStatisticsConsistent() throws Exception {
        long first=publishId(owner,8,true),second=publishId(owner,6,false);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var a=pool.submit(()->removeReview(owner,second).andReturn().getResponse().getStatus());
            var b=pool.submit(()->removeReview(owner,second).andReturn().getResponse().getStatus());
            var c=pool.submit(()->publish(owner,1,input(9,true,UUID.randomUUID().toString())).andReturn().getResponse().getStatus());
            assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(204);assertThat(b.get(20,TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(c.get(20,TimeUnit.SECONDS)).isEqualTo(201);assertStats(2,1,1,"9");
            removeReview(owner,first).andExpect(status().isNoContent());assertStats(1,1,1,"9");
        }
    }

    private long publishId(String who,double score,boolean recommended) throws Exception {
        var result=publish(who,1,input(score,recommended,UUID.randomUUID().toString())).andExpect(status().isCreated()).andReturn();
        return Long.parseLong(json.readTree(result.getResponse().getContentAsString()).get("data").get("reviewId").asText());
    }
    private org.springframework.test.web.servlet.ResultActions removeReview(String who,long id) throws Exception {
        return mvc.perform(delete("/api/reviews/"+id).header("Authorization","Bearer "+who));
    }

    private String token(long user,boolean expired) {
        var now=Instant.now();var p=authentication.getJwt();
        var claims=JwtClaimsSet.builder().issuer(p.getIssuer()).audience(List.of(p.getAudience())).subject(Long.toString(user))
            .issuedAt(now.minusSeconds(120)).expiresAt(now.plusSeconds(expired?-60:600)).id(UUID.randomUUID().toString()).claim("credential_version",0).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }
    private static String password(){return Objects.requireNonNull(System.getenv("REVIEW_TEST_DB_PASSWORD"),"Set isolated test password");}
    private static String url(String name) {
        var server=System.getenv().getOrDefault("REVIEW_TEST_DB_SERVER","jdbc:mysql://127.0.0.1:3306/");
        if(!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"))throw new IllegalArgumentException("Loopback only");
        return server+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }
    private static Connection connect(String db)throws SQLException{return DriverManager.getConnection(url(db),"root",password());}
}
