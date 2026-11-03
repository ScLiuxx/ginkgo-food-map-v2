package com.ginkgo.foodmap.restaurant;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V9/V10真实MySQL结构与升级测试。只连接显式回环测试实例，临时库按次随机生成并清理。
 * 不读取业务配置、不执行应用写接口、不把DDL通过等同于窗口发布/权限/审核已经接通。
 */
@EnabledIfSystemProperty(named = "campus.integration", matches = "true")
class CampusDiningSchemaIntegrationTest {
    private static final String SERVER = System.getenv().getOrDefault("CAMPUS_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3341/");
    private static final String USER = System.getenv().getOrDefault("CAMPUS_TEST_DB_USERNAME", "root");
    private static final Set<String> CREATED = new LinkedHashSet<>();
    private static String database;
    private Connection connection;

    @BeforeAll static void prepare() throws SQLException {
        if (!SERVER.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"))
            throw new IllegalArgumentException("Campus schema tests require an explicit loopback server");
        password();
        database = createDatabase();
        migration(database, null).migrate();
    }

    @BeforeEach void begin() throws SQLException {
        connection = connect(database);
        connection.setAutoCommit(false);
    }

    @AfterEach void rollback() throws SQLException {
        if (connection != null) { try { connection.rollback(); } finally { connection.close(); } }
    }

    /** 只删除本次实际创建且名称匹配的测试库，绝不调用Flyway clean。 */
    @AfterAll static void cleanup() throws SQLException {
        for (String name : CREATED) {
            if (!name.matches("ginkgo_campus_it_[a-f0-9]{32}")) throw new IllegalStateException("Unexpected test database");
            try (var c = connect(""); var st = c.createStatement()) { st.executeUpdate("DROP DATABASE `" + name + "`"); }
        }
    }

    @Test void freshAndRepeatedMigrationCreatesNoBusinessOrDishData() throws SQLException {
        assertThat(migration(database, null).info().current().getVersion().getVersion()).isEqualTo("11");
        migration(database, null).validate();
        assertThat(migration(database, null).migrate().migrationsExecuted).isZero();
        for (String table : List.of("restaurants", "canteen_windows", "reviews", "review_request_receipts", "window_review_statistics"))
            assertThat(number(connection, "SELECT COUNT(*) FROM " + table)).isZero();
        assertThat(number(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE '%dish%'")).isZero();
        assertThat(number(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='canteen_windows' AND column_name IN ('supports_dine_in','supports_delivery')")).isZero();
        assertThat(number(connection, "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name IN ('idx_reviews_window_time','idx_reviews_window_author_time')")).isEqualTo(7);
        // review仍只保存跨模块逻辑ID；同模块窗口→食堂和统计→窗口才使用物理外键。
        assertThat(number(connection, "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name IN ('reviews','review_request_receipts') AND referenced_table_name IS NOT NULL")).isZero();
    }

    /** 带旧食堂点评、普通点评、照片、回复及删除回执的V8升级，逐项核对未搬移/丢失。 */
    @Test void upgradeV8PreservesLegacyTargetsScoresReceiptsAndRestaurantMetadata() throws SQLException {
        String db = createDatabase(); migration(db, "8").migrate();
        try (var c = connect(db)) {
            execute(c, "INSERT INTO restaurants(id,name,category_code,source_type,created_at,updated_at) VALUES (100,'旧食堂','CANTEEN','LEGACY_IMPORT','2026-10-01','2026-10-02'),(101,'旧餐厅','HOT_POT','LEGACY_IMPORT','2026-10-01','2026-10-02')");
            execute(c, "INSERT INTO restaurant_tags(restaurant_id,tag,position) VALUES (100,'保留标签',0)");
            review(c, 100L, null, 1, "旧食堂评价保留正文");
            long oldId = number(c, "SELECT LAST_INSERT_ID()");
            execute(c, "INSERT INTO review_photos(review_id,position,media_file_id) VALUES (?,0,123)", oldId);
            execute(c, "INSERT INTO review_replies(review_id,author_user_id,body,client_request_id,request_hash) VALUES (?,2,'原有回复',UUID_TO_BIN(UUID()),UNHEX(REPEAT('22',32)))", oldId);
            review(c, 101L, null, 2, "旧餐厅评价保留正文");
            execute(c, "INSERT INTO review_request_receipts(author_user_id,client_request_id,review_id,restaurant_id) SELECT author_user_id,client_request_id,id,restaurant_id FROM reviews");
            execute(c, "INSERT INTO review_request_receipts(author_user_id,client_request_id,review_id,restaurant_id,deleted_at) VALUES (3,UUID_TO_BIN(UUID()),999,100,CURRENT_TIMESTAMP(6))");
            execute(c, "INSERT INTO restaurant_review_statistics(restaurant_id,review_count,participant_count,recommend_count,taste_sum,value_sum,service_sum,ambience_sum,overall_score_sum) VALUES (100,1,1,1,8,8,8,7.5,7.88)");
            List<String> before = strings(c, "SELECT CONCAT(id,':',restaurant_id,':',overall_score,':',HEX(client_request_id),':',HEX(request_hash)) FROM reviews ORDER BY id");
            assertThat(migration(db, null).migrate().migrationsExecuted).isEqualTo(3);
            assertThat(strings(c, "SELECT CONCAT(id,':',restaurant_id,':',overall_score,':',HEX(client_request_id),':',HEX(request_hash)) FROM reviews ORDER BY id")).isEqualTo(before);
            assertThat(strings(c, "SELECT restaurant_type FROM restaurants ORDER BY id")).containsExactly("CANTEEN", "REGULAR");
            assertThat(number(c, "SELECT COUNT(*) FROM restaurants WHERE updated_at='2026-10-02' AND supports_dine_in IS NULL AND supports_delivery IS NULL AND campus_code IS NULL")).isEqualTo(2);
            assertThat(number(c, "SELECT COUNT(*) FROM review_request_receipts")).isEqualTo(3);
            assertThat(number(c, "SELECT COUNT(*) FROM review_request_receipts WHERE deleted_at IS NOT NULL AND restaurant_id=100 AND window_id IS NULL")).isEqualTo(1);
            assertThat(number(c, "SELECT COUNT(*) FROM reviews WHERE window_id IS NULL")).isEqualTo(2);
            assertThat(number(c, "SELECT COUNT(*) FROM review_photos")).isEqualTo(1);
            assertThat(number(c, "SELECT COUNT(*) FROM review_replies")).isEqualTo(1);
            assertThat(number(c, "SELECT COUNT(*) FROM restaurant_tags")).isEqualTo(1);
            assertThat(strings(c, "SELECT overall_score_sum FROM restaurant_review_statistics WHERE restaurant_id=100")).containsExactly("7.88");
            assertThat(number(c, "SELECT COUNT(*) FROM canteen_windows")).isZero();
            assertThat(migration(db, null).migrate().migrationsExecuted).isZero();
        }
    }

    @Test void restaurantTypeAndUnknownDiningModesRemainIndependent() throws SQLException {
        restaurant(connection, 100, "CANTEEN");
        assertThat(number(connection, "SELECT COUNT(*) FROM restaurants WHERE supports_dine_in IS NULL AND supports_delivery IS NULL")).isEqualTo(1);
        execute(connection, "UPDATE restaurants SET supports_dine_in=1,supports_delivery=1,campus_code='QINGSHUIHE'");
        reject("UPDATE restaurants SET restaurant_type='canteen'");
        reject("UPDATE restaurants SET restaurant_type='CANTEEN '");
        reject("UPDATE restaurants SET restaurant_type='WINDOW'");
        reject("UPDATE restaurants SET supports_delivery=2");
        reject("UPDATE restaurants SET campus_code='UNKNOWN'");
        execute(connection, "UPDATE restaurants SET supports_dine_in=0,supports_delivery=NULL,campus_code=NULL");
    }

    /** V11保留历史长用户名；真实唯一索引允许Ka/ka共存，邮箱唯一规则不变。 */
    @Test void usernameCaseMigrationPreservesAccountsAndExactUniqueness() throws SQLException {
        String db = createDatabase(); migration(db, "10").migrate();
        try (var c = connect(db)) {
            execute(c, "INSERT INTO users(username,email,password_hash) VALUES (?,?,'old-hash')", "A".repeat(32), "legacy@std.uestc.edu.cn");
            assertThat(migration(db, null).migrate().migrationsExecuted).isEqualTo(1);
            execute(c, "INSERT INTO users(username,email,password_hash) VALUES ('Ka','first@std.uestc.edu.cn','hash'),('ka','second@std.uestc.edu.cn','hash')");
            assertThat(number(c, "SELECT COUNT(*) FROM users WHERE username='Ka'")).isEqualTo(1);
            assertThat(number(c, "SELECT COUNT(*) FROM users WHERE username='KA'")).isZero();
            assertThrows(SQLException.class, () -> execute(c, "INSERT INTO users(username,email,password_hash) VALUES ('Ka','third@std.uestc.edu.cn','hash')"));
            assertThrows(SQLException.class, () -> execute(c, "INSERT INTO users(username,email,password_hash) VALUES ('New','FIRST@std.uestc.edu.cn','hash')"));
            assertThat(strings(c, "SELECT password_hash FROM users WHERE username=?", "A".repeat(32))).containsExactly("old-hash");
            assertThat(migration(db, null).migrate().migrationsExecuted).isZero();
        }
    }

    @Test void windowsRequireARealCanteenAndRejectTypeSpoofing() throws SQLException {
        restaurant(connection, 100, "CANTEEN"); restaurant(connection, 101, "REGULAR");
        window(201, 100, "重复名称", 2);
        window(202, 100, "重复名称", 2);
        reject("INSERT INTO canteen_windows(canteen_id,name,floor_number,created_by) VALUES (101,'普通餐厅不能挂窗口',1,1)");
        reject("INSERT INTO canteen_windows(canteen_id,name,floor_number,created_by) VALUES (999,'缺失父级',1,1)");
        reject("INSERT INTO canteen_windows(canteen_id,canteen_type,name,floor_number,created_by) VALUES (101,'REGULAR','伪装类型',1,1)");
        reject("UPDATE restaurants SET restaurant_type='REGULAR' WHERE id=100");
        assertThat(number(connection, "SELECT COUNT(*) FROM canteen_windows")).isEqualTo(2);
    }

    @Test void windowsValidateCoreFieldsOptionalDataAndDoNotUseNameAsIdentity() throws SQLException {
        restaurant(connection, 100, "CANTEEN"); window(201, 100, "窗口", 1);
        for (String change : List.of("name=' '", "floor_number=NULL", "floor_number=101", "category_code='CANTEEN'", "price_per_person=10", "price_kind='EXACT'", "business_status='UNKNOWN'", "created_by=0", "cover_file_id=0", "description=' '", "deleted_at=CURRENT_TIMESTAMP(6)"))
            reject("UPDATE canteen_windows SET " + change + " WHERE id=201");
        execute(connection, "UPDATE canteen_windows SET floor_number=-1,category_code='NOODLE',price_kind='EXACT',price_per_person=12,description='真实介绍',cover_file_id=123 WHERE id=201");
        execute(connection, "UPDATE canteen_windows SET name='更名窗口',business_status='CLOSED' WHERE id=201");
        assertThat(number(connection, "SELECT id FROM canteen_windows WHERE name='更名窗口'")).isEqualTo(201);
    }

    @Test void reviewsRequireExactlyOneTargetAndKeepOriginalScoreRules() throws SQLException {
        review(connection, 100L, null, 1, "普通餐厅独立评价");
        review(connection, null, 201L, 1, "独立窗口评价正文");
        assertThat(number(connection, "SELECT COUNT(*) FROM reviews")).isEqualTo(2);
        assertThrows(SQLException.class, () -> review(connection, null, null, 1, "缺少目标评价正文"));
        assertThrows(SQLException.class, () -> review(connection, 100L, 201L, 1, "双重归属评价正文"));
        assertThrows(SQLException.class, () -> review(connection, null, 0L, 1, "无效窗口评价正文"));
        assertThrows(SQLException.class, () -> review(connection, 0L, null, 1, "无效餐厅评价正文"));
        assertThrows(SQLException.class, () -> review(connection, null, 201L, 0, "无效作者评价正文"));
        reject("UPDATE reviews SET taste_score=7.3 WHERE window_id=201");
        assertThat(strings(connection, "SELECT overall_score FROM reviews ORDER BY id")).containsExactly("7.88", "7.88");
    }

    @Test void durableReceiptsKeepTargetExclusivityAndPreventReuseAfterDeletion() throws SQLException {
        review(connection, null, 201L, 1, "可删除窗口评价正文");
        long id = number(connection, "SELECT LAST_INSERT_ID()");
        execute(connection, "INSERT INTO review_request_receipts(author_user_id,client_request_id,review_id,window_id) SELECT author_user_id,client_request_id,id,window_id FROM reviews WHERE id=?", id);
        reject("UPDATE review_request_receipts SET restaurant_id=100");
        reject("UPDATE review_request_receipts SET window_id=NULL");
        execute(connection, "UPDATE review_request_receipts SET deleted_at=CURRENT_TIMESTAMP(6)");
        execute(connection, "DELETE FROM reviews WHERE id=?", id);
        reject("INSERT INTO review_request_receipts(author_user_id,client_request_id,review_id,restaurant_id) SELECT author_user_id,client_request_id,999,100 FROM review_request_receipts");
        assertThat(number(connection, "SELECT COUNT(*) FROM review_request_receipts WHERE window_id=201 AND restaurant_id IS NULL AND deleted_at IS NOT NULL")).isEqualTo(1);
    }

    @Test void statisticsValidateCountsZeroSamplesAndPreserveClosedWindowHistory() throws SQLException {
        restaurant(connection, 100, "CANTEEN"); window(201, 100, "窗口", 1);
        execute(connection, "INSERT INTO window_review_statistics(window_id) VALUES (201)");
        reject("UPDATE window_review_statistics SET participant_count=1");
        reject("UPDATE window_review_statistics SET taste_sum=1");
        reject("INSERT INTO window_review_statistics(window_id) VALUES (999)");
        execute(connection, "UPDATE window_review_statistics SET review_count=1,participant_count=1");
        assertThat(new BigDecimal(strings(connection, "SELECT overall_score_sum / NULLIF(participant_count,0) FROM window_review_statistics").getFirst())).isEqualByComparingTo("0");
        review(connection, null, 201L, 1, "停业保留历史评价");
        execute(connection, "UPDATE canteen_windows SET name='改名后窗口',business_status='CLOSED' WHERE id=201");
        assertThat(number(connection, "SELECT COUNT(*) FROM reviews WHERE window_id=201")).isEqualTo(1);
        reject("DELETE FROM canteen_windows WHERE id=201");
        reject("DELETE FROM restaurants WHERE id=100");
    }

    /** 聚合只读本模块窗口及统计，等权平均、不按人数加权；0分有效，未评/停业/软删不计。 */
    @Test void aggregateUsesOnlyRatedOperatingWindowsAndKeepsReviewedZero() throws SQLException {
        restaurant(connection, 100, "CANTEEN"); restaurant(connection, 101, "CANTEEN");
        for (int i = 1; i <= 7; i++) window(200+i, i==7?101:100, "窗口"+i, 1);
        stats(201, 100, 800); stats(202, 1, 6); stats(203, 1, 0);
        stats(204, 1, 10); stats(205, 0, 0); stats(206, 1, 10); stats(207, 1, 10);
        execute(connection, "UPDATE canteen_windows SET business_status='CLOSED' WHERE id=204");
        execute(connection, "UPDATE canteen_windows SET deleted_at=CURRENT_TIMESTAMP(6),deleted_by=1 WHERE id=206");
        String sql = "SELECT AVG(s.overall_score_sum/NULLIF(s.participant_count,0)) FROM canteen_windows w JOIN window_review_statistics s ON s.window_id=w.id WHERE w.canteen_id=? AND w.business_status='OPERATING' AND w.deleted_at IS NULL AND s.participant_count>0";
        BigDecimal score = new BigDecimal(strings(connection, sql, 100).getFirst());
        assertThat(score.setScale(4, RoundingMode.HALF_UP)).isEqualByComparingTo("4.6667");
        assertThat(strings(connection, sql, 999)).containsExactly((String)null);
    }

    private void stats(long id, int count, int sum) throws SQLException {
        execute(connection, "INSERT INTO window_review_statistics(window_id,review_count,participant_count,overall_score_sum) VALUES (?,?,?,?)", id,count,count,sum);
    }
    private void window(long id, long canteen, String name, int floor) throws SQLException {
        execute(connection, "INSERT INTO canteen_windows(id,canteen_id,name,floor_number,created_by) VALUES (?,?,?,?,1)", id,canteen,name,floor);
    }
    private static void restaurant(Connection c, long id, String type) throws SQLException {
        execute(c, "INSERT INTO restaurants(id,name,category_code,source_type,restaurant_type) VALUES (?,'隔离测试餐厅','OTHER','LEGACY_IMPORT',?)", id,type);
    }
    /** V8和V10共用插入辅助：旧库没有window_id，只有窗口目标才显式写入新列。 */
    private static void review(Connection c, Long restaurant, Long window, long author, String body) throws SQLException {
        if (window == null && restaurant != null) {
            execute(c, "INSERT INTO reviews(restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash) VALUES (?,?,8,8,8,7.5,1,?,UUID_TO_BIN(UUID()),UNHEX(REPEAT('11',32)))", restaurant,author,body);
        } else {
            execute(c, "INSERT INTO reviews(restaurant_id,window_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash) VALUES (?,?,?,8,8,8,7.5,1,?,UUID_TO_BIN(UUID()),UNHEX(REPEAT('11',32)))", restaurant,window,author,body);
        }
    }
    private void reject(String sql) { assertThrows(SQLException.class, () -> execute(connection, sql)); }
    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (var st=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]); st.executeUpdate(); }
    }
    private static long number(Connection c, String sql, Object... args) throws SQLException { return Long.parseLong(strings(c, sql, args).getFirst()); }
    private static List<String> strings(Connection c, String sql, Object... args) throws SQLException {
        try(var st=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);
            try(var rs=st.executeQuery()) { var result=new ArrayList<String>(); while(rs.next())result.add(rs.getString(1)); return result; }
        }
    }
    private static String createDatabase() throws SQLException {
        String name="ginkgo_campus_it_"+UUID.randomUUID().toString().replace("-","");
        try(var c=connect(""); var st=c.createStatement()) { st.executeUpdate("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        CREATED.add(name); return name;
    }
    private static Connection connect(String name) throws SQLException { return DriverManager.getConnection(url(name),USER,password()); }
    private static String url(String name) { return SERVER+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true&sessionVariables=sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'"; }
    private static String password() {
        String value=System.getenv("CAMPUS_TEST_DB_PASSWORD");
        if(value==null)throw new IllegalStateException("Set CAMPUS_TEST_DB_PASSWORD for the isolated test instance");
        return value;
    }
    private static Flyway migration(String name,String target) {
        var config=Flyway.configure().dataSource(url(name),USER,password()).locations("classpath:db/migration").cleanDisabled(true);
        // 该套夹具验证V9/V10的历史升级，V12删列由单独的餐厅迁移测试覆盖。
        config.target(MigrationVersion.fromVersion(target==null ? "11" : target)); return config.load();
    }
}
