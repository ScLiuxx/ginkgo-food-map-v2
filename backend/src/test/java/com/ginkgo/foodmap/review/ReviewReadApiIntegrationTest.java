package com.ginkgo.foodmap.review;

import com.ginkgo.foodmap.review.mapper.ReviewReadMapper;
import com.ginkgo.foodmap.review.mapper.ReviewPhotoMapper;
import com.ginkgo.foodmap.user.mapper.PublicAuthorMapper;
import com.ginkgo.foodmap.security.TestJwtKeys;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实MySQL/Flyway/安全过滤链读取验收；随机回环数据库，不读开发库或OSS配置。 */
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.datasource.hikari.minimum-idle=0"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named="review.integration",matches="true")
class ReviewReadApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean ReviewReadMapper reads;
    @MockitoSpyBean ReviewPhotoMapper photos;
    @MockitoSpyBean PublicAuthorMapper authors;
    @MockitoSpyBean com.ginkgo.foodmap.restaurant.service.RestaurantReadService restaurants;

    /** 开关开启时必须提供测试凭证，只连接显式回环地址。 */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database="ginkgo_review_read_it_"+UUID.randomUUID().toString().replace("-", "");
        try(var c=connect("");var s=c.createStatement()) {
            s.executeUpdate("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");created=true;
        }
        registry.add("spring.datasource.url",()->url(database));
        registry.add("spring.datasource.username",()->"root");
        registry.add("spring.datasource.password",ReviewReadApiIntegrationTest::password);
    }

    @BeforeEach void setup() {
        reset(reads,photos,authors,org.springframework.test.util.AopTestUtils.getUltimateTargetObject(restaurants));
        jdbc.update("DELETE FROM review_photos"); jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM restaurant_review_statistics"); jdbc.update("DELETE FROM restaurants");
        jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(11,'read_owner','read11@example.test','private-password',TRUE),(22,'read_other','read22@example.test','private-password',TRUE)");
        jdbc.update("INSERT INTO restaurants(id,name,category_code,created_by) VALUES(1,'读取店','OTHER',11),(2,'空店','OTHER',11)");
    }

    @AfterAll static void cleanup() throws SQLException {
        if(!created)return;
        if(!database.matches("ginkgo_review_read_it_[a-f0-9]{32}"))throw new IllegalStateException("Unexpected database");
        try(var c=connect("");var s=c.createStatement()){s.executeUpdate("DROP DATABASE `"+database+"`");}
    }

    private void review(long id,long restaurant,long author,double score,String time) {
        jdbc.update("INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash,created_at) VALUES(?,?,?,?,?,?,?,TRUE,?,UUID_TO_BIN(?),UNHEX(SHA2('private-hash',256)),?)",
                id,restaurant,author,score,score,score,score,"点评正文🥟第"+id+"条",UUID.randomUUID().toString(),java.time.LocalDateTime.parse(time.replace(' ','T')));
    }

    @Test void anonymousGroupingBeforePaginationAndStableTimeTieBreak() throws Exception {
        review(101,1,11,8,"2026-10-29 01:00:00");
        review(102,1,11,6,"2026-10-29 02:00:00");
        review(103,1,22,9,"2026-10-29 02:00:00");
        review(104,2,22,10,"2026-10-29 03:00:00");
        mvc.perform(get("/api/restaurants/1/reviews").param("pageSize","1"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.totalGroups").value(2)).andExpect(jsonPath("$.data.totalReviews").value(3))
            .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.records.length()").value(1))
            .andExpect(jsonPath("$.data.records[0].author.id").value("22"))
            .andExpect(jsonPath("$.data.records[0].currentReview.id").value("103"))
            .andExpect(jsonPath("$.data.records[0].currentReview.overallScore").value(9))
            .andExpect(jsonPath("$.data.records[0].currentReview.tier").doesNotExist());
        mvc.perform(get("/api/restaurants/1/reviews").param("pageSize","1").param("page","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].currentReview.id").value("102"))
            .andExpect(jsonPath("$.data.records[0].currentReview.scores.taste").value(6))
            .andExpect(jsonPath("$.data.records[0].currentReview.tier").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].historyCount").value(1))
            .andExpect(jsonPath("$.data.records[0].currentReview.scoringStatus").value("current"));
        mvc.perform(get("/api/restaurants/1/reviews").param("page","2147483647").param("pageSize","50"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty())
            .andExpect(jsonPath("$.data.totalGroups").value(2));
    }

    @Test void sameAuthorSameTimestampChoosesHigherIdAndZeroIsValid() throws Exception {
        review(101,1,11,10,"2026-10-29 01:00:00.123456");
        review(102,1,11,0,"2026-10-29 01:00:00.123456");
        mvc.perform(get("/api/restaurants/1/reviews"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].currentReview.id").value("102"))
            .andExpect(jsonPath("$.data.records[0].currentReview.overallScore").value(0))
            .andExpect(jsonPath("$.data.records[0].currentReview.tier").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].currentReview.createdAt").value("2026-10-29T01:00:00.123456Z"))
            .andExpect(jsonPath("$.data.records[0].historyCount").value(1));
    }

    @Test void emptyPageSkipsBatchQueriesAndKeepsPaginationMetadata() throws Exception {
        mvc.perform(get("/api/restaurants/2/reviews"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(8)).andExpect(jsonPath("$.data.totalPages").value(0))
            .andExpect(jsonPath("$.data.totalGroups").value(0)).andExpect(jsonPath("$.data.totalReviews").value(0))
            .andExpect(jsonPath("$.data.records").isEmpty());
        verify(reads,never()).page(anyLong(),anyInt(),anyLong());
        verifyNoInteractions(photos,authors);
    }

    @Test void validatesIdPaginationAndDoesNotOpenOtherReviewRoutes() throws Exception {
        for(String id:List.of("0","01","-1","abc","9223372036854775808"))
            mvc.perform(get("/api/restaurants/"+id+"/reviews")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REVIEW_INVALID_QUERY"));
        for(var pair:List.of(new String[]{"page","0"},new String[]{"page","-1"},new String[]{"page","2147483648"},
                new String[]{"page","1.5"},new String[]{"pageSize","0"},new String[]{"pageSize","51"},new String[]{"pageSize","abc"}))
            mvc.perform(get("/api/restaurants/1/reviews").param(pair[0],pair[1])).andExpect(status().isBadRequest());
        mvc.perform(get("/api/restaurants/1/reviews/me/context")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/restaurants/1/reviews/authors/11/history/management")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/restaurants/1/reviews")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reads,photos,authors);
    }

    @Test void parentVisibilityBeforeReadingAndClosedRestaurantAllowed() throws Exception {
        review(101,1,11,7.5,"2026-10-29 01:00:00");
        mvc.perform(get("/api/restaurants/999/reviews")).andExpect(status().isNotFound());
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=1");
        mvc.perform(get("/api/restaurants/1/reviews")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].currentReview.overallScore").value(7.5));
        clearInvocations(reads);
        jdbc.update("UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=1");
        mvc.perform(get("/api/restaurants/1/reviews")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESTAURANT_NOT_FOUND"));
        verifyNoInteractions(reads);
    }

    @Test void batchesFiveOrderedPhotoReferencesAndOnlyPublicFields() throws Exception {
        review(101,1,11,8,"2026-10-29 01:00:00");
        review(102,1,22,9,"2026-10-29 02:00:00");
        for(int i=4;i>=0;i--)jdbc.update("INSERT INTO review_photos(review_id,position,media_file_id) VALUES(101,?,?)",i,500-i);
        var response=mvc.perform(get("/api/restaurants/1/reviews")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[1].author.username").value("read_owner"))
            .andExpect(jsonPath("$.data.records[1].currentReview.body").value("点评正文🥟第101条"))
            .andExpect(jsonPath("$.data.records[1].currentReview.photos.length()").value(5)).andReturn();
        var data=json.readTree(response.getResponse().getContentAsString()).get("data");
        var refs=data.get("records").get(1).get("currentReview").get("photos");
        for(int i=0;i<5;i++) {
            assertThat(refs.get(i).get("id").asText()).isEqualTo(Integer.toString(500-i));
            assertThat(refs.get(i).get("position").asInt()).isEqualTo(i);
            assertThat(refs.get(i).get("url").asText()).isEqualTo("/api/reviews/101/photos/"+i);
        }
        assertThat(data.toString()).doesNotContain("email", "password", "credential", "requestHash", "clientRequestId",
                "storageKey", "bucketName", "canDelete", "previousReviews", "private-");
        verify(reads,times(1)).counts(1); verify(reads,times(1)).page(1,8,0);
        verify(authors,times(1)).find(List.of(22L,11L));
        verify(photos,times(1)).references(List.of(102L,101L));
    }

    @Test void duplicateDisplayNamesNeverMergeAccountsAndMissingUserHasFallback() throws Exception {
        // 当前users.username唯一；用缺失逻辑账号的相同展示名检验归组不依赖名称，无需破坏约束。
        review(101,1,33,7,"2026-10-29 01:00:00");
        review(102,1,44,8,"2026-10-29 02:00:00");
        mvc.perform(get("/api/restaurants/1/reviews")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalGroups").value(2)).andExpect(jsonPath("$.data.records.length()").value(2))
            .andExpect(jsonPath("$.data.records[0].author.username").value("用户不可用"))
            .andExpect(jsonPath("$.data.records[1].author.username").value("用户不可用"))
            .andExpect(jsonPath("$.data.records[0].author.id").value("44"))
            .andExpect(jsonPath("$.data.records[1].author.id").value("33"));
    }

    @Test void countPageAndAuthorUseOneSnapshotDuringConcurrentCommit() throws Exception {
        review(101,1,11,8,"2026-10-29 01:00:00");
        var target = org.springframework.test.util.AopTestUtils.<com.ginkgo.foodmap.restaurant.service.RestaurantReadService>getUltimateTargetObject(restaurants);
        doAnswer(invocation -> {
            var result=invocation.callRealMethod();
            // 父店可见性已建立快照，独立连接此时提交，后续计数/主卡/署名仍应读取旧状态。
            try(var c=connect(database);var s=c.createStatement()) {
                s.executeUpdate("INSERT INTO reviews(restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash,created_at) VALUES(1,22,9,9,9,9,TRUE,'并发的新点评正文',UUID_TO_BIN(UUID()),UNHEX(SHA2('new',256)),'2026-10-29 02:00:00')");
                s.executeUpdate("UPDATE users SET username='changed_owner' WHERE id=11");
            }
            return result;
        }).when(target).requireVisible(1);
        mvc.perform(get("/api/restaurants/1/reviews")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalGroups").value(1)).andExpect(jsonPath("$.data.totalReviews").value(1))
            .andExpect(jsonPath("$.data.records.length()").value(1))
            .andExpect(jsonPath("$.data.records[0].author.username").value("read_owner"));
        reset(target);
        mvc.perform(get("/api/restaurants/1/reviews")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalGroups").value(2)).andExpect(jsonPath("$.data.records.length()").value(2))
            .andExpect(jsonPath("$.data.records[1].author.username").value("changed_owner"));
    }

    @Test void explainsRepresentativePageForReproducibleHistoryDepths() throws Exception {
        var report=new StringBuilder("MySQL "+jdbc.queryForObject("SELECT VERSION()",String.class)+"; loopback isolated fixture\n");
        for(int depth:List.of(1,10,50)) {
            jdbc.update("DELETE FROM reviews");
            for(int author=1;author<=30;author++)for(int n=0;n<depth;n++)
                review(author*1000L+n,1,author,7,"2026-10-29 01:00:00");
            var sql=ReviewReadMapper.PAGE_SQL.replace("#{restaurant}","1").replace("#{limit}","8").replace("#{offset}","8");
            report.append("\n30 authors; ").append(depth).append(" reviews/author; rows=").append(30*depth).append("\n").append(sql);
            for(var row:jdbc.queryForList("EXPLAIN "+sql)) report.append(row).append('\n');
            for(var row:jdbc.queryForList("EXPLAIN ANALYZE "+sql)) report.append(row).append('\n');
            assertThat(reads.page(1,8,8)).hasSize(8).allSatisfy(r -> assertThat(r.getHistoryCount()).isEqualTo(depth-1));
        }
        var path=Path.of("target/review-read-explain.txt"); Files.createDirectories(path.getParent());
        Files.writeString(path,report);
    }

    /** 同时间使用ID作第二排序键；默认5条及后续段不能重复当前点评。 */
    @Test void historyCursorPagesPhotosAndScopes() throws Exception {
        for(int i=101;i<=108;i++) review(i,1,11,8,"2026-10-29 01:00:00.123456");
        review(201,1,22,9,"2026-10-29 01:00:00.123456");
        review(301,2,11,9,"2026-10-29 01:00:00.123456");
        for(int i=4;i>=0;i--)jdbc.update("INSERT INTO review_photos(review_id,position,media_file_id) VALUES(107,?,?)",i,500-i);
        var list=json.readTree(mvc.perform(get("/api/restaurants/1/reviews")).andReturn().getResponse().getContentAsString());
        String cursor=list.get("data").get("records").get(1).get("nextHistoryCursor").asText();
        var first=mvc.perform(get("/api/restaurants/1/reviews/authors/11/history").param("cursor",cursor))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.currentReviewId").value("108"))
            .andExpect(jsonPath("$.data.records.length()").value(5))
            .andExpect(jsonPath("$.data.records[0].id").value("107"))
            .andExpect(jsonPath("$.data.records[4].id").value("103"))
            .andExpect(jsonPath("$.data.records[0].scoringStatus").value("history"))
            .andExpect(jsonPath("$.data.records[0].photos[4].url").value("/api/reviews/107/photos/4"))
            .andExpect(jsonPath("$.data.hasMore").value(true)).andReturn();
        var data=json.readTree(first.getResponse().getContentAsString()).get("data");
        assertThat(data.toString()).doesNotContain("password","email","storageKey","requestHash","clientRequestId");
        mvc.perform(get("/api/restaurants/1/reviews/authors/11/history").param("cursor",data.get("nextCursor").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(2))
            .andExpect(jsonPath("$.data.records[0].id").value("102"))
            .andExpect(jsonPath("$.data.records[1].id").value("101"))
            .andExpect(jsonPath("$.data.hasMore").value(false)).andExpect(jsonPath("$.data.nextCursor").isEmpty());
        for(String path:List.of("/api/restaurants/2/reviews/authors/11/history","/api/restaurants/1/reviews/authors/22/history"))
            mvc.perform(get(path).param("cursor",cursor)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REVIEW_INVALID_CURSOR"));
        // 新主卡出现时返回最新ID；客户端据此丢弃旧段并刷新列表。
        review(109,1,11,7,"2026-10-29 02:00:00");
        mvc.perform(get("/api/restaurants/1/reviews/authors/11/history").param("cursor",cursor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentReviewId").value("109"));
        mvc.perform(get("/api/restaurants/1/reviews/authors/11/history"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value("108"));
    }

    @Test void historyValidationVisibilityAndEmptyAuthor() throws Exception {
        mvc.perform(get("/api/restaurants/1/reviews/authors/99/history"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty())
            .andExpect(jsonPath("$.data.currentReviewId").isEmpty()).andExpect(jsonPath("$.data.hasMore").value(false));
        for(String id:List.of("0","01","abc","9223372036854775808"))
            mvc.perform(get("/api/restaurants/1/reviews/authors/"+id+"/history")).andExpect(status().isBadRequest());
        for(String size:List.of("0","21","abc"))
            mvc.perform(get("/api/restaurants/1/reviews/authors/11/history").param("pageSize",size)).andExpect(status().isBadRequest());
        review(101,1,11,8,"2026-10-29 01:00:00.123456");
        var time=java.time.Instant.parse("2026-10-29T01:00:00.123456Z");
        for(String cursor:List.of("!bad", "", "a".repeat(201),
            com.ginkgo.foodmap.review.policy.ReviewHistoryCursorPolicy.encode(1,11,time,999),
            com.ginkgo.foodmap.review.policy.ReviewHistoryCursorPolicy.encode(1,11,time.plusSeconds(1),101)))
            mvc.perform(get("/api/restaurants/1/reviews/authors/11/history").param("cursor",cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/restaurants/999/reviews/authors/11/history")).andExpect(status().isNotFound());
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=1");
        mvc.perform(get("/api/restaurants/1/reviews/authors/11/history")).andExpect(status().isOk());
        jdbc.update("UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=1");
        mvc.perform(get("/api/restaurants/1/reviews/authors/11/history")).andExpect(status().isNotFound());
    }

    /** 父店建立快照之后并发发布，不得改变本段代表或作者资料。 */
    @Test void historyRepresentativeAnchorAndAuthorShareSnapshot() throws Exception {
        review(101,1,11,8,"2026-10-29 01:00:00");
        review(102,1,11,9,"2026-10-29 02:00:00");
        var target = org.springframework.test.util.AopTestUtils.<com.ginkgo.foodmap.restaurant.service.RestaurantReadService>getUltimateTargetObject(restaurants);
        doAnswer(invocation -> {
            var result=invocation.callRealMethod();
            try(var c=connect(database);var st=c.createStatement()) {
                st.executeUpdate("INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash,created_at) VALUES(103,1,11,9,9,9,9,TRUE,'并发更新正文',UUID_TO_BIN(UUID()),UNHEX(SHA2('new',256)),'2026-10-29 03:00:00')");
                st.executeUpdate("UPDATE users SET username='changed_owner' WHERE id=11");
            }
            return result;
        }).when(target).requireVisible(1);
        mvc.perform(get("/api/restaurants/1/reviews/authors/11/history"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentReviewId").value("102"))
            .andExpect(jsonPath("$.data.author.username").value("read_owner"))
            .andExpect(jsonPath("$.data.records[0].id").value("101"));
        reset(target);
        mvc.perform(get("/api/restaurants/1/reviews/authors/11/history"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentReviewId").value("103"))
            .andExpect(jsonPath("$.data.records[0].id").value("102"));
    }

    @Test void explainsHistorySeekWithDeepFixture() throws Exception {
        for(int author=1;author<=30;author++)for(int n=0;n<50;n++)
            review(author*1000L+n,1,author,7,"2026-10-29 01:00:00.123456");
        String sql="SELECT id,created_at FROM reviews WHERE restaurant_id=1 AND author_user_id=11 AND (created_at < '2026-10-29 01:00:00.123456' OR (created_at = '2026-10-29 01:00:00.123456' AND id<11040)) ORDER BY created_at DESC,id DESC LIMIT 6";
        var report=new StringBuilder("Isolated MySQL "+jdbc.queryForObject("SELECT VERSION()",String.class)+"; 30 authors x 50 reviews\n"+sql+"\n");
        for(var row:jdbc.queryForList("EXPLAIN "+sql))report.append(row).append('\n');
        for(var row:jdbc.queryForList("EXPLAIN ANALYZE "+sql))report.append(row).append('\n');
        Files.writeString(Path.of("target/review-history-explain.txt"),report);
        assertThat(jdbc.queryForList(sql)).hasSize(6);
    }

    private static String password(){return Objects.requireNonNull(System.getenv("REVIEW_TEST_DB_PASSWORD"),"Set isolated test password");}
    private static String url(String name) {
        var server=System.getenv().getOrDefault("REVIEW_TEST_DB_SERVER","jdbc:mysql://127.0.0.1:3306/");
        if(!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"))throw new IllegalArgumentException("Loopback only");
        return server+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }
    private static Connection connect(String db)throws SQLException{return DriverManager.getConnection(url(db),"root",password());}
}
