package com.ginkgo.foodmap.restaurant;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.json.JsonData;
import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchIndexWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** 外部版本、别名约束及409核对必须显式验证，不能把所有冲突视为成功。 */
class RestaurantSearchIndexWriterTest {
    private final ElasticsearchClient client=mock(ElasticsearchClient.class);
    private final ObjectMapper json=new ObjectMapper();
    private final RestaurantSearchIndexWriter writer=new RestaurantSearchIndexWriter(client,json,new RestaurantSearchSyncProperties());
    @BeforeEach void prepare() throws Exception {
        var response=mock(IndexResponse.class);when(response.version()).thenReturn(3L);
        doReturn(response).when(client).index(any(IndexRequest.class));
    }
    @Test void usesStrictVersionAndExistingAliasWithMinimalTombstone() throws Exception {
        writer.write(RestaurantSearchSyncServiceTest.document(101,3));
        var capture=ArgumentCaptor.forClass(IndexRequest.class);verify(client).index(capture.capture());
        assertThat(capture.getValue().versionType()).isEqualTo(VersionType.External);
        assertThat(capture.getValue().requireAlias()).isTrue();
        assertThat(capture.getValue().id()).isEqualTo("101");
        assertThat(((JsonData)capture.getValue().document()).toJson().toString()).contains("\"deleted\":true").doesNotContain("name");
    }
    @Test void acceptsEqualBodyAndGreaterVersionButRejectsSameVersionDifferentBody() throws Exception {
        conflict(409);
        stored(3,"{\"id\":101,\"deleted\":true,\"projectionVersion\":3}");
        writer.write(RestaurantSearchSyncServiceTest.document(101,3));
        stored(4,"{\"id\":101,\"deleted\":false,\"projectionVersion\":4}");
        writer.write(RestaurantSearchSyncServiceTest.document(101,3));
        stored(3,"{\"id\":101,\"deleted\":false,\"projectionVersion\":3}");
        assertThrows(IllegalStateException.class,()->writer.write(RestaurantSearchSyncServiceTest.document(101,3)));
    }
    @Test void rejectsMismatchedSourceMetadataAndDoesNotSwallowNonConflictErrors() throws Exception {
        conflict(409);stored(4,"{\"id\":101,\"deleted\":true,\"projectionVersion\":3}");
        assertThrows(IllegalStateException.class,()->writer.write(RestaurantSearchSyncServiceTest.document(101,3)));
        conflict(503);
        assertThrows(ElasticsearchException.class,()->writer.write(RestaurantSearchSyncServiceTest.document(101,3)));
    }
    private void conflict(int status) throws Exception {
        doThrow(new ElasticsearchException("index",new ErrorResponse.Builder().status(status)
                .error(e->e.type("test").reason("test")).build())).when(client).index(any(IndexRequest.class));
    }
    private void stored(long version,String source) throws Exception {
        var response=GetResponse.of(b->b.index("test").id("101").found(true).version(version).source(JsonData.fromJson(source)));
        doReturn(response).when(client).get(any(GetRequest.class),eq(JsonData.class));
    }
}
