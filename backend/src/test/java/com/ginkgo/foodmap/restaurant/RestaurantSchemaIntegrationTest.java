package com.ginkgo.foodmap.restaurant;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 直接执行真实Flyway迁移，验证MySQL的CHECK、NULL、Unicode、唯一键和外键语义，不用H2模拟。
 *
 * <p>仅在显式设置restaurant.integration=true时运行。测试只接受回环地址，创建随机命名的临时库，
 * 不连接业务库；每项约束测试独立事务回滚，最后只删除本次确实创建的数据库。登录归属、标签总字符数、
 * 乐观锁和公开接口过滤尚未实现，不把DDL约束测试当作这些应用能力已经完成。</p>
 */
@EnabledIfSystemProperty(named = "restaurant.integration", matches = "true")
class RestaurantSchemaIntegrationTest {

    private static final String SERVER = System.getenv().getOrDefault(
            "RESTAURANT_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
    private static final String USERNAME = System.getenv().getOrDefault("RESTAURANT_TEST_DB_USERNAME", "root");
    private static final Set<String> CREATED_DATABASES = new LinkedHashSet<>();
    private static String database;
    private Connection connection;

    /** 历史约束固定在V11，V12精简迁移由独立测试验证，不依赖业务库或JWT密钥。 */
    @BeforeAll
    static void prepareSchema() throws SQLException {
        if (!SERVER.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Restaurant schema tests require an explicit loopback MySQL server");
        }
        password();
        database = createDatabase();
        migration(database, null).migrate();
    }

    /** 约束错误只影响当前语句；每个测试最后整体回滚，保证分类和目录初态不受测试顺序影响。 */
    @BeforeEach
    void beginTransaction() throws SQLException {
        connection = connect(database);
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws SQLException {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /** 不使用Flyway clean；严格匹配且只清理本次记录的随机库，保留任何其他本地数据库。 */
    @AfterAll
    static void dropTestDatabases() throws SQLException {
        for (String created : CREATED_DATABASES) {
            if (!created.matches("ginkgo_restaurant_it_[a-f0-9]{32}")) {
                throw new IllegalStateException("Refusing to remove an unexpected database name");
            }
            try (Connection admin = connect(""); Statement statement = admin.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + created + "`");
            }
        }
    }

    /** 新库只有受控字典，无演示餐厅；重复运行迁移不重复插入分类，也不改变既有角色。 */
    @Test
    void shouldMigrateFreshDatabaseOnceWithoutDemoRestaurants() throws SQLException {
        assertThat(migration(database, null).info().current().getVersion().getVersion()).isEqualTo("11");
        assertThat(migration(database, null).migrate().migrationsExecuted).isZero();
        assertThat(strings(connection, "SELECT name FROM restaurant_categories ORDER BY sort_order, code"))
                .containsExactly("火锅", "川菜", "小吃", "食堂", "快餐", "烧烤", "甜品", "冒菜",
                        "面馆", "麻辣烫", "粥粉面", "轻食", "其他");
        assertThat(number(connection, "SELECT COUNT(*) FROM restaurant_categories WHERE enabled = 1")).isEqualTo(13);
        assertThat(number(connection, "SELECT COUNT(*) FROM restaurants")).isZero();
        assertThat(number(connection, "SELECT COUNT(*) FROM restaurant_tags")).isZero();
        assertThat(strings(connection, "SELECT code FROM roles ORDER BY code"))
                .containsExactly("ROLE_ADMIN", "ROLE_USER");
        assertThat(number(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")).isEqualTo(11);
        migration(database, null).validate();
    }

    /** 从已存在用户与角色关系的V2升级，验证新增迁移不重建或覆盖认证数据。 */
    @Test
    void shouldUpgradeVersionTwoWithoutChangingExistingAccounts() throws SQLException {
        String upgradeDatabase = createDatabase();
        migration(upgradeDatabase, "2").migrate();
        try (Connection old = connect(upgradeDatabase)) {
            update(old, """
                    INSERT INTO users (username, email, password_hash, credential_version, row_version)
                    VALUES ('migration_user', 'migration@example.invalid', 'test-only-placeholder', 7, 3)
                    """);
            update(old, """
                    INSERT INTO user_roles (user_id, role_id)
                    SELECT u.id, r.id FROM users u JOIN roles r ON r.code = 'ROLE_USER'
                    WHERE u.username = 'migration_user'
                    """);
            long accountId = number(old, "SELECT id FROM users WHERE username = 'migration_user'");
            Flyway upgrade = migration(upgradeDatabase, null);
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(9);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(number(old, "SELECT id FROM users WHERE username = 'migration_user'")).isEqualTo(accountId);
            assertThat(strings(old, "SELECT password_hash FROM users WHERE id = ?", accountId))
                    .containsExactly("test-only-placeholder");
            assertThat(number(old, "SELECT credential_version FROM users WHERE id = ?", accountId)).isEqualTo(7);
            assertThat(number(old, "SELECT row_version FROM users WHERE id = ?", accountId)).isEqualTo(3);
            assertThat(number(old, "SELECT COUNT(*) FROM user_roles WHERE user_id = ?", accountId)).isEqualTo(1);
            assertThat(number(old, "SELECT COUNT(*) FROM restaurants")).isZero();
            assertThat(number(old, "SELECT COUNT(*) FROM restaurant_categories")).isEqualTo(13);
            upgrade.validate();
        }
    }

    /** 同名分店合法；历史归属、地址与位置未知时保持NULL，不能默认伪装为校园坐标。 */
    /** 删除校区归属不修改商户其他业务资料、标签或历史申请，重复启动不再次迁移。 */
    @Test
    void shouldRemoveCampusWithoutChangingOtherBusinessData() throws SQLException {
        String upgradeDatabase = createDatabase();
        migration(upgradeDatabase, "13").migrate();
        try (Connection old = connect(upgradeDatabase)) {
            update(old, "INSERT INTO restaurants(id,name,category_code,campus_code,latitude,longitude,needs_location) VALUES(501,'迁移保留商户','OTHER','SHAHE',30.675789,104.099671,0)");
            update(old, "INSERT INTO restaurant_tags VALUES(501,'保留标签',0)");
            update(old, "INSERT INTO restaurant_change_requests(restaurant_id,submitted_by,client_request_id,request_hash,proposed_data) VALUES(501,42,?,UNHEX(REPEAT('00',32)),JSON_OBJECT('name','待审资料'))", UUID.randomUUID().toString());
            var before = strings(old, "SELECT CONCAT_WS('|',id,name,category_code,address,latitude,longitude,created_at,updated_at) FROM restaurants WHERE id=501");
            var receipt = strings(old, "SELECT CAST(proposed_data AS CHAR) FROM restaurant_change_requests");
            var upgrade = migration(upgradeDatabase, "14");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            upgrade.validate();
            assertThat(number(old, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='restaurants' AND column_name='campus_code'")).isZero();
            assertThat(strings(old, "SELECT CONCAT_WS('|',id,name,category_code,address,latitude,longitude,created_at,updated_at) FROM restaurants WHERE id=501")).isEqualTo(before);
            assertThat(strings(old, "SELECT tag FROM restaurant_tags WHERE restaurant_id=501")).containsExactly("保留标签");
            assertThat(strings(old, "SELECT CAST(proposed_data AS CHAR) FROM restaurant_change_requests")).isEqualTo(receipt);
        }
    }

    @Test
    void shouldAllowBranchesAndUnknownLegacyFields() throws SQLException {
        long first = insertLegacy("同名餐厅");
        long second = insertLegacy("同名餐厅");
        assertThat(first).isNotEqualTo(second);
        assertThat(number(connection, """
                SELECT COUNT(*) FROM restaurants WHERE name = '同名餐厅' AND created_by IS NULL
                    AND address IS NULL AND latitude IS NULL AND longitude IS NULL
                    AND needs_location = 1 AND price_per_person IS NULL AND price_kind = 'UNKNOWN'
                    AND row_version = 0 AND deleted_at IS NULL
                """)).isEqualTo(2);
    }

    /** Unicode按字符而非UTF-8字节数限制，40个补充平面字符也能完整往返。 */
    @Test
    void shouldPreserveFortyUnicodeCharacters() throws SQLException {
        String name = "🥟".repeat(40);
        long id = insertLegacy(name);
        assertThat(strings(connection, "SELECT name FROM restaurants WHERE id = ?", id)).containsExactly(name);
        assertThat(number(connection, "SELECT CHAR_LENGTH(name) FROM restaurants WHERE id = ?", id)).isEqualTo(40);
        reject(1406, "INSERT INTO restaurants(name, category_code, source_type) VALUES (?, 'OTHER', 'LEGACY_IMPORT')",
                "🥟".repeat(41));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", " 餐厅", "餐厅 ", "　餐厅", "餐厅\n"})
    void shouldRejectEmptyOrUntrimmedNames(String name) {
        reject(3819, "INSERT INTO restaurants(name, category_code, source_type) VALUES (?, 'OTHER', 'LEGACY_IMPORT')", name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", " 地址", "地址 "})
    void shouldRequireBlankAddressesToBeRepresentedAsNull(String address) throws SQLException {
        long id = insertLegacy("地址约束");
        reject(3819, "UPDATE restaurants SET address = ? WHERE id = ?", address, id);
    }

    @ParameterizedTest
    @CsvSource(value = {"UNKNOWN,NULL", "EXACT,1", "EXACT,5000", "ESTIMATE,25"}, nullValues = "NULL")
    void shouldAcceptConsistentPriceKinds(String kind, Integer amount) throws SQLException {
        long id = insertLegacy("消费边界");
        assertThat(update(connection, "UPDATE restaurants SET price_kind = ?, price_per_person = ? WHERE id = ?",
                kind, amount, id)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(value = {"UNKNOWN,25", "EXACT,NULL", "ESTIMATE,NULL", "EXACT,0", "ESTIMATE,5001", "OTHER,NULL", "exact,25"}, nullValues = "NULL")
    void shouldRejectUnknownPricesWithAmountsAndMissingOrOutOfRangeAmounts(String kind, Integer amount) throws SQLException {
        long id = insertLegacy("消费约束");
        reject(3819, "UPDATE restaurants SET price_kind = ?, price_per_person = ? WHERE id = ?", kind, amount, id);
    }

    /** 坐标成对与范围检查显式排除NULL，避免CHECK得到UNKNOWN后错误放行。 */
    @ParameterizedTest
    @CsvSource(value = {"0,NULL,NULL", "0,30,NULL", "0,NULL,104", "1,30,104", "2,NULL,NULL",
            "0,2.999999,104", "0,54.000001,104", "0,30,72.999999", "0,30,136.000001"}, nullValues = "NULL")
    void shouldRejectInconsistentLocations(int needsLocation, BigDecimal latitude, BigDecimal longitude) throws SQLException {
        long id = insertLegacy("位置约束");
        reject(3819, "UPDATE restaurants SET needs_location = ?, latitude = ?, longitude = ? WHERE id = ?",
                needsLocation, latitude, longitude, id);
    }

    @ParameterizedTest
    @CsvSource({"3,73", "54,136", "30.754400,103.931200"})
    void shouldAcceptLocatedUserRestaurantsAtCoordinateBoundaries(BigDecimal latitude, BigDecimal longitude) throws SQLException {
        long id = insertLegacy("用户餐厅");
        update(connection, """
                UPDATE restaurants SET source_type = 'USER', created_by = 42, updated_by = 42,
                    needs_location = 0, latitude = ?, longitude = ? WHERE id = ?
                """, latitude, longitude, id);
        assertThat(number(connection, "SELECT created_by FROM restaurants WHERE id = ?", id)).isEqualTo(42);
    }

    @Test
    void shouldRequireUserOwnershipAndConfirmedLocation() throws SQLException {
        long id = insertLegacy("来源约束");
        reject(3819, "UPDATE restaurants SET source_type = 'USER' WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET source_type = 'USER', created_by = 42 WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET source_type = 'PREVIEW' WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET created_by = 0 WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET updated_by = 0 WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET coordinate_system = 'WGS84' WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET coordinate_system = 'gcj02' WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET business_status = 'operating' WHERE id = ?", id);
    }

    /** 防止ASCII排序规则忽略末尾空格，让数据库存下应用无法映射的枚举编码。 */
    @Test
    void shouldRejectPaddedMachineCodesAndInvalidCategoryCharacters() throws SQLException {
        long id = insertLegacy("编码约束");
        for (String assignment : List.of("price_kind = 'UNKNOWN '", "coordinate_system = 'GCJ02 '",
                "business_status = 'OPERATING '", "source_type = 'LEGACY_IMPORT '")) {
            reject(3819, "UPDATE restaurants SET " + assignment + " WHERE id = ?", id);
        }
        reject(3819, "UPDATE restaurants SET deleted_at = CURRENT_TIMESTAMP(6), delete_reason_code = 'SYSTEM ' WHERE id = ?", id);
        reject(3819, "INSERT INTO restaurant_categories(code, name) VALUES (?, '非法分类')", "TRAILING\n");
    }

    /** 分类引用存在性由外键保证；停用不是删除，不能让已有餐厅丢失分类。 */
    @Test
    void shouldKeepDisabledCategoriesButRestrictMissingReferences() throws SQLException {
        long id = insertLegacy("分类约束");
        reject(1452, "UPDATE restaurants SET category_code = 'MISSING' WHERE id = ?", id);
        reject(1452, "UPDATE restaurants SET category_code = 'other' WHERE id = ?", id);
        reject(1451, "DELETE FROM restaurant_categories WHERE code = 'OTHER'");
        update(connection, "UPDATE restaurant_categories SET enabled = 0 WHERE code = 'OTHER'");
        assertThat(number(connection, """
                SELECT COUNT(*) FROM restaurants r JOIN restaurant_categories c ON c.code = r.category_code
                WHERE r.id = ? AND c.enabled = 0
                """, id)).isEqualTo(1);
        reject(3819, "UPDATE restaurant_categories SET enabled = 2 WHERE code = 'OTHER'");
        reject(3819, "INSERT INTO restaurant_categories(code, name) VALUES ('lower_case', '无效编码')");
        reject(3819, "UPDATE restaurant_categories SET name = '' WHERE code = 'OTHER'");
        reject(1062, "INSERT INTO restaurant_categories(code, name) VALUES ('DUPLICATE_NAME', '其他')");
    }

    /** 有限位置加唯一键同时保证标签顺序、同店去重和最多8项，软删除不抹掉子记录。 */
    @Test
    void shouldEnforceTagIdentityOrderAndEightItemLimit() throws SQLException {
        long id = insertLegacy("标签约束");
        update(connection, "INSERT INTO restaurant_tags VALUES (?, 'Wi-Fi', 0)", id);
        reject(1062, "INSERT INTO restaurant_tags VALUES (?, 'wi-fi', 1)", id);
        reject(1062, "INSERT INTO restaurant_tags VALUES (?, '其他标签', 0)", id);
        for (int position = 1; position <= 7; position++) {
            update(connection, "INSERT INTO restaurant_tags VALUES (?, ?, ?)", id, "标签" + position, position);
        }
        assertThat(strings(connection, "SELECT tag FROM restaurant_tags WHERE restaurant_id = ? ORDER BY position", id))
                .containsExactly("Wi-Fi", "标签1", "标签2", "标签3", "标签4", "标签5", "标签6", "标签7");
        reject(3819, "INSERT INTO restaurant_tags VALUES (?, '第九项', 8)", id);
        reject(1452, "INSERT INTO restaurant_tags VALUES (18446744073709551615, '孤儿标签', 0)");
        reject(1451, "DELETE FROM restaurants WHERE id = ?", id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", " 标签", "标签 "})
    void shouldRejectBlankOrUntrimmedTags(String tag) throws SQLException {
        long id = insertLegacy("标签文本");
        reject(3819, "INSERT INTO restaurant_tags VALUES (?, ?, 0)", id, tag);
    }

    @Test
    void shouldRejectAnOverlongTag() throws SQLException {
        long id = insertLegacy("标签长度");
        reject(1406, "INSERT INTO restaurant_tags VALUES (?, ?, 0)", id, "签".repeat(81));
    }

    /** 经营状态与删除独立；删除和恢复采用完整元数据组合，标签资料始终保留。 */
    @Test
    void shouldPreserveClosedStateAndTagsAcrossSoftDeletionAndRestoration() throws SQLException {
        long id = insertLegacy("停业与删除");
        update(connection, "INSERT INTO restaurant_tags VALUES (?, '历史标签', 0)", id);
        update(connection, "UPDATE restaurants SET business_status = 'CLOSED' WHERE id = ?", id);
        assertThat(number(connection, "SELECT COUNT(*) FROM restaurants WHERE id = ? AND deleted_at IS NULL", id)).isEqualTo(1);
        update(connection, """
                UPDATE restaurants SET deleted_at = CURRENT_TIMESTAMP(6), deleted_by = 42,
                    delete_reason_code = 'ADMIN_REMOVAL' WHERE id = ?
                """, id);
        assertThat(number(connection, "SELECT COUNT(*) FROM restaurants WHERE id = ? AND deleted_at IS NULL", id)).isZero();
        assertThat(number(connection, "SELECT COUNT(*) FROM restaurant_tags WHERE restaurant_id = ?", id)).isEqualTo(1);
        update(connection, "UPDATE restaurants SET deleted_at = NULL, deleted_by = NULL, delete_reason_code = NULL WHERE id = ?", id);
        assertThat(strings(connection, "SELECT business_status FROM restaurants WHERE id = ? AND deleted_at IS NULL", id))
                .containsExactly("CLOSED");
    }

    @Test
    void shouldRejectPartialDeletionMetadataButAllowExplicitSystemDeletion() throws SQLException {
        long id = insertLegacy("删除约束");
        reject(3819, "UPDATE restaurants SET deleted_by = 42 WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET delete_reason_code = 'SYSTEM' WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET deleted_at = CURRENT_TIMESTAMP(6), delete_reason_code = 'OWNER_REQUEST' WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET deleted_at = CURRENT_TIMESTAMP(6), deleted_by = 42, delete_reason_code = 'INVALID' WHERE id = ?", id);
        update(connection, "UPDATE restaurants SET deleted_at = CURRENT_TIMESTAMP(6), delete_reason_code = 'SYSTEM' WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET deleted_by = 0 WHERE id = ?", id);
        reject(3819, "UPDATE restaurants SET deleted_at = NULL WHERE id = ?", id);
    }

    /** 验证TIMESTAMP保存微秒与会话时区转换；不据此宣称应用运行配置已经验证完毕。 */
    @Test
    void shouldRoundTripMicrosecondsWithAnExplicitUtcSession() throws SQLException {
        long id = insertLegacy("时间精度");
        Instant instant = Instant.parse("2026-10-28T01:02:03.123456Z");
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        try (var statement = connection.prepareStatement("UPDATE restaurants SET created_at = ? WHERE id = ?")) {
            statement.setTimestamp(1, Timestamp.from(instant), utc);
            statement.setLong(2, id);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("SELECT created_at FROM restaurants WHERE id = ?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getTimestamp(1, utc).toInstant()).isEqualTo(instant);
            }
        }
        update(connection, "SET SESSION time_zone = '+08:00'");
        assertThat(strings(connection, "SELECT CAST(created_at AS CHAR) FROM restaurants WHERE id = ?", id))
                .containsExactly("2026-10-28 09:02:03.123456");
    }

    /** V12在有资料的旧库上只删除三列，保留坐标、归属、软删除、标签和评分统计。 */
    @Test
    void shouldSimplifyVersionTwelveWithoutLosingBusinessOrAccountData() throws SQLException {
        String upgradeDatabase = createDatabase();
        migration(upgradeDatabase, "11").migrate();
        try (Connection old = connect(upgradeDatabase)) {
            update(old, "INSERT INTO users(id,username,email,password_hash,row_version) VALUES (42,'v12_fixture','v12@example.invalid','test-only-placeholder',7)");
            update(old, """
                    INSERT INTO restaurants(id,name,category_code,address,latitude,longitude,needs_location,
                        source_type,created_by,updated_by,row_version,created_at,updated_at)
                    VALUES (101,'迁移测试店','OTHER','测试街道',30.754400,103.931200,FALSE,
                        'USER',42,42,9,'2026-10-01','2026-10-02')
                    """);
            update(old, "INSERT INTO restaurants(id,name,category_code,source_type,deleted_at,delete_reason_code) VALUES (102,'旧删除店','OTHER','LEGACY_IMPORT','2026-10-03','SYSTEM')");
            update(old, "INSERT INTO restaurant_tags(restaurant_id,tag,position) VALUES (101,'测试标签',0)");
            update(old, "INSERT INTO restaurant_review_statistics(restaurant_id,review_count,participant_count,recommend_count,taste_sum,value_sum,service_sum,ambience_sum,overall_score_sum) VALUES (101,1,1,1,8,8,8,8,8)");
            var before = strings(old, "SELECT CONCAT_WS('|',id,name,address,latitude,longitude,created_by,updated_by,created_at,updated_at) FROM restaurants WHERE id=101");
            var upgrade = migration(upgradeDatabase, "12");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            upgrade.validate();
            assertThat(strings(old, "SELECT CONCAT_WS('|',id,name,address,latitude,longitude,created_by,updated_by,created_at,updated_at) FROM restaurants WHERE id=101")).isEqualTo(before);
            assertThat(number(old, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='restaurants' AND column_name IN ('row_version','coordinate_system','source_type')")).isZero();
            assertThat(number(old, "SELECT row_version FROM users WHERE id=42")).isEqualTo(7);
            assertThat(strings(old, "SELECT tag FROM restaurant_tags WHERE restaurant_id=101")).containsExactly("测试标签");
            assertThat(number(old, "SELECT review_count FROM restaurant_review_statistics WHERE restaurant_id=101")).isEqualTo(1);
            assertThat(strings(old, "SELECT delete_reason_code FROM restaurants WHERE id=102 AND deleted_at IS NOT NULL AND needs_location=1")).containsExactly("SYSTEM");
            // 删列同时重建机器码约束，仍须拒绝尾随空格；坐标成对规则继续由数据库兜底。
            for (String assignment : List.of("price_kind='UNKNOWN '", "business_status='OPERATING '", "latitude=NULL")) {
                SQLException error = assertThrows(SQLException.class, () -> update(old, "UPDATE restaurants SET " + assignment + " WHERE id=101"));
                assertThat(error.getErrorCode()).isEqualTo(3819);
            }
        }
    }

    /** 从空库顺序执行V1至V12，验证新装不依赖手工删列且不会注入餐厅数据。 */
    @Test
    void shouldCreateFreshSimplifiedSchema() throws SQLException {
        String fresh = createDatabase();
        var latest = migration(fresh, "12");
        latest.migrate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("12");
        assertThat(latest.migrate().migrationsExecuted).isZero();
        try (Connection current = connect(fresh)) {
            assertThat(number(current, "SELECT COUNT(*) FROM restaurants")).isZero();
            assertThat(number(current, "SELECT COUNT(*) FROM restaurant_categories")).isEqualTo(13);
            assertThat(number(current, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='restaurants' AND column_name IN ('row_version','coordinate_system','source_type')")).isZero();
        }
    }

    /** V15允许无目标的新店申请，旧纠错快照与外键保持，重复迁移不产生额外变化。 */
    @Test
    void shouldAllowNewSubmissionsWithoutChangingExistingCorrections() throws SQLException {
        String database = createDatabase();
        migration(database, "14").migrate();
        try (Connection current = connect(database)) {
            update(current, "INSERT INTO restaurants(id,name,category_code) VALUES (101,'迁移保留商户','OTHER')");
            update(current, "INSERT INTO restaurant_change_requests(restaurant_id,submitted_by,client_request_id,request_hash,proposed_data) VALUES (101,42,UUID(),UNHEX(SHA2('old',256)),JSON_OBJECT('name','旧纠错'))");
            var before = strings(current, "SELECT CONCAT_WS('|',id,restaurant_id,submitted_by,client_request_id,HEX(request_hash),proposed_data,status,created_at) FROM restaurant_change_requests");
            var upgrade = migration(database, "15");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            upgrade.validate();
            assertThat(strings(current, "SELECT CONCAT_WS('|',id,restaurant_id,submitted_by,client_request_id,HEX(request_hash),proposed_data,status,created_at) FROM restaurant_change_requests")).isEqualTo(before);
            update(current, "INSERT INTO restaurant_change_requests(restaurant_id,submitted_by,client_request_id,request_hash,proposed_data) VALUES (NULL,42,UUID(),UNHEX(SHA2('new',256)),JSON_OBJECT('name','新店'))");
            assertThat(number(current, "SELECT COUNT(*) FROM restaurant_change_requests WHERE restaurant_id IS NULL AND status='PENDING'")).isEqualTo(1);
            SQLException missing = assertThrows(SQLException.class, () -> update(current, "INSERT INTO restaurant_change_requests(restaurant_id,submitted_by,client_request_id,request_hash,proposed_data) VALUES (999,42,UUID(),UNHEX(SHA2('invalid',256)),JSON_OBJECT())"));
            assertThat(missing.getErrorCode()).isEqualTo(1452);
            SQLException delete = assertThrows(SQLException.class, () -> update(current, "DELETE FROM restaurants WHERE id=101"));
            assertThat(delete.getErrorCode()).isEqualTo(1451);
            assertThat(number(current, "SELECT COUNT(*) FROM restaurants")).isEqualTo(1);
        }
    }

    /** V16只给旧文件补REVIEW用途，不更改对象路径或申请摘要；重复执行无额外变化。 */
    @Test
    void shouldPreserveFilesAndRequestsWhenAddingMerchantPhotos() throws SQLException {
        String database = createDatabase();migration(database,"15").migrate();
        try(Connection current=connect(database)) {
            update(current,"INSERT INTO media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,expires_at) VALUES(101,42,'test-bucket','review-images/old.jpg','image/jpeg',100,10,10,UNHEX(SHA2('old',256)),'TEMPORARY',CURRENT_TIMESTAMP+INTERVAL 1 DAY)");
            update(current,"INSERT INTO restaurant_change_requests(submitted_by,client_request_id,request_hash,proposed_data) VALUES(42,UUID(),UNHEX(SHA2('old',256)),JSON_OBJECT('name','旧申请'))");
            var before=strings(current,"SELECT CONCAT_WS('|',id,submitted_by,client_request_id,HEX(request_hash),proposed_data) FROM restaurant_change_requests");
            var upgrade=migration(database,"16");assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();upgrade.validate();
            assertThat(strings(current,"SELECT CONCAT_WS('|',purpose,storage_key,status) FROM media_files")).containsExactly("REVIEW|review-images/old.jpg|TEMPORARY");
            assertThat(strings(current,"SELECT CONCAT_WS('|',id,submitted_by,client_request_id,HEX(request_hash),proposed_data) FROM restaurant_change_requests")).isEqualTo(before);
            assertThat(number(current,"SELECT COUNT(*) FROM restaurant_change_photos")).isZero();
        }
    }

    /** 测试夹具只创建历史候选，不编造任何评价、评分或已核实信息。 */
    private long insertLegacy(String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO restaurants(name, category_code, source_type) VALUES (?, 'OTHER', 'LEGACY_IMPORT')",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    /** 验证具体MySQL约束错误码，不能将断线、拼错SQL或权限不足误当成约束生效。 */
    private void reject(int mysqlError, String sql, Object... arguments) {
        SQLException error = assertThrows(SQLException.class, () -> update(connection, sql, arguments));
        assertThat(error.getErrorCode()).as("MySQL constraint error for %s", sql).isEqualTo(mysqlError);
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
        String name = "ginkgo_restaurant_it_" + UUID.randomUUID().toString().replace("-", "");
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
        String value = System.getenv("RESTAURANT_TEST_DB_PASSWORD");
        if (value == null) throw new IllegalStateException("Set RESTAURANT_TEST_DB_PASSWORD for the isolated test MySQL server");
        return value;
    }

    private static Flyway migration(String name, String target) {
        var config = Flyway.configure().dataSource(url(name), USERNAME, password())
                .locations("classpath:db/migration").cleanDisabled(true);
        // 原有测试验证旧约束；明确指定12的测试覆盖删列后的结构与数据保留。
        config.target(MigrationVersion.fromVersion(target == null ? "11" : target));
        return config.load();
    }
}
