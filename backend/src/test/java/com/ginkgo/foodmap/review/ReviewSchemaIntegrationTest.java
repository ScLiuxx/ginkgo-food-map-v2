package com.ginkgo.foodmap.review;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 实际执行V4验证MySQL语义；仅创建并清理本次随机回环测试库，不读取业务配置，不调用OSS。 */
@EnabledIfSystemProperty(named = "review.integration", matches = "true")
class ReviewSchemaIntegrationTest {
    private static final String SERVER = System.getenv().getOrDefault("REVIEW_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
    private static final String USERNAME = System.getenv().getOrDefault("REVIEW_TEST_DB_USERNAME", "root");
    private static final Set<String> CREATED_DATABASES = new LinkedHashSet<>();
    private static String database;
    private Connection connection;

    /** 默认不开启；开启却缺少连接信息时失败，不能把跳过当通过。 */
    @BeforeAll static void prepare() throws SQLException {
        if (!SERVER.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"))
            throw new IllegalArgumentException("Review schema tests require an explicit loopback MySQL server");
        password(); database = createDatabase(); migration(database, null).migrate();
    }
    @BeforeEach void begin() throws SQLException { connection = connect(database); connection.setAutoCommit(false); }
    @AfterEach void rollback() throws SQLException {
        if (connection != null) { try { connection.rollback(); } finally { connection.close(); } }
    }
    /** 严格验证名称并只删除本次成功创建的测试库，不使用Flyway clean。 */
    @AfterAll static void cleanup() throws SQLException {
        for (String name : CREATED_DATABASES) {
            if (!name.matches("ginkgo_review_it_[a-f0-9]{32}")) throw new IllegalStateException("Unexpected test database");
            try (Connection admin=connect(""); Statement st=admin.createStatement()) { st.executeUpdate("DROP DATABASE `"+name+"`"); }
        }
    }

    @Test void freshAndRepeatedMigrationWithoutInventedContent() throws SQLException {
        assertThat(migration(database,null).info().current().getVersion().getVersion()).isEqualTo("11");
        assertThat(migration(database,null).migrate().migrationsExecuted).isZero();
        migration(database,null).validate();
        for (String table : List.of("reviews","review_replies","review_photos","media_files","restaurant_review_statistics"))
            assertThat(number(connection,"SELECT COUNT(*) FROM "+table)).isZero();
        assertThat(number(connection,"SELECT COUNT(*) FROM restaurant_categories")).isEqualTo(13);
        assertThat(number(connection,"SELECT COUNT(*) FROM users")).isZero();
        assertThat(number(connection,"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('reviews','review_replies','media_files') AND column_name IN ('source_type','display_nickname','row_version','deleted_at','deleted_by','delete_reason_code','purge_requested_at','purged_at')")).isZero();
        assertThat(number(connection,"SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name IN ('reviews','review_replies','review_photos','media_files','restaurant_review_statistics')")).isEqualTo(3);
        assertThat(number(connection,"SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name IN ('reviews','review_replies','review_photos','media_files') AND referenced_table_name IN ('users','restaurants','media_files')")).isZero();
    }

    /** V3已有正常及删除餐厅全部补零；认证、标签、归属与餐厅版本遗留列保持原值。 */
    @Test void upgradeVersionThreePreservesDataAndBackfillsZeroStatistics() throws SQLException {
        String db=createDatabase(); migration(db,"3").migrate();
        try(Connection c=connect(db)) {
            update(c,"INSERT INTO users(username,email,password_hash,credential_version) VALUES ('schema_user','schema@example.invalid','test-only',9)");
            update(c,"INSERT INTO restaurants(name,category_code,source_type,row_version) VALUES ('原店','OTHER','LEGACY_IMPORT',6)");
            update(c,"INSERT INTO restaurants(name,category_code,source_type,deleted_at,delete_reason_code) VALUES ('旧删除店','OTHER','LEGACY_IMPORT',CURRENT_TIMESTAMP(6),'SYSTEM')");
            update(c,"INSERT INTO restaurant_tags(restaurant_id,tag,position) SELECT id,'保留',0 FROM restaurants");
            assertThat(migration(db,null).migrate().migrationsExecuted).isEqualTo(7);
            assertThat(number(c,"SELECT COUNT(*) FROM restaurant_review_statistics WHERE review_count=0 AND recommend_count=0 AND taste_sum=0 AND overall_score_sum=0")).isEqualTo(2);
            assertThat(number(c,"SELECT credential_version FROM users")).isEqualTo(9);
            assertThat(number(c,"SELECT row_version FROM restaurants WHERE name='原店'")).isEqualTo(6);
            assertThat(number(c,"SELECT COUNT(*) FROM restaurant_tags")).isEqualTo(2);
            assertThat(number(c,"SELECT COUNT(*) FROM reviews")).isZero();
            assertThat(migration(db,null).migrate().migrationsExecuted).isZero();
        }
    }

    /** 在有历史、同时间并列和缺失统计行的V4库上验证V5重建，不依赖空库假设。 */
    @Test void upgradeV4SelectsLatestAuthorAndPreservesHistory() throws SQLException {
        String db=createDatabase(); migration(db,"4").migrate();
        try (Connection c=connect(db)) {
            update(c,"INSERT INTO restaurants(id,name,category_code,source_type) VALUES (1,'升级店','OTHER','LEGACY_IMPORT'),(2,'空店','OTHER','LEGACY_IMPORT')");
            update(c,"UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=1");
            for (int i=0;i<3;i++) {
                int score=i==0?8:i==1?6:9;
                update(c,"INSERT INTO reviews(restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash,created_at) VALUES (1,?,?,?,?,?,?, '真实升级测试正文',UUID_TO_BIN(?),UNHEX(REPEAT('11',32)),'2026-10-29 00:00:00')",i==2?2:1,score,score,score,score,i==1?0:1,UUID.randomUUID().toString());
            }
            assertThat(migration(db,null).migrate().migrationsExecuted).isEqualTo(6);
            assertThat(number(c,"SELECT COUNT(*) FROM reviews")).isEqualTo(3);
            assertThat(strings(c,"SELECT CONCAT(review_count,':',participant_count,':',recommend_count,':',overall_score_sum) FROM restaurant_review_statistics WHERE restaurant_id=1")).containsExactly("3:2:1:15.00");
            assertThat(number(c,"SELECT participant_count FROM restaurant_review_statistics WHERE restaurant_id=2")).isZero();
            assertThat(number(c,"SELECT COUNT(*) FROM restaurants WHERE deleted_at IS NOT NULL")).isEqualTo(1);
            assertThat(number(c,"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name='idx_reviews_restaurant_author_time'")).isEqualTo(4);
            assertThat(migration(db,null).migrate().migrationsExecuted).isZero();
        }
    }

    /** V5媒体数据在V6原样保留，新增上传状态仅对后续写入开放。 */
    @Test void upgradeV5PreservesExistingMediaAndAllowsUploading() throws SQLException {
        String db=createDatabase();migration(db,"5").migrate();
        try(var c=connect(db)) {
            update(c,mediaSql());
            assertThat(migration(db,"6").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(strings(c,"SELECT status FROM media_files")).containsExactly("TEMPORARY");
            update(c,"UPDATE media_files SET status='UPLOADING'");
            assertThat(strings(c,"SELECT status FROM media_files")).containsExactly("UPLOADING");
            assertThat(migration(db,"6").migrate().migrationsExecuted).isZero();
        }
    }

    /** V7保留V6已存在的两张关联，扩展后的第三至第五位置可写，第六位置仍被拒绝。 */
    @Test void upgradeV6PreservesPhotosAndExtendsLimitToFive() throws SQLException {
        String db=createDatabase();migration(db,"6").migrate();
        try(var c=connect(db)) {
            update(c,reviewSql(),1,UUID.randomUUID().toString());
            long id=number(c,"SELECT id FROM reviews");
            update(c,"INSERT INTO review_photos(review_id,position,media_file_id) VALUES (?,0,100),(?,1,101)",id,id);
            assertThat(migration(db,null).migrate().migrationsExecuted).isEqualTo(4);
            assertThat(strings(c,"SELECT CONCAT(position,':',media_file_id) FROM review_photos ORDER BY position")).containsExactly("0:100","1:101");
            update(c,"INSERT INTO review_photos(review_id,position,media_file_id) VALUES (?,2,102),(?,3,103),(?,4,104)",id,id,id);
            assertThat(number(c,"SELECT COUNT(*) FROM review_photos")).isEqualTo(5);
            assertThrows(SQLException.class,()->update(c,"INSERT INTO review_photos(review_id,position,media_file_id) VALUES (?,5,105)",id));
            assertThat(migration(db,null).migrate().migrationsExecuted).isZero();
        }
    }

    /** V7存量记录回填回执，硬删内容不删除防重材料，重复迁移也不重复创建。 */
    @Test void upgradeV7BackfillsDurableRequestReceipts() throws SQLException {
        String db=createDatabase();migration(db,"7").migrate();
        String uuid=UUID.randomUUID().toString();
        try(var c=connect(db)) {
            update(c,reviewSql(),1,uuid);
            long id=number(c,"SELECT id FROM reviews");
            assertThat(migration(db,null).migrate().migrationsExecuted).isEqualTo(3);
            assertThat(strings(c,"SELECT BIN_TO_UUID(client_request_id) FROM review_request_receipts WHERE author_user_id=1 AND review_id=?",id)).containsExactly(uuid);
            update(c,"UPDATE review_request_receipts SET deleted_at=CURRENT_TIMESTAMP(6) WHERE review_id=?",id);
            update(c,"DELETE FROM reviews WHERE id=?",id);
            assertThat(number(c,"SELECT COUNT(*) FROM review_request_receipts WHERE deleted_at IS NOT NULL")).isEqualTo(1);
            assertThat(migration(db,null).migrate().migrationsExecuted).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings={"0","0.5","7","9.5","10"})
    void scoresAllowEndpointsAndHalfSteps(String score) throws SQLException {
        long id=review(1,UUID.randomUUID());
        update(connection,"UPDATE reviews SET taste_score=?,value_score=?,service_score=?,ambience_score=? WHERE id=?",score,score,score,score,id);
        assertThat(strings(connection,"SELECT overall_score FROM reviews WHERE id=?",id)).containsExactly(new BigDecimal(score).setScale(2).toPlainString());
    }
    @ParameterizedTest @ValueSource(strings={"-0.5","10.5","7.1","7.4"})
    void rejectsInvalidStoredScores(String value) throws SQLException {
        long id=review(1,UUID.randomUUID());
        for(String column:List.of("taste_score","value_score","service_score","ambience_score"))
            reject(3819,"UPDATE reviews SET "+column+"=? WHERE id=?",value,id);
    }
    @Test void generatedScoreIsRoundedAndCannotBeWritten() throws SQLException {
        long id=review(1,UUID.randomUUID());
        update(connection,"UPDATE reviews SET taste_score=8,value_score=8,service_score=8,ambience_score=7.5 WHERE id=?",id);
        assertThat(strings(connection,"SELECT overall_score FROM reviews WHERE id=?",id)).containsExactly("7.88");
        reject(3105,"UPDATE reviews SET overall_score=10 WHERE id=?",id);
    }
    @Test void authorRequestDeduplicatesButMultipleExperiencesRemainAllowed() throws SQLException {
        UUID request=UUID.randomUUID(); review(1,request);
        reject(1062,reviewSql(),1,request.toString());
        review(1,UUID.randomUUID()); review(2,request);
        assertThat(number(connection,"SELECT COUNT(*) FROM reviews")).isEqualTo(3);
    }
    @Test void requiresIdentityRequestAndRecommendation() throws SQLException {
        long id=review(1,UUID.randomUUID());
        reject(1048,"UPDATE reviews SET author_user_id=NULL WHERE id=?",id);
        reject(3819,"UPDATE reviews SET author_user_id=0 WHERE id=?",id);
        reject(3819,"UPDATE reviews SET restaurant_id=0 WHERE id=?",id);
        reject(1048,"UPDATE reviews SET client_request_id=NULL WHERE id=?",id);
        reject(1048,"UPDATE reviews SET request_hash=NULL WHERE id=?",id);
        reject(3819,"UPDATE reviews SET is_recommended=2 WHERE id=?",id);
    }
    @ParameterizedTest @ValueSource(strings={"","1234"," 12345","12345 ","\t12345","12345\n","　12345"})
    void rejectsShortOrUntrimmedReviewBody(String body) throws SQLException {
        long id=review(1,UUID.randomUUID()); reject(3819,"UPDATE reviews SET body=? WHERE id=?",body,id);
    }
    @Test void unicodeLimitsCountCharactersNotBytes() throws SQLException {
        long id=review(1,UUID.randomUUID());
        update(connection,"UPDATE reviews SET body=? WHERE id=?","🥟".repeat(500),id);
        assertThat(number(connection,"SELECT CHAR_LENGTH(body) FROM reviews WHERE id=?",id)).isEqualTo(500);
        reject(1406,"UPDATE reviews SET body=? WHERE id=?","🥟".repeat(501),id);
        reply(id,"🥟".repeat(200));
        reject(1406,replySql(),id,"🥟".repeat(201));
    }
    @Test void photosHaveTwoOrderedSlotsAndExclusiveFiles() throws SQLException {
        long a=review(1,UUID.randomUUID()), b=review(1,UUID.randomUUID());
        update(connection,"INSERT INTO review_photos(review_id,position,media_file_id) VALUES (?,0,100),(?,1,101)",a,a);
        update(connection,"INSERT INTO review_photos(review_id,position,media_file_id) VALUES (?,2,102),(?,3,103),(?,4,104)",a,a,a);
        reject(3819,"INSERT INTO review_photos(review_id,position,media_file_id) VALUES (?,5,105)",a);
        reject(1062,"INSERT INTO review_photos(review_id,position,media_file_id) VALUES (?,0,100)",b);
        reject(1452,"INSERT INTO review_photos(review_id,position,media_file_id) VALUES (9223372036854775807,0,102)");
    }
    @Test void repliesAreSingleLevelAndDeduplicated() throws SQLException {
        long id=review(1,UUID.randomUUID());reply(id,"回复");
        reject(1062,replySql(),id,"再次回复");
        reject(3819,"UPDATE review_replies SET body=''");
        reject(1048,"UPDATE review_replies SET author_user_id=NULL");
        reject(1452,"UPDATE review_replies SET review_id=9223372036854775807");
        assertThat(number(connection,"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='review_replies' AND column_name='parent_reply_id'")).isZero();
    }
    @Test void parentDeleteRequiresExplicitChildrenAndRollbackIsAtomic() throws SQLException {
        long id=review(1,UUID.randomUUID());reply(id,"回复");
        update(connection,"INSERT INTO review_photos(review_id,position,media_file_id) VALUES (?,0,10)",id);
        reject(1451,"DELETE FROM reviews WHERE id=?",id);
        Savepoint point=connection.setSavepoint();
        update(connection,"DELETE FROM review_replies WHERE review_id=?",id);
        update(connection,"DELETE FROM review_photos WHERE review_id=?",id);
        update(connection,"DELETE FROM reviews WHERE id=?",id);
        assertThat(number(connection,"SELECT COUNT(*) FROM reviews")).isZero();
        connection.rollback(point);
        assertThat(number(connection,"SELECT COUNT(*) FROM reviews")).isEqualTo(1);
        assertThat(number(connection,"SELECT COUNT(*) FROM review_replies")).isEqualTo(1);
        assertThat(number(connection,"SELECT COUNT(*) FROM review_photos")).isEqualTo(1);
    }

    @Test void mediaLifecycleAndObjectUniqueness() throws SQLException {
        media();
        reject(1062,mediaSql());
        reject(3819,"UPDATE media_files SET status='BOUND'");
        update(connection,"UPDATE media_files SET status='BOUND',expires_at=NULL,bound_at=CURRENT_TIMESTAMP(6)");
        reject(3819,"UPDATE media_files SET expires_at=CURRENT_TIMESTAMP(6)");
        reject(3819,"UPDATE media_files SET status='PURGED'");
        update(connection,"UPDATE media_files SET status='PENDING_DELETE'");
        update(connection,"DELETE FROM media_files");
        assertThat(number(connection,"SELECT COUNT(*) FROM media_files")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"byte_size=0","byte_size=307201","width=0","height=1201","content_type='text/plain'","content_type='image/jpeg '","uploader_user_id=0","bucket_name='Invalid'","storage_key='https://example.invalid/a'","status='temporary'","status='TEMPORARY '"})
    void rejectsInvalidMediaMetadata(String assignment) throws SQLException {
        media();reject(3819,"UPDATE media_files SET "+assignment);
    }
    @Test void statisticsBoundsAndOwnModuleForeignKey() throws SQLException {
        update(connection,"INSERT INTO restaurants(name,category_code,source_type) VALUES ('统计店','OTHER','LEGACY_IMPORT')");
        long id=number(connection,"SELECT id FROM restaurants");
        update(connection,"INSERT INTO restaurant_review_statistics(restaurant_id) VALUES (?)",id);
        for(String assignment:List.of("recommend_count=1","taste_sum=0.5","overall_score_sum=0.01"))
            reject(3819,"UPDATE restaurant_review_statistics SET "+assignment);
        update(connection,"UPDATE restaurant_review_statistics SET review_count=1,participant_count=1,recommend_count=1,taste_sum=8,value_sum=8,service_sum=8,ambience_sum=7.5,overall_score_sum=7.88");
        reject(3819,"UPDATE restaurant_review_statistics SET overall_score_sum=10.01");
        reject(1451,"DELETE FROM restaurants WHERE id=?",id);
        reject(1452,"INSERT INTO restaurant_review_statistics(restaurant_id) VALUES (9223372036854775807)");
    }

    /** 逻辑ID无需创建虚构账号或餐厅；服务端权限与跨模块绑定不属于本DDL测试的完成范围。 */
    private long review(long author,UUID request) throws SQLException {
        update(connection,reviewSql(),author,request.toString());return number(connection,"SELECT LAST_INSERT_ID()");
    }
    private String reviewSql() {
        return "INSERT INTO reviews(restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash) VALUES (1,?,7,7,7,7,1,'测试评价正文',UUID_TO_BIN(?),UNHEX(SHA2('test-only',256)))";
    }
    private void reply(long id,String body) throws SQLException {update(connection,replySql(),id,body);}
    private String replySql() {
        return "INSERT INTO review_replies(review_id,author_user_id,body,client_request_id,request_hash) VALUES (?,1,?,UNHEX(REPEAT('11',16)),UNHEX(REPEAT('22',32)))";
    }
    private void media() throws SQLException {update(connection,mediaSql());}
    private String mediaSql() {
        return "INSERT INTO media_files(uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,expires_at) VALUES (1,'schema-test','reviews/test.jpg','image/jpeg',307200,1200,800,UNHEX(REPEAT('22',32)),'TEMPORARY',CURRENT_TIMESTAMP(6)+INTERVAL 1 DAY)";
    }
    /** 断线、SQL拼错不能冒充约束拒绝，检查具体MySQL错误码。 */
    private void reject(int code,String sql,Object... args) {
        SQLException error=assertThrows(SQLException.class,()->update(connection,sql,args));assertThat(error.getErrorCode()).isEqualTo(code);
    }
    private static int update(Connection target, String sql, Object... arguments) throws SQLException {
        try (var statement = target.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement.executeUpdate();
        }
    }

    private static long number(Connection target, String sql, Object... arguments) throws SQLException {
        try (var statement = target.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static List<String> strings(Connection target, String sql, Object... arguments) throws SQLException {
        try (var statement = target.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            try (var rows = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) values.add(rows.getString(1));
                return values;
            }
        }
    }

    private static String createDatabase() throws SQLException {
        String name = "ginkgo_review_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect(""); Statement statement = admin.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        CREATED_DATABASES.add(name);
        return name;
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), USERNAME, password());
    }

    /** 参数仅用于测试临时实例，连接会话显式使用UTC和严格模式，不读取应用本地配置。 */
    private static String url(String name) {
        return SERVER + name + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
                + "&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&sessionVariables=sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'";
    }

    private static String password() {
        String value = System.getenv("REVIEW_TEST_DB_PASSWORD");
        if (value == null) throw new IllegalStateException("Set REVIEW_TEST_DB_PASSWORD for the isolated test MySQL server");
        return value;
    }

    private static Flyway migration(String name, String target) {
        var config = Flyway.configure().dataSource(url(name), USERNAME, password())
                .locations("classpath:db/migration").cleanDisabled(true);
        if (target != null) config.target(MigrationVersion.fromVersion(target));
        return config.load();
    }
}
