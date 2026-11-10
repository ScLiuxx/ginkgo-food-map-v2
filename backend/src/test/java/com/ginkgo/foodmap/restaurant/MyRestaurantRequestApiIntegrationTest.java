package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ginkgo.foodmap.file.storage.OssStorageClient;
import com.ginkgo.foodmap.restaurant.mapper.MyRestaurantRequestMapper;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 随机回环MySQL/Flyway与真实JWT验证本人申请边界；仅Redis会话和OSS字节使用替身。 */
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
@EnabledIfSystemProperty(named = "request.integration", matches = "true")
class MyRestaurantRequestApiIntegrationTest {
    private static final String BASE = "/api/me/restaurant-requests";
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @Autowired org.mybatis.spring.SqlSessionTemplate sql;
    @MockitoBean LoginSessionService sessions;
    @MockitoBean OssStorageClient storage;
    @MockitoSpyBean MyRestaurantRequestMapper requests;
    private String owner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_request_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", MyRestaurantRequestApiIntegrationTest::username);
        registry.add("spring.datasource.password", MyRestaurantRequestApiIntegrationTest::password);
    }

    @BeforeEach
    void setup() {
        reset(sessions, storage, requests);
        // 重提申请通过自关联指向原驳回记录，清理夹具必须先断开自引用再删除父行。
        jdbc.update("UPDATE restaurant_change_requests SET previous_request_id=NULL");
        for (String table :
                List.of(
                        "restaurant_change_photos",
                        "restaurant_change_requests",
                        "media_files",
                        "restaurants",
                        "user_roles",
                        "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (long id : List.of(11L, 22L, 33L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(?,?,?,?,TRUE)",
                    id,
                    "request_" + id,
                    "private" + id + "@example.test",
                    "unused-private-hash");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",
                    id,
                    id == 33 ? "ROLE_ADMIN" : "ROLE_USER");
        }
        jdbc.update(
                "INSERT INTO restaurants(id,name,category_code) VALUES(501,'当前店名','OTHER'),(502,'失效店铺','OTHER')");
        jdbc.update(
                "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),deleted_by=33,delete_reason_code='ADMIN_REMOVAL' WHERE id=502");
        seed(101, 11, "CREATE", null, "PENDING", "原投稿", "{}");
        seed(
                102,
                11,
                "CREATE",
                501L,
                "APPROVED",
                "提交时的店名",
                "{\"name\":\"审核修订店名\",\"categoryCode\":\"OTHER\",\"clientRequestId\":\"private-approved\"}");
        seed(103, 11, "UPDATE", 501L, "REJECTED", "被驳回纠错", "{}");
        seed(104, 11, "UPDATE", 502L, "PENDING", "失效店铺纠错", "{}");
        seed(201, 22, "CREATE", null, "PENDING", "其他用户私有店名", "{}");
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        owner = token(11);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (created) {
            drop(database);
        }
    }

    @Test
    void readsOnlyCurrentOwnerWithStablePaginationCountsAndNoWrites() throws Exception {
        var before = rows();
        getOwn("?userId=22&submittedBy=22&offset=900&pageSize=2")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.userId").value("11"))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value("104"))
                .andExpect(jsonPath("$.data.records[1].id").value("103"))
                .andExpect(jsonPath("$.data.counts.ALL").value(4))
                .andExpect(jsonPath("$.data.counts.PENDING").value(2))
                .andExpect(jsonPath("$.data.counts.APPROVED").value(1))
                .andExpect(jsonPath("$.data.counts.REJECTED").value(1));
        getOwn("?pageSize=2&page=2")
                .andExpect(jsonPath("$.data.records[0].id").value("102"))
                .andExpect(jsonPath("$.data.records[1].id").value("101"));
        getOwn("?page=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.total").value(4));
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void statusFiltersUseOwnCountsAndTrueEmptyAccountReturnsZero() throws Exception {
        for (String status : List.of("PENDING", "APPROVED", "REJECTED")) {
            getOwn("?status=" + status)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.records[0].status").value(status))
                    .andExpect(jsonPath("$.data.total").value(status.equals("PENDING") ? 2 : 1))
                    .andExpect(jsonPath("$.data.counts.ALL").value(4));
        }
        mvc.perform(get(BASE).header("Authorization", "Bearer " + token(33)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("33"))
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.counts.ALL").value(0));
    }

    @Test
    void ownDetailsSeparateOriginalAndApprovedSnapshotsWithoutManagementFields() throws Exception {
        var response =
                getOwn("/102")
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.application.requestType").value("CREATE"))
                        .andExpect(jsonPath("$.data.proposed.name").value("提交时的店名"))
                        .andExpect(jsonPath("$.data.approved.name").value("审核修订店名"))
                        .andExpect(jsonPath("$.data.targetAvailable").value(true))
                        .andReturn();
        assertThat(response.getResponse().getContentAsString())
                .doesNotContain(
                        "requestHash",
                        "clientRequestId",
                        "photoIds",
                        "campusCode",
                        "reviewedBy",
                        "before",
                        "current",
                        "private-approved",
                        "unused-private-hash",
                        "@example.test");
        getOwn("/103")
                .andExpect(jsonPath("$.data.rejectReason").value("地址需要补充，\n请核对店名"))
                .andExpect(jsonPath("$.data.approved").isEmpty());
        getOwn("/101")
                .andExpect(jsonPath("$.data.application.restaurantId").isEmpty())
                .andExpect(jsonPath("$.data.targetAvailable").value(false));
    }

    @Test
    void deletedTargetKeepsPrivateHistoryAndClosedTargetRemainsNavigable() throws Exception {
        getOwn("/104")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetAvailable").value(false))
                .andExpect(jsonPath("$.data.proposed.name").value("失效店铺纠错"));
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=501");
        getOwn("/102").andExpect(jsonPath("$.data.targetAvailable").value(true));
    }

    @Test
    void anonymousInactiveAndOtherOwnerCannotReadRecordsOrPhotos() throws Exception {
        for (String path : List.of("", "/101", "/101/photos/0")) {
            mvc.perform(get(BASE + path)).andExpect(status().isUnauthorized());
        }
        getOwn("/201")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESTAURANT_REQUEST_NOT_FOUND"));
        getOwn("/999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESTAURANT_REQUEST_NOT_FOUND"));
        getOwn("/201/photos/0").andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/101").header("Authorization", "Bearer " + token(33)))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE users SET status='LOCKED' WHERE id=11");
        getOwn("").andExpect(status().isUnauthorized());
        verifyNoInteractions(storage);
    }

    @Test
    void photoReadsAreBoundToOwnerPurposeAndStatusAcrossAllReviewStates() throws Exception {
        byte[] jpeg = {(byte) 255, (byte) 216, (byte) 255, (byte) 217};
        for (int request : List.of(101, 102, 103)) {
            long file = request + 800;
            photo(request, file, 11, "RESTAURANT", "BOUND");
            when(storage.read("private-test", "restaurant-images/" + file + ".jpg"))
                    .thenReturn(jpeg);
            getOwn("/" + request + "/photos/0")
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(content().contentType("image/jpeg"))
                    .andExpect(content().bytes(jpeg));
            getOwn("/" + request)
                    .andExpect(jsonPath("$.data.photoPositions[0]").value(0))
                    .andExpect(jsonPath("$.data.application.photoCount").value(1));
        }
        reset(storage);
        jdbc.update("UPDATE media_files SET uploader_user_id=22 WHERE id=901");
        getOwn("/101/photos/0").andExpect(status().isNotFound());
        jdbc.update("UPDATE media_files SET purpose='REVIEW' WHERE id=902");
        getOwn("/102/photos/0").andExpect(status().isNotFound());
        jdbc.update("UPDATE media_files SET status='PENDING_DELETE' WHERE id=903");
        getOwn("/103/photos/0").andExpect(status().isNotFound());
        getOwn("/104/photos/0").andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @Test
    void invalidParametersIdsPositionsAndWriteMethodsAreRejected() throws Exception {
        for (String query :
                List.of(
                        "?status=UNKNOWN",
                        "?status=",
                        "?page=0",
                        "?page=1000001",
                        "?pageSize=0",
                        "?pageSize=51",
                        "?page=no")) {
            getOwn(query).andExpect(status().isBadRequest());
        }
        for (String id : List.of("0", "01", "-1", "9223372036854775808", "abc")) {
            getOwn("/" + id).andExpect(status().isBadRequest());
        }
        for (String position : List.of("5", "-1", "00", "abc")) {
            getOwn("/101/photos/" + position).andExpect(status().isBadRequest());
        }
        mvc.perform(
                        patch(BASE + "/101")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void failuresAreNotEmptyListsOrMissingPictures() throws Exception {
        doThrow(new DataAccessResourceFailureException("test-only"))
                .when(requests)
                .counts(anyLong());
        getOwn("")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
        doThrow(new DataAccessResourceFailureException("test-only"))
                .when(requests)
                .detail(eq(11L), eq(101L));
        getOwn("/101").andExpect(status().isInternalServerError());
        photo(102, 902, 11, "RESTAURANT", "BOUND");
        when(storage.read(anyString(), anyString()))
                .thenThrow(
                        new com.ginkgo.foodmap.common.exception.BusinessException(
                                com.ginkgo.foodmap.file.exception.FileErrorCode.UNAVAILABLE));
        getOwn("/102/photos/0").andExpect(status().isServiceUnavailable());
    }

    @Test
    void countsAndRowsRemainInSameSnapshotDuringConcurrentReview() throws Exception {
        doAnswer(
                        call -> {
                            // Mapper是MyBatis动态代理，没有可调用的Java方法体；从同一事务SqlSession取得未被Spy包装的代理执行真实SQL。
                            var counts =
                                    sql.getMapper(MyRestaurantRequestMapper.class)
                                            .counts(call.getArgument(0));
                            CompletableFuture.runAsync(
                                            () ->
                                                    jdbc.update(
                                                            "UPDATE restaurant_change_requests SET status='REJECTED',reviewed_by=33,reviewed_at=CURRENT_TIMESTAMP(6),reject_reason='并发审核' WHERE id=101"))
                                    .get(10, TimeUnit.SECONDS);
                            return counts;
                        })
                .when(requests)
                .counts(eq(11L));
        getOwn("?status=PENDING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[1].status").value("PENDING"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM restaurant_change_requests WHERE id=101",
                                String.class))
                .isEqualTo("REJECTED");
    }

    @Test
    void v26UpgradeOnlyAddsSubmitterIndexAndCanRepeat() throws Exception {
        String name = "ginkgo_request_upgrade_it_" + UUID.randomUUID().toString().replace("-", "");
        boolean made = false;
        try {
            try (var c = connect("");
                    var s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE DATABASE `"
                                + name
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                made = true;
            }
            Flyway.configure()
                    .dataSource(url(name), username(), password())
                    .locations("classpath:db/migration")
                    .target("26")
                    .load()
                    .migrate();
            var db =
                    new JdbcTemplate(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                    url(name), username(), password()));
            db.update(
                    "INSERT INTO restaurant_change_requests(id,submitted_by,request_type,client_request_id,request_hash,proposed_data) VALUES(71,11,'CREATE',?,UNHEX(SHA2('original',256)),JSON_OBJECT('name','旧申请'))",
                    UUID.randomUUID().toString());
            var before = db.queryForMap("SELECT * FROM restaurant_change_requests WHERE id=71");
            // 固定V27验收边界，后续迁移另外验证，不能把新增迁移误判为索引重复执行。
            var flyway =
                    Flyway.configure()
                            .dataSource(url(name), username(), password())
                            .locations("classpath:db/migration")
                            .target("27")
                            .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(db.queryForMap("SELECT * FROM restaurant_change_requests WHERE id=71"))
                    .usingRecursiveComparison()
                    .isEqualTo(before);
            assertThat(
                            db.queryForList(
                                    "SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='restaurant_change_requests' AND index_name='idx_restaurant_change_submitter_time' ORDER BY seq_in_index",
                                    String.class))
                    .containsExactly("submitted_by", "created_at", "id");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            // V27非空库升级V28只建空收件箱，不重写旧申请，也不补发历史通知。
            var latest =
                    Flyway.configure()
                            .dataSource(url(name), username(), password())
                            .locations("classpath:db/migration")
                            .target("28")
                            .load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(db.queryForMap("SELECT * FROM restaurant_change_requests WHERE id=71"))
                    .usingRecursiveComparison()
                    .isEqualTo(before);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_messages", Long.class))
                    .isZero();
            assertThat(latest.migrate().migrationsExecuted).isZero();
        } finally {
            if (made) {
                drop(name);
            }
        }
    }

    @Test
    void pendingRequestCanBeEditedWithFullReplacementAndOwnerBoundary() throws Exception {
        String beforeHash =
                jdbc.queryForObject(
                        "SELECT HEX(request_hash) FROM restaurant_change_requests WHERE id=101",
                        String.class);
        write(put(BASE + "/101"), editBody("修改后的店名"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.id").value("101"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT JSON_UNQUOTE(JSON_EXTRACT(proposed_data,'$.name')) FROM restaurant_change_requests WHERE id=101",
                                String.class))
                .isEqualTo("修改后的店名");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT HEX(request_hash) FROM restaurant_change_requests WHERE id=101",
                                String.class))
                .isNotEqualTo(beforeHash);
        getOwn("/101")
                .andExpect(jsonPath("$.data.application.status").value("PENDING"))
                .andExpect(jsonPath("$.data.proposed.name").value("修改后的店名"));

        // 已通过、已驳回、目标失效和其他人的申请都不能修改，且不泄漏申请是否存在。
        write(put(BASE + "/102"), editBody("不能修改已通过"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTAURANT_STATE_CONFLICT"));
        write(put(BASE + "/103"), editBody("不能修改已驳回"))
                .andExpect(status().isConflict());
        write(put(BASE + "/104"), editBody("不能修改失效目标"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        put(BASE + "/201")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content(json.writeValueAsString(editBody("不能修改他人申请"))))
                .andExpect(status().isNotFound());
        write(put(BASE + "/101"), editBody(" "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withdrawIsIdempotentForOwnerAndRejectsReviewedOrForeignRequests() throws Exception {
        write(post(BASE + "/101/withdraw"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        var withdrawnAt =
                jdbc.queryForObject(
                        "SELECT withdrawn_at FROM restaurant_change_requests WHERE id=101",
                        Timestamp.class);
        assertThat(withdrawnAt).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_change_requests WHERE id=101 AND reviewed_by IS NULL",
                                Long.class))
                .isEqualTo(1L);
        write(post(BASE + "/101/withdraw"), null).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT withdrawn_at FROM restaurant_change_requests WHERE id=101",
                                Timestamp.class))
                .isEqualTo(withdrawnAt);

        write(put(BASE + "/101"), editBody("撤回后不能修改"))
                .andExpect(status().isConflict());
        write(post(BASE + "/101/resubmit"), submission("撤回后不能重提"))
                .andExpect(status().isConflict());
        write(post(BASE + "/102/withdraw"), null).andExpect(status().isConflict());
        write(post(BASE + "/103/withdraw"), null).andExpect(status().isConflict());
        mvc.perform(
                        post(BASE + "/201/withdraw")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        getOwn("?status=WITHDRAWN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.counts.WITHDRAWN").value(1))
                .andExpect(jsonPath("$.data.records[0].withdrawnAt").isNotEmpty());
    }

    @Test
    void rejectedRequestCanBeResubmittedWithPreviousLinkAndOriginalPreserved() throws Exception {
        var body = submission("重提修订店名");
        var first =
                write(post(BASE + "/103/resubmit"), body)
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.status").value("PENDING"))
                        .andReturn();
        String newId =
                json.readTree(first.getResponse().getContentAsString())
                        .get("data")
                        .get("id")
                        .asString();
        var saved =
                jdbc.queryForMap(
                        "SELECT status,request_type,restaurant_id,previous_request_id,submitted_by FROM restaurant_change_requests WHERE id=?",
                        Long.parseLong(newId));
        assertThat(saved)
                .containsEntry("status", "PENDING")
                .containsEntry("request_type", "UPDATE");
        // MySQL驱动可能按列宽度返回Integer，统一转long比较，避免夹具受驱动数值类型影响。
        assertThat(((Number) saved.get("restaurant_id")).longValue()).isEqualTo(501L);
        assertThat(((Number) saved.get("previous_request_id")).longValue()).isEqualTo(103L);
        assertThat(((Number) saved.get("submitted_by")).longValue()).isEqualTo(11L);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT status,reject_reason FROM restaurant_change_requests WHERE id=103"))
                .containsEntry("status", "REJECTED")
                .containsEntry("reject_reason", "地址需要补充，\n请核对店名");
        // 相同请求UUID和相同内容重试返回新申请，不重复创建。
        write(post(BASE + "/103/resubmit"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(newId));
        getOwn("/" + newId)
                .andExpect(jsonPath("$.data.application.previousRequestId").value("103"))
                .andExpect(jsonPath("$.data.proposed.name").value("重提修订店名"));
        // 同一个UUID不能用于不同内容或另一个目标。
        var changed = submission("另一种内容");
        changed.put("clientRequestId", body.get("clientRequestId"));
        write(post(BASE + "/103/resubmit"), changed).andExpect(status().isConflict());
        write(post(BASE + "/101/resubmit"), submission("待审不能重提"))
                .andExpect(status().isConflict());
        mvc.perform(
                        post(BASE + "/201/resubmit")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content(json.writeValueAsString(submission("他人不能重提"))))
                .andExpect(status().isNotFound());

        // 新店驳回后重提仍为CREATE且不携带目标ID。
        jdbc.update(
                "INSERT INTO restaurant_change_requests(id,request_type,submitted_by,client_request_id,request_hash,proposed_data,status,reviewed_by,reviewed_at,reject_reason) VALUES(105,'CREATE',11,?,UNHEX(SHA2('fixture',256)),JSON_OBJECT('name','驳回新店'),'REJECTED',33,CURRENT_TIMESTAMP(6),'资料不足')",
                UUID.randomUUID().toString());
        var created =
                write(post(BASE + "/105/resubmit"), submission("重提新店"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String createdId =
                json.readTree(created.getResponse().getContentAsString())
                        .get("data")
                        .get("id")
                        .asString();
        var createdRow =
                jdbc.queryForMap(
                        "SELECT status,request_type,restaurant_id,previous_request_id FROM restaurant_change_requests WHERE id=?",
                        Long.parseLong(createdId));
        assertThat(createdRow)
                .containsEntry("status", "PENDING")
                .containsEntry("request_type", "CREATE");
        // 驱动可能将窄整型映射为Integer，统一按long比较。
        assertThat(((Number) createdRow.get("previous_request_id")).longValue()).isEqualTo(105L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT restaurant_id FROM restaurant_change_requests WHERE id=?",
                                Long.class,
                                Long.parseLong(createdId)))
                .isNull();
    }

    @Test
    void editingPhotosReleasesRemovedFilesAndRebindsOnlyNewOnes() throws Exception {
        long first = temporaryPhoto(1001);
        long second = temporaryPhoto(1002);
        write(put(BASE + "/101"), editBody("保留两张图", Long.toString(first), Long.toString(second)))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForList(
                                "SELECT media_file_id FROM restaurant_change_photos WHERE request_id=101 ORDER BY position",
                                Long.class))
                .containsExactly(first, second);

        long replacement = temporaryPhoto(1003);
        write(put(BASE + "/101"), editBody("替换第二张", Long.toString(first), Long.toString(replacement)))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForList(
                                "SELECT media_file_id FROM restaurant_change_photos WHERE request_id=101 ORDER BY position",
                                Long.class))
                .containsExactly(first, replacement);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM media_files WHERE id=?", String.class, second))
                .isEqualTo("PENDING_DELETE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM media_files WHERE id=?", String.class, replacement))
                .isEqualTo("BOUND");

        write(put(BASE + "/101"), editBody("清空图片"))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_change_photos WHERE request_id=101",
                                Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM media_files WHERE id=?", String.class, first))
                .isEqualTo("PENDING_DELETE");
    }

    @Test
    void anonymousWritesAreRejected() throws Exception {
        for (String path : List.of("/101/withdraw", "/101/resubmit")) {
            mvc.perform(post(BASE + path).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(
                        put(BASE + "/101")
                                .contentType("application/json")
                                .content(json.writeValueAsString(editBody("匿名修改"))))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions write(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            Map<String, ?> body)
            throws Exception {
        builder.header("Authorization", "Bearer " + owner);
        if (body != null) {
            builder.contentType("application/json").content(json.writeValueAsString(body));
        }
        return mvc.perform(builder);
    }

    /** 修改与重提共用的完整业务内容，分类使用已启用的OTHER。 */
    private Map<String, Object> editBody(String name, String... photoIds) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("categoryCode", "OTHER");
        body.put("restaurantType", "REGULAR");
        body.put("priceKind", "UNKNOWN");
        body.put("latitude", 30.75);
        body.put("longitude", 103.93);
        body.put("locationConfirmed", true);
        body.put("businessStatus", "OPERATING");
        body.put("tags", List.of("测试"));
        body.put("photoIds", List.of(photoIds));
        return body;
    }

    private Map<String, Object> submission(String name) {
        var body = editBody(name);
        body.put("clientRequestId", UUID.randomUUID().toString());
        return body;
    }

    private long temporaryPhoto(long file) {
        jdbc.update(
                "INSERT INTO media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,purpose,expires_at) VALUES(?,11,'private-test',?,'image/jpeg',4,1,1,UNHEX(SHA2('photo',256)),'TEMPORARY','RESTAURANT',TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)))",
                file,
                "restaurant-images/" + file + ".jpg");
        return file;
    }

    private ResultActions getOwn(String suffix) throws Exception {
        return mvc.perform(get(BASE + suffix).header("Authorization", "Bearer " + owner));
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList(
                "SELECT id,status,submitted_by,proposed_data,approved_data,reviewed_at FROM restaurant_change_requests ORDER BY id");
    }

    private void seed(
            long id,
            long owner,
            String type,
            Long target,
            String status,
            String name,
            String approved) {
        var proposed = new LinkedHashMap<String, Object>();
        proposed.put("name", name);
        proposed.put("categoryCode", "OTHER");
        proposed.put("restaurantType", "REGULAR");
        proposed.put("clientRequestId", "private-request-id");
        proposed.put("photoIds", List.of("private-file-id"));
        proposed.put("campusCode", "OLD");
        jdbc.update(
                """
            INSERT INTO restaurant_change_requests(id,restaurant_id,request_type,submitted_by,client_request_id,request_hash,proposed_data,status,created_at,reviewed_by,reviewed_at,reject_reason,approved_data)
            VALUES(?,?,?,?,?,UNHEX(SHA2('fixture',256)),?,?,'2026-11-01 12:00:00.123456',?,?,?,?)
            """,
                id,
                target,
                type,
                owner,
                UUID.randomUUID().toString(),
                json.writeValueAsString(proposed),
                status,
                status.equals("PENDING") ? null : 33,
                status.equals("PENDING")
                        ? null
                        : Timestamp.from(Instant.parse("2026-11-02T12:00:00Z")),
                status.equals("REJECTED") ? "地址需要补充，\n请核对店名" : null,
                status.equals("APPROVED") ? approved : null);
    }

    private void photo(long request, long file, long owner, String purpose, String status) {
        jdbc.update(
                "INSERT INTO media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,purpose,bound_at) VALUES(?,?,'private-test',?,'image/jpeg',4,1,1,UNHEX(SHA2('photo',256)),?,?,CURRENT_TIMESTAMP(6))",
                file,
                owner,
                "restaurant-images/" + file + ".jpg",
                status,
                purpose);
        jdbc.update(
                "INSERT INTO restaurant_change_photos(request_id,position,media_file_id) VALUES(?,0,?)",
                request,
                file);
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
        return System.getenv().getOrDefault("REQUEST_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        return Objects.requireNonNull(
                System.getenv("REQUEST_TEST_DB_PASSWORD"), "Set isolated request test password");
    }

    private static String url(String name) {
        String server =
                System.getenv()
                        .getOrDefault("REQUEST_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback only");
        }
        return server
                + name
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), username(), password());
    }

    private static void drop(String name) throws SQLException {
        if (!name.matches("ginkgo_request_(?:upgrade_)?it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test DB");
        }
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate("DROP DATABASE `" + name + "`");
        }
    }
}
