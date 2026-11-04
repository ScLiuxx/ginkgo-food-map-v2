package com.ginkgo.foodmap.restaurant;

import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchProjectionMapper;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchProjectionService;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.service.LoginSessionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 真实MySQL/Flyway/事务/相册SQL验证；显式restaurant.integration才运行，只创建清理自己的UUID库。
 * 不使用外层测试事务，确保被测REQUIRES_NEW读取已提交夹具；没有ES连接或业务库写入。
 */
@EnabledIfSystemProperty(named = "restaurant.integration", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.minimum-idle=0", "spring.datasource.hikari.maximum-pool-size=4"})
@Import(TestJwtKeys.class)
class RestaurantSearchProjectionIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RestaurantSearchProjectionService projections;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean private RestaurantSearchProjectionMapper mapper;
    @MockitoBean private LoginSessionService sessions;
    @MockitoBean private MediaFileService files;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_projection_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connect(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", RestaurantSearchProjectionIntegrationTest::username);
        registry.add("spring.datasource.password", RestaurantSearchProjectionIntegrationTest::password);
    }

    @BeforeEach void seed() {
        reset(mapper, files);
        jdbc.update("DELETE FROM restaurant_photos");
        jdbc.update("DELETE FROM restaurant_change_requests");
        jdbc.update("DELETE FROM restaurant_tags");
        jdbc.update("DELETE FROM restaurant_review_statistics");
        jdbc.update("DELETE FROM restaurants");
        jdbc.update("""
            INSERT INTO restaurants(id,name,category_code,restaurant_type,price_per_person,price_kind,
              latitude,longitude,needs_location,business_status,supports_dine_in,supports_delivery)
            VALUES(101,'测试火锅','HOT_POT','REGULAR',30,'ESTIMATE',30.750001,103.930002,0,'CLOSED',1,0),
              (102,'测试食堂',NULL,'CANTEEN',NULL,'UNKNOWN',NULL,NULL,1,'OPERATING',NULL,NULL),
              (103,'已删除店','OTHER','REGULAR',NULL,'UNKNOWN',NULL,NULL,1,'OPERATING',NULL,NULL),
              (104,'无公开封面','OTHER','REGULAR',NULL,'UNKNOWN',NULL,NULL,1,'OPERATING',NULL,NULL)
            """);
        jdbc.update("UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM' WHERE id=103");
        jdbc.update("INSERT INTO restaurant_tags(restaurant_id,tag,position) VALUES(101,'后标签',1),(101,'前标签',0),(103,'删除残留标签',0)");
        jdbc.update("""
            INSERT INTO restaurant_review_statistics(restaurant_id,review_count,participant_count,recommend_count,overall_score_sum)
            VALUES(101,7,3,1,23.12)
            """);
        request(51, 101, true); request(52, 102, false); request(53, 103, true); request(54, 102, true);
        jdbc.update("""
            INSERT INTO restaurant_photos(id,restaurant_id,media_file_id,position,source_request_id)
            VALUES(9007199254740993,101,801,0,51),(802,102,802,0,52),(803,103,803,0,53),(804,104,804,0,54)
            """);
    }

    /** 隔离夹具构造审核状态，不调用业务审核或上传，也不把文件逻辑ID当作公开关联ID。 */
    private void request(long id, long restaurant, boolean approved) {
        if (approved) jdbc.update("""
            INSERT INTO restaurant_change_requests(id,restaurant_id,request_type,submitted_by,client_request_id,
              request_hash,proposed_data,status,reviewed_by,reviewed_at,approved_data,before_data)
            VALUES(?,?,'UPDATE',1,UUID(),UNHEX(REPEAT('11',32)),JSON_OBJECT(),'APPROVED',2,CURRENT_TIMESTAMP(6),JSON_OBJECT(),JSON_OBJECT())
            """, id, restaurant);
        else jdbc.update("""
            INSERT INTO restaurant_change_requests(id,restaurant_id,request_type,submitted_by,client_request_id,
              request_hash,proposed_data) VALUES(?,?,'UPDATE',1,UUID(),UNHEX(REPEAT('11',32)),JSON_OBJECT())
            """, id, restaurant);
    }

    @Test void buildsRegularCanteenAndTombstoneWithoutInitializingMissingStatistics() {
        var result = projections.assemble(List.of(101L, 102L, 103L));
        var byId = result.stream().collect(java.util.stream.Collectors.toMap(d -> d.getId(), d -> d));
        assertThat(result).allSatisfy(document -> assertThat(document.getProjectionVersion()).isEqualTo(1));
        var regular = byId.get(101L);
        assertThat(regular.getCategoryName()).isEqualTo("火锅串串");
        assertThat(regular.getTags()).containsExactly("前标签", "后标签");
        assertThat(regular.getRatingScore()).isCloseTo(23.12 / 3, within(1e-14));
        assertThat(regular.getRecommendRate()).isEqualTo(1.0 / 3);
        assertThat(regular.getLocation().getLat()).isEqualByComparingTo("30.750001");
        assertThat(regular.getCoverPhotoId()).isEqualTo("9007199254740993");
        assertThat(regular.getBusinessStatus()).isEqualTo("CLOSED");
        assertThat(regular.getSupportsDineIn()).isTrue(); assertThat(regular.getSupportsDelivery()).isFalse();
        var canteen = byId.get(102L);
        assertThat(canteen.getCategoryCode()).isNull(); assertThat(canteen.getCategoryName()).isNull();
        assertThat(canteen.getLocation()).isNull(); assertThat(canteen.getRatingScore()).isNull();
        assertThat(canteen.getTags()).isEmpty(); assertThat(canteen.getCoverPhotoId()).isNull();
        assertThat(canteen.getSupportsDelivery()).isNull();
        assertThat(byId.get(103L).getDeleted()).isTrue();
        assertThat(byId.get(103L).getName()).isNull(); assertThat(byId.get(103L).getCoverPhotoId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_review_statistics", Long.class)).isEqualTo(1);
        verifyNoInteractions(files);
    }

    @Test void excludesUnapprovedAndWrongTargetCoverReferences() {
        var documents = projections.assemble(List.of(102L, 104L));
        assertThat(documents).allSatisfy(document -> assertThat(document.getCoverPhotoId()).isNull());
        verifyNoInteractions(files);
    }

    @Test void concurrentCommitCannotMixNewTagsOrCoverWithOldMerchantSnapshot() throws Exception {
        var updated = new AtomicBoolean();
        doAnswer(invocation -> {
            // MyBatis Mapper是接口代理，直接通过SqlSession执行真实映射，避免对抽象接口callRealMethod。
            var rows = sqlSession.selectList(RestaurantSearchProjectionMapper.class.getName() + ".selectSources",
                    Map.of("ids", List.of(101L)));
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            if (updated.compareAndSet(false, true)) {
                // 独立连接提交，不依赖测试线程内的事务传播；SELECT无行锁，不阻塞写入。
                try (Connection writer = connect(database); Statement statement = writer.createStatement()) {
                    writer.setAutoCommit(false);
                    statement.executeUpdate("UPDATE restaurants SET name='新店名',search_projection_version=search_projection_version+1 WHERE id=101");
                    statement.executeUpdate("UPDATE restaurant_tags SET tag='新标签' WHERE restaurant_id=101 AND position=0");
                    statement.executeUpdate("UPDATE restaurant_change_requests SET restaurant_id=102 WHERE id=51");
                    writer.commit();
                }
            }
            return rows;
        }).when(mapper).selectSources(List.of(101L));
        var old = projections.assemble(List.of(101L)).getFirst();
        assertThat(old.getProjectionVersion()).isEqualTo(1);
        assertThat(old.getName()).isEqualTo("测试火锅");
        assertThat(old.getTags()).containsExactly("前标签", "后标签");
        assertThat(old.getCoverPhotoId()).isEqualTo("9007199254740993");
        var current = projections.assemble(List.of(101L)).getFirst();
        assertThat(current.getProjectionVersion()).isEqualTo(2);
        assertThat(current.getName()).isEqualTo("新店名");
        assertThat(current.getTags()).containsExactly("新标签", "后标签");
        assertThat(current.getCoverPhotoId()).isNull();
    }

    @Test void missingMerchantFailsInsteadOfInventingDeletion() {
        assertThatIllegalStateException().isThrownBy(() -> projections.assemble(List.of(99999L)));
    }

    @AfterAll static void dropDatabase() throws SQLException {
        if (!created) return;
        if (!database.matches("ginkgo_projection_it_[a-f0-9]{32}")) throw new IllegalStateException("Unexpected test database");
        try (Connection connection = connect(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    private static String username() { return System.getenv().getOrDefault("RESTAURANT_TEST_DB_USERNAME", "root"); }
    private static String password() { return Objects.requireNonNull(System.getenv("RESTAURANT_TEST_DB_PASSWORD"), "Set isolated test password"); }
    private static String url(String databaseName) {
        String server = System.getenv().getOrDefault("RESTAURANT_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) throw new IllegalArgumentException("Loopback test server required");
        return server + databaseName + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }
    private static Connection connect(String databaseName) throws SQLException { return DriverManager.getConnection(url(databaseName), username(), password()); }
}
