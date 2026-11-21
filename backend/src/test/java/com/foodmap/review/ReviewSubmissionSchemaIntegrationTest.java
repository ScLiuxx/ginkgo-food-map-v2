package com.foodmap.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** V45真实MySQL结构验证，只创建并删除本次回环随机库；不启动业务或写入开发业务数据。 */
@EnabledIfSystemProperty(named = "review.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewSubmissionSchemaIntegrationTest {
    private final Set<String> createdDatabases = new LinkedHashSet<>();
    private String database;
    private JdbcTemplate db;
    private Map<String, Object> oldReview;
    private Map<String, Object> oldStatistics;
    private Map<String, Object> oldRequest;
    private int migrated;

    /** V49升级V50保留旧数据；新下限同时覆盖正式、待审、撤回与已发布清空规则。 */
    @Test
    void v50AllowsShortBodiesWithoutRewritingRowsOrWeakeningOtherChecks() throws SQLException {
        var name = createDatabase();
        flyway(name, "49").migrate();
        var sql = jdbc(name);
        sql.update("INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,"
                + "ambience_score,recommendation,body,client_request_id,request_hash)"
                + " VALUES(1,101,11,8,8,8,8,'NEUTRAL','原有点评保持不变',UUID_TO_BIN(UUID()),UNHEX(REPEAT('ab',32)))");
        sql.update("INSERT INTO review_submissions(id,restaurant_request_id,origin_request_id,author_user_id,"
                + "taste_score,value_score,service_score,ambience_score,recommendation,body,client_request_id,request_hash,content_hash,publish_request_id)"
                + " VALUES(1,301,301,11,8,8,8,8,'NEUTRAL','待发布点评保持不变',UUID_TO_BIN(UUID()),"
                + "UNHEX(REPEAT('aa',32)),UNHEX(REPEAT('bb',32)),UUID_TO_BIN(UUID()))");
        var oldFormal = sql.queryForMap("SELECT * FROM reviews WHERE id=1");
        var oldPending = sql.queryForMap("SELECT * FROM review_submissions WHERE id=1");
        invalid(() -> sql.update("UPDATE reviews SET body='好' WHERE id=1"), 3819);
        invalid(() -> sql.update("UPDATE review_submissions SET body='好' WHERE id=1"), 3819);
        assertThat(flyway(name, "50").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(sql.queryForMap("SELECT * FROM reviews WHERE id=1")).usingRecursiveComparison().isEqualTo(oldFormal);
        assertThat(sql.queryForMap("SELECT * FROM review_submissions WHERE id=1")).usingRecursiveComparison().isEqualTo(oldPending);
        assertThat(flyway(name, "50").migrate().migrationsExecuted).isZero();
        for (String table : List.of("reviews", "review_submissions")) {
            for (String body : List.of("好", "不错", "很好吃", "味道不错", "🍜", "🍜".repeat(500))) {
                sql.update("UPDATE " + table + " SET body=? WHERE id=1", body);
                assertThat(sql.queryForObject("SELECT body FROM " + table + " WHERE id=1", String.class)).isEqualTo(body);
            }
            for (String assignment : List.of("body=''", "body=NULL", "body=' '", "body=' 好'", "body='好 '", "taste_score=NULL")) {
                invalid(() -> sql.update("UPDATE " + table + " SET " + assignment + " WHERE id=1"), 3819, 1048);
            }
            invalid(() -> sql.update("UPDATE " + table + " SET body=? WHERE id=1", "🍜".repeat(501)), 1406, 3819);
        }
        sql.update("UPDATE review_submissions SET body='好',status='WITHDRAWN',withdrawn_at=CURRENT_TIMESTAMP(6) WHERE id=1");
        invalid(() -> sql.update("UPDATE review_submissions SET body='' WHERE id=1"), 3819);
        invalid(() -> sql.update("UPDATE review_submissions SET status='PUBLISHED',withdrawn_at=NULL,"
                + "published_at=CURRENT_TIMESTAMP(6),published_review_id=1 WHERE id=1"), 3819);
        sql.update("UPDATE review_submissions SET status='PUBLISHED',taste_score=NULL,value_score=NULL,"
                + "service_score=NULL,ambience_score=NULL,recommendation=NULL,body=NULL,withdrawn_at=NULL,"
                + "published_at=CURRENT_TIMESTAMP(6),published_review_id=1 WHERE id=1");
    }

    /** 在V44库保存真实旧结构夹具，再升级；同时验证新的迁移没有改写二态态度或统计。 */
    @BeforeAll
    void initialize() throws SQLException {
        database = createDatabase();
        db = jdbc(database);
        flyway(database, "44").migrate();
        db.update(
                "INSERT INTO restaurants(id,name,category_code,price_kind,business_status)"
                        + " VALUES(101,'旧结构保留店','OTHER','UNKNOWN','OPERATING')");
        db.update(
                "INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,"
                    + "service_score,ambience_score,is_recommended,body,client_request_id,request_hash)"
                    + " VALUES(201,101,11,8,8,8,8,FALSE,'原有不推荐点评正文',UUID_TO_BIN(?),UNHEX(REPEAT('ab',32)))",
                UUID.randomUUID().toString());
        db.update(
                "INSERT INTO"
                    + " restaurant_review_statistics(restaurant_id,review_count,participant_count,"
                    + "recommend_count,taste_sum,value_sum,service_sum,ambience_sum,overall_score_sum)"
                    + " VALUES(101,1,1,0,8,8,8,8,8)");
        db.update(
                "INSERT INTO"
                    + " restaurant_change_requests(id,request_type,submitted_by,client_request_id,request_hash,proposed_data)"
                    + " VALUES(301,'CREATE',11,?,UNHEX(REPEAT('cd',32)),JSON_OBJECT('name','原申请'))",
                UUID.randomUUID().toString());
        oldReview = db.queryForMap("SELECT * FROM reviews WHERE id=201");
        oldStatistics =
                db.queryForMap(
                        "SELECT * FROM restaurant_review_statistics WHERE restaurant_id=101");
        oldRequest = db.queryForMap("SELECT * FROM restaurant_change_requests WHERE id=301");
        migrated = flyway(database, "45").migrate().migrationsExecuted;
    }

    @BeforeEach
    void seedSubmission() {
        db.update("DELETE FROM review_submission_photos");
        db.update("DELETE FROM review_submissions");
        insert(1, 301, 11, "NEUTRAL");
    }

    @AfterEach
    void clearSubmission() {
        db.update("DELETE FROM review_submission_photos");
        db.update("DELETE FROM review_submissions");
    }

    /** 名称必须匹配并位于本次创建集合；禁止Flyway clean或删除现有业务库。 */
    @AfterAll
    void cleanup() throws SQLException {
        for (String name : createdDatabases) {
            if (!name.matches("foodmap_review_submission_it_[a-f0-9]{32}")) {
                throw new IllegalStateException("Unexpected test database name");
            }
            try (var connection = DriverManager.getConnection(url(""), username(), password());
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + name + "`");
            }
        }
    }

    @Test
    void upgradePreservesExistingContentAndIsRepeatable() {
        assertThat(migrated).isEqualTo(1);
        // UUID和摘要是byte[]，必须按数组内容比较，不能比较两次查询返回的数组对象身份。
        assertThat(db.queryForMap("SELECT * FROM reviews WHERE id=201"))
                .usingRecursiveComparison()
                .isEqualTo(oldReview);
        assertThat(
                        db.queryForMap(
                                "SELECT * FROM restaurant_review_statistics WHERE"
                                        + " restaurant_id=101"))
                .usingRecursiveComparison()
                .isEqualTo(oldStatistics);
        assertThat(db.queryForMap("SELECT * FROM restaurant_change_requests WHERE id=301"))
                .usingRecursiveComparison()
                .isEqualTo(oldRequest);
        assertThat(flyway(database, "45").migrate().migrationsExecuted).isZero();
        flyway(database, "45").validate();
    }

    @Test
    void freshMigrationCreatesEmptyTablesWithoutPublishingReviews() throws SQLException {
        var fresh = createDatabase();
        assertThat(flyway(fresh, "45").migrate().migrationsExecuted).isEqualTo(45);
        var empty = jdbc(fresh);
        for (String table :
                List.of(
                        "review_submissions",
                        "review_submission_photos",
                        "reviews",
                        "restaurant_change_requests",
                        "restaurants")) {
            assertThat(empty.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isZero();
        }
        assertThat(flyway(fresh, "45").migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECOMMENDED", "NEUTRAL", "NOT_RECOMMENDED"})
    void acceptsAllThreeExplicitAttitudes(String attitude) {
        db.update("UPDATE review_submissions SET recommendation=? WHERE id=1", attitude);
        assertThat(
                        db.queryForObject(
                                "SELECT recommendation FROM review_submissions WHERE id=1",
                                String.class))
                .isEqualTo(attitude);
    }

    /** 空值、越界和状态缺字段不能利用CHECK的UNKNOWN语义绕过结构校验。 */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "recommendation=NULL",
                "recommendation=''",
                "recommendation='UNKNOWN'",
                "recommendation='neutral'",
                "recommendation='NEUTRAL '",
                "taste_score=NULL",
                "taste_score=-0.5",
                "taste_score=10.5",
                "taste_score=7.1",
                "body=NULL",
                "body='四个字啊'",
                "body=CONCAT(' ', '正文五个字')",
                "body=CONCAT('正文五个字', CHAR(9))",
                "status='PUBLISHED'",
                "status='WITHDRAWN'",
                "status='WAITING '",
                "published_review_id=901",
                "published_at=CURRENT_TIMESTAMP(6)",
                "withdrawn_at=CURRENT_TIMESTAMP(6)",
                "restaurant_request_id=0",
                "origin_request_id=0",
                "author_user_id=9223372036854775808",
                "request_hash=NULL",
                "content_hash=NULL"
            })
    void rejectsInvalidWaitingContentAndPartialResult(String assignment) {
        invalid(
                () -> db.update("UPDATE review_submissions SET " + assignment + " WHERE id=1"),
                3819,
                1048,
                1264);
    }

    @Test
    void bodyUsesUnicodeCodePointsAndWaitingAcceptsZeroScores() {
        db.update(
                "UPDATE review_submissions SET body=?,taste_score=0,value_score=0,"
                        + "service_score=0,ambience_score=0 WHERE id=1",
                "🍜".repeat(500));
        assertThat(
                        db.queryForObject(
                                "SELECT CHAR_LENGTH(body) FROM review_submissions WHERE id=1",
                                Integer.class))
                .isEqualTo(500);
        invalid(
                () ->
                        db.update(
                                "UPDATE review_submissions SET body=? WHERE id=1",
                                "🍜".repeat(501)),
                1406);
    }

    @Test
    void enforcesRequestAuthorClientAndPublicationUniqueness() {
        invalid(() -> insert(2, 301, 22, "RECOMMENDED"), 1062);
        insert(2, 302, 11, "RECOMMENDED");
        var client =
                db.queryForObject(
                        "SELECT client_request_id FROM review_submissions WHERE id=1",
                        byte[].class);
        invalid(
                () ->
                        db.update(
                                "UPDATE review_submissions SET client_request_id=? WHERE id=2",
                                client),
                1062);
        var publication =
                db.queryForObject(
                        "SELECT publish_request_id FROM review_submissions WHERE id=1",
                        byte[].class);
        invalid(
                () ->
                        db.update(
                                "UPDATE review_submissions SET publish_request_id=? WHERE id=2",
                                publication),
                1062);
        publish(1, 901);
        invalid(() -> publish(2, 901), 1062);
    }

    @Test
    void publicationRequiresContentTransferAndReceiptSurvivesFormalReviewDeletion() {
        db.update(
                "INSERT INTO"
                    + " reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash)"
                    + " VALUES"
                    + " (202,101,11,8,8,8,8,TRUE,'可删除的正式点评正文',UUID_TO_BIN(?),UNHEX(REPEAT('ef',32)))",
                UUID.randomUUID().toString());
        invalid(
                () ->
                        db.update(
                                "UPDATE review_submissions SET"
                                    + " status='PUBLISHED',published_review_id=202,published_at=CURRENT_TIMESTAMP(6)"
                                    + " WHERE id=1"),
                3819);
        publish(1, 202);
        assertThat(
                        db.queryForMap(
                                "SELECT body,recommendation,taste_score FROM review_submissions"
                                        + " WHERE id=1"))
                .containsEntry("body", null)
                .containsEntry("recommendation", null)
                .containsEntry("taste_score", null);
        invalid(
                () ->
                        db.update(
                                "UPDATE review_submissions SET published_review_id=NULL WHERE"
                                        + " id=1"),
                3819);
        db.update("DELETE FROM reviews WHERE id=202");
        assertThat(
                        db.queryForObject(
                                "SELECT published_review_id FROM review_submissions WHERE id=1",
                                Long.class))
                .isEqualTo(202);
    }

    @Test
    void withdrawalRetainsContentAndResubmissionRetainsOrigin() {
        db.update(
                "UPDATE review_submissions SET status='WITHDRAWN',withdrawn_at=CURRENT_TIMESTAMP(6)"
                        + " WHERE id=1");
        assertThat(
                        db.queryForObject(
                                "SELECT body FROM review_submissions WHERE id=1", String.class))
                .isEqualTo("待发布点评测试正文");
        invalid(
                () -> db.update("UPDATE review_submissions SET withdrawn_at=NULL WHERE id=1"),
                3819);
        db.update(
                "UPDATE review_submissions SET"
                    + " status='WAITING',withdrawn_at=NULL,restaurant_request_id=302 WHERE id=1");
        assertThat(
                        db.queryForObject(
                                "SELECT origin_request_id FROM review_submissions WHERE id=1",
                                Long.class))
                .isEqualTo(301);
    }

    @Test
    void photosHaveFiveOrderedPositionsExclusiveFilesAndRestrictedParentDeletion() {
        for (int position = 0; position < 5; position++) {
            db.update(
                    "INSERT INTO review_submission_photos VALUES(1,?,?,CURRENT_TIMESTAMP(6))",
                    position,
                    1000 + position);
        }
        assertThat(
                        db.queryForList(
                                "SELECT media_file_id FROM review_submission_photos WHERE"
                                        + " submission_id=1 ORDER BY position",
                                Long.class))
                .containsExactly(1000L, 1001L, 1002L, 1003L, 1004L);
        invalid(
                () ->
                        db.update(
                                "INSERT INTO review_submission_photos"
                                        + " VALUES(1,5,1005,CURRENT_TIMESTAMP(6))"),
                3819);
        invalid(
                () ->
                        db.update(
                                "INSERT INTO review_submission_photos"
                                        + " VALUES(1,0,1005,CURRENT_TIMESTAMP(6))"),
                1062);
        invalid(() -> db.update("DELETE FROM review_submissions WHERE id=1"), 1451);
        insert(2, 302, 11, "NEUTRAL");
        invalid(
                () ->
                        db.update(
                                "INSERT INTO review_submission_photos"
                                        + " VALUES(2,0,1000,CURRENT_TIMESTAMP(6))"),
                1062);
        invalid(
                () ->
                        db.update(
                                "INSERT INTO review_submission_photos"
                                        + " VALUES(999,0,1005,CURRENT_TIMESTAMP(6))"),
                1452);
        invalid(
                () ->
                        db.update(
                                "INSERT INTO review_submission_photos"
                                        + " VALUES(2,0,0,CURRENT_TIMESTAMP(6))"),
                3819);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.referential_constraints"
                                        + " WHERE constraint_schema=DATABASE() AND table_name IN"
                                        + " ('review_submissions','review_submission_photos')",
                                Integer.class))
                .isEqualTo(1);
    }

    /** 这里只模拟合法写入形状；本阶段没有提交、审核联动或自动发布服务。 */
    private void insert(long id, long request, long author, String recommendation) {
        db.update(
                "INSERT INTO"
                    + " review_submissions(id,restaurant_request_id,origin_request_id,author_user_id,"
                    + "taste_score,value_score,service_score,ambience_score,recommendation,body,client_request_id,request_hash,content_hash,publish_request_id)"
                    + " VALUES(?,?,301,?,8,8,8,8,?,"
                    + "'待发布点评测试正文',UUID_TO_BIN(?),UNHEX(REPEAT('aa',32)),UNHEX(REPEAT('bb',32)),UUID_TO_BIN(?))",
                id,
                request,
                author,
                recommendation,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
    }

    private void publish(long submission, long review) {
        db.update(
                "UPDATE review_submissions SET"
                    + " status='PUBLISHED',taste_score=NULL,value_score=NULL,"
                    + "service_score=NULL,ambience_score=NULL,recommendation=NULL,body=NULL,published_review_id=?,published_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE id=?",
                review,
                submission);
    }

    /** 断言实际MySQL错误码，避免把语法错误或连接失败误认成约束生效。 */
    private void invalid(Runnable action, Integer... codes) {
        var exception = assertThrows(DataAccessException.class, action::run);
        assertThat(exception.getMostSpecificCause()).isInstanceOf(SQLException.class);
        assertThat(((SQLException) exception.getMostSpecificCause()).getErrorCode()).isIn(codes);
    }

    private String createDatabase() throws SQLException {
        String name =
                "foodmap_review_submission_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), username(), password());
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + name
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            createdDatabases.add(name);
        }
        return name;
    }

    private Flyway flyway(String name, String target) {
        return Flyway.configure()
                .dataSource(url(name), username(), password())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private JdbcTemplate jdbc(String name) {
        return new JdbcTemplate(new DriverManagerDataSource(url(name), username(), password()));
    }

    private String url(String name) {
        var server =
                System.getenv()
                        .getOrDefault("REVIEW_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Schema tests require a loopback MySQL server");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private String username() {
        return System.getenv().getOrDefault("REVIEW_TEST_DB_USERNAME", "root");
    }

    private String password() {
        return Objects.requireNonNull(
                System.getenv("REVIEW_TEST_DB_PASSWORD"), "Set isolated test password");
    }
}
