package com.ginkgo.foodmap.restaurant;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.json.JsonData;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchOutboxMapper;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchProjectionService;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchSyncService;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchIndexWriter;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.service.LoginSessionService;
import org.junit.jupiter.api.*;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.sql.*;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实MySQL+ES仅写本次UUID库/索引/别名，调度关闭，手动执行批次，不触碰正式搜索别名。 */
@EnabledIfSystemProperty(named="ginkgo.sync.it",matches="true")
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.datasource.hikari.minimum-idle=0",
        "app.search.elasticsearch.enabled=true","app.search.sync.enabled=false"})
@Import(TestJwtKeys.class)
class RestaurantSearchSyncIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired JdbcTemplate jdbc;
    @Autowired ElasticsearchClient client;
    @Autowired ObjectMapper json;
    @Autowired RestaurantSearchProjectionService projections;
    @MockitoSpyBean RestaurantSearchOutboxMapper tasks;
    @MockitoBean LoginSessionService sessions;
    @MockitoBean com.ginkgo.foodmap.file.service.MediaFileService media;
    private String index;
    private RestaurantSearchSyncProperties properties;
    private RestaurantSearchIndexWriter writer;
    private RestaurantSearchSyncService sync;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        database="ginkgo_sync_it_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=connect("");var statement=connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");created=true;
        }
        registry.add("spring.datasource.url",()->url(database));
        registry.add("spring.datasource.username",()->System.getenv().getOrDefault("RESTAURANT_TEST_DB_USERNAME","root"));
        registry.add("spring.datasource.password",()->Objects.requireNonNull(System.getenv("RESTAURANT_TEST_DB_PASSWORD")));
        registry.add("app.search.elasticsearch.address",()->System.getProperty("ginkgo.es.address","127.0.0.1:9200"));
    }
    @BeforeEach void prepare() throws Exception {
        reset(tasks);
        jdbc.update("DELETE FROM restaurant_search_outbox");jdbc.update("DELETE FROM restaurants");
        jdbc.update("INSERT INTO restaurants(id,name,category_code) VALUES(101,'同步测试商户','OTHER')");
        index="ginkgo-sync-it-"+UUID.randomUUID().toString().replace("-","");
        properties=new RestaurantSearchSyncProperties();properties.setIndexAlias(index+"-alias");properties.setMaxAttempts(2);
        // 完整复用v002的显式mapping/IK设置，但删除正式别名定义，仅添加本次随机别名。
        String definition;
        try(var stream=getClass().getResourceAsStream("/elasticsearch/restaurant-search-v003.json")) {
            var mapping=json.readValue(stream,Map.class);mapping.remove("aliases");definition=json.writeValueAsString(mapping);
        }
        client.indices().create(b->b.index(index).withJson(new StringReader(definition))
                .aliases(properties.getIndexAlias(),a->a.isWriteIndex(true)));
        writer=new RestaurantSearchIndexWriter(client,json,properties);
        sync=new RestaurantSearchSyncService(tasks,projections,writer,properties);
    }
    @AfterEach void removeIndex() throws Exception {
        if(index!=null && index.matches("ginkgo-sync-it-[a-f0-9]{32}") && client.indices().exists(b->b.index(index)).value())
            client.indices().delete(b->b.index(index));
    }
    @AfterAll static void removeDatabase() throws Exception {
        if(created && database.matches("ginkgo_sync_it_[a-f0-9]{32}"))
            try(var connection=connect("");var statement=connection.createStatement()){statement.executeUpdate("DROP DATABASE `"+database+"`");}
    }
    @Test void syncsLatestVersionDuplicatesTombstonesAndRestore() throws Exception {
        tasks.insertCurrentVersion(101);var old=projections.assemble(java.util.List.of(101L)).getFirst();
        jdbc.update("UPDATE restaurants SET name='最新资料𠀀〇',search_projection_version=2 WHERE id=101");tasks.insertCurrentVersion(101);
        sync.processBatch();
        assertThat(count("SUCCEEDED")).isEqualTo(2);
        assertThat(stored().version()).isEqualTo(2);
        assertThat(stored().source().toJson().toString()).contains("最新资料");
        client.indices().refresh(b->b.index(index));
        for(String word:java.util.List.of("料","𠀀","〇")) {
            var found=client.search(b->b.index(properties.getIndexAlias()).query(q->q.term(t->t.field("name.chars").value(word))),JsonData.class);
            assertThat(found.hits().total().value()).isEqualTo(1);
        }
        jdbc.update("UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM',search_projection_version=3 WHERE id=101");
        tasks.insertCurrentVersion(101);sync.processBatch();
        var tombstone=json.readTree(stored().source().toJson().toString());
        assertThat(tombstone.size()).isEqualTo(3);assertThat(tombstone.get("deleted").asBoolean()).isTrue();
        jdbc.update("UPDATE restaurants SET deleted_at=NULL,delete_reason_code=NULL,search_projection_version=4 WHERE id=101");
        tasks.insertCurrentVersion(101);sync.processBatch();
        writer.write(old);assertThat(stored().version()).isEqualTo(4);
        var conflicting=projections.assemble(java.util.List.of(101L)).getFirst();conflicting.setName("同版本不同内容");
        assertThrows(IllegalStateException.class,()->writer.write(conflicting));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_search_outbox WHERE completed_at IS NOT NULL",Long.class)).isEqualTo(4);
    }
    @Test void badTaskDoesNotBlockOthersAndRetriesStopAtLimit() {
        jdbc.update("INSERT INTO restaurant_search_outbox(restaurant_id,projection_version) VALUES(999,1)");
        tasks.insertCurrentVersion(101);sync.processBatch();
        assertThat(count("SUCCEEDED")).isEqualTo(1);assertThat(count("PENDING")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM restaurant_search_outbox WHERE restaurant_id=999",Integer.class)).isEqualTo(1);
        sync.processBatch();
        assertThat(jdbc.queryForObject("SELECT attempts FROM restaurant_search_outbox WHERE restaurant_id=999",Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE restaurant_search_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE restaurant_id=999");
        sync.processBatch();sync.processBatch();
        assertThat(count("FAILED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM restaurant_search_outbox WHERE restaurant_id=999",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT last_error FROM restaurant_search_outbox WHERE restaurant_id=999",String.class)).isEqualTo("IllegalStateException");
    }
    @Test void missingAliasFailsWithoutAutomaticallyCreatingAnIndex() throws Exception {
        properties.setIndexAlias(index+"-missing");tasks.insertCurrentVersion(101);
        sync.processBatch();
        assertThat(count("PENDING")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM restaurant_search_outbox",Integer.class)).isEqualTo(1);
        assertThat(client.indices().exists(b->b.index(properties.getIndexAlias())).value()).isFalse();
    }

    @Test void ackFailureReplaysSameVersionWithoutConsumingFailureBudget() throws Exception {
        tasks.insertCurrentVersion(101);
        doThrow(new IllegalStateException("ack unavailable")).when(tasks).succeed(anyLong(),anyInt());
        sync.processBatch();assertThat(stored().version()).isEqualTo(1);assertThat(count("PENDING")).isEqualTo(1);
        reset(tasks);sync.processBatch();
        assertThat(count("SUCCEEDED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM restaurant_search_outbox",Integer.class)).isZero();
    }
    @Test void laterTaskIsNotAcknowledgedByCurrentBatchAndNetworkRunsWithoutTransaction() throws Exception {
        tasks.insertCurrentVersion(101);var observed=spy(writer);
        doAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            invocation.callRealMethod();
            jdbc.update("UPDATE restaurants SET search_projection_version=2 WHERE id=101");tasks.insertCurrentVersion(101);
            return null;
        }).when(observed).write(any());
        new RestaurantSearchSyncService(tasks,projections,observed,properties).processBatch();
        assertThat(count("SUCCEEDED")).isEqualTo(1);assertThat(count("PENDING")).isEqualTo(1);
        sync.processBatch();assertThat(stored().version()).isEqualTo(2);assertThat(count("SUCCEEDED")).isEqualTo(2);
    }
    private long count(String status){return jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_search_outbox WHERE status=?",Long.class,status);}
    private co.elastic.clients.elasticsearch.core.GetResponse<JsonData> stored() throws Exception {
        return client.get(new GetRequest.Builder().index(properties.getIndexAlias()).id("101").build(),JsonData.class);
    }
    private static String url(String name){
        String server=System.getenv().getOrDefault("RESTAURANT_TEST_DB_SERVER","jdbc:mysql://127.0.0.1:3306/");
        if(!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"))throw new IllegalArgumentException("Loopback database required");
        return server+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }
    private static Connection connect(String name) throws SQLException {return DriverManager.getConnection(url(name),System.getenv().getOrDefault("RESTAURANT_TEST_DB_USERNAME","root"),Objects.requireNonNull(System.getenv("RESTAURANT_TEST_DB_PASSWORD")));}
}
