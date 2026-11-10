package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.SimpleJsonpMapper;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchDocument;
import com.ginkgo.foodmap.restaurant.metrics.RestaurantSearchSyncMetrics;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchIndexWriter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 验证实际Bulk请求、逐项隔离、严格版本、UTF-8切批和传输结果未知时的安全边界。 */
class RestaurantSearchIndexWriterTest {
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final RestaurantSearchSyncProperties properties = new RestaurantSearchSyncProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RestaurantSearchSyncMetrics metrics =
            new RestaurantSearchSyncMetrics(
                    meters,
                    mock(com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchOutboxMapper.class),
                    properties);
    private final RestaurantSearchIndexWriter writer =
            new RestaurantSearchIndexWriter(client, new ObjectMapper(), properties, metrics);

    @BeforeEach
    void prepare() throws Exception {
        metrics.initialize();
        when(client._jsonpMapper()).thenReturn(SimpleJsonpMapper.INSTANCE);
        when(client.bulk(any(BulkRequest.class)))
                .thenAnswer(
                        invocation -> {
                            BulkRequest request = invocation.getArgument(0);
                            return response(
                                    request.operations().stream()
                                            .map(
                                                    operation -> {
                                                        var index = operation.index();
                                                        return item(
                                                                index.id(), index.version(), 201);
                                                    })
                                            .toList());
                        });
    }

    @Test
    void sendsMultipleRestaurantsInOneRequestWithStrictVersionsAndAlias() {
        var results = writer.writeBatch(List.of(document(101, 3), document(102, 4)));
        assertThat(results.values()).allMatch(result -> result.failure() == null);
        var capture = ArgumentCaptor.forClass(BulkRequest.class);
        try {
            verify(client).bulk(capture.capture());
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        var request = capture.getValue();
        assertThat(request.requireAlias()).isTrue();
        assertThat(request.refresh()).isNull();
        assertThat(request.operations()).hasSize(2);
        assertThat(request.operations().getFirst().index().versionType())
                .isEqualTo(VersionType.External);
        assertThat(request.operations().getFirst().index().index()).isEqualTo("restaurant-search");
        assertThat(request.operations().getFirst().index().id()).isEqualTo("101");
        assertThat(request.operations().getFirst().index().version()).isEqualTo(3);
        assertThat(
                        ((JsonData) request.operations().getFirst().index().document())
                                .toJson()
                                .toString())
                .contains("\"deleted\":true")
                .doesNotContain("name");
        assertThat(
                        meters.get("restaurant.search.sync.bulk.requests")
                                .tag("outcome", "response")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("restaurant.search.sync.bulk.documents").summary().totalAmount())
                .isEqualTo(2);
    }

    @Test
    void partialFailureDoesNotDiscardSuccessfulItems() throws Exception {
        when(client.bulk(any(BulkRequest.class)))
                .thenReturn(
                        response(
                                List.of(
                                        item("101", 3, 201),
                                        item("102", 1, 429),
                                        item("103", 1, 400))));
        var results =
                writer.writeBatch(List.of(document(101, 3), document(102, 1), document(103, 1)));
        assertThat(results.get(101L).failure()).isNull();
        assertThat(results.get(102L).failure()).hasMessageContaining("429");
        assertThat(results.get(103L).failure()).hasMessageContaining("400");
        assertThat(
                        meters.get("restaurant.search.sync.bulk.items")
                                .tag("outcome", "success")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.sync.bulk.items")
                                .tag("outcome", "error")
                                .counter()
                                .count())
                .isEqualTo(2);
    }

    @Test
    void acceptsEqualBodyAndGreaterVersionButRejectsSameVersionDifferentBody() throws Exception {
        when(client.bulk(any(BulkRequest.class)))
                .thenReturn(response(List.of(item("101", 3, 409))));
        stored(3, "{\"id\":101,\"deleted\":true,\"projectionVersion\":3}");
        writer.write(document(101, 3));
        stored(4, "{\"id\":101,\"deleted\":false,\"projectionVersion\":4}");
        writer.write(document(101, 3));
        stored(3, "{\"id\":101,\"deleted\":false,\"projectionVersion\":3}");
        assertThrows(IllegalStateException.class, () -> writer.write(document(101, 3)));
        assertThat(meters.get("restaurant.search.sync.version.conflicts").counter().count())
                .isEqualTo(3);
    }

    @Test
    void conflictVerificationFailureDoesNotBlockOtherItems() throws Exception {
        when(client.bulk(any(BulkRequest.class)))
                .thenReturn(response(List.of(item("101", 3, 409), item("102", 1, 201))));
        when(client.get(any(GetRequest.class), eq(JsonData.class)))
                .thenThrow(new IOException("lookup timeout"));
        var results = writer.writeBatch(List.of(document(101, 3), document(102, 1)));
        assertThat(results.get(101L).failure()).isInstanceOf(IOException.class);
        assertThat(results.get(102L).failure()).isNull();
    }

    @Test
    void rejectsMismatchedSourceMetadataAndMissingConflictDocument() throws Exception {
        when(client.bulk(any(BulkRequest.class)))
                .thenReturn(response(List.of(item("101", 3, 409))));
        stored(4, "{\"id\":101,\"deleted\":true,\"projectionVersion\":3}");
        assertThrows(IllegalStateException.class, () -> writer.write(document(101, 3)));
        when(client.get(any(GetRequest.class), eq(JsonData.class)))
                .thenReturn(GetResponse.of(b -> b.index("test").id("101").found(false)));
        assertThrows(IllegalStateException.class, () -> writer.write(document(101, 3)));
    }

    @Test
    void wholeRequestTimeoutLeavesEveryItemUnconfirmedAndCanReplay() throws Exception {
        doThrow(new IOException("response lost")).when(client).bulk(any(BulkRequest.class));
        var results = writer.writeBatch(List.of(document(101, 3), document(102, 1)));
        assertThat(results.values()).allMatch(result -> result.failure() instanceof IOException);
        assertThat(
                        meters.get("restaurant.search.sync.bulk.requests")
                                .tag("outcome", "error")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.sync.bulk.items")
                                .tag("outcome", "error")
                                .counter()
                                .count())
                .isEqualTo(2);
    }

    @Test
    void rejectsMalformedResponseCountIdentityAndVersion() throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenReturn(response(List.of()));
        assertThat(writer.writeBatch(List.of(document(101, 3))).get(101L).failure()).isNotNull();
        when(client.bulk(any(BulkRequest.class)))
                .thenReturn(response(List.of(item("999", 3, 201))));
        assertThat(writer.writeBatch(List.of(document(101, 3))).get(101L).failure()).isNotNull();
        when(client.bulk(any(BulkRequest.class)))
                .thenReturn(response(List.of(item("101", 2, 201))));
        assertThat(writer.writeBatch(List.of(document(101, 3))).get(101L).failure()).isNotNull();
    }

    @Test
    void splitsOnUtf8NdjsonBytesAndContinuesAfterFailedChunk() throws Exception {
        properties.setBulkMaxBytes(1024);
        var first = document(101, 3);
        first.setName("餐".repeat(180));
        var second = document(102, 1);
        second.setName("厅".repeat(180));
        var results = writer.writeBatch(List.of(first, second));
        assertThat(results.values()).allMatch(result -> result.failure() == null);
        var capture = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, times(2)).bulk(capture.capture());
        for (var request : capture.getAllValues()) {
            assertThat(request.operations()).hasSize(1);
            long bytes = 0;
            for (var operation : request.operations()) {
                var lines = operation._serializables();
                while (lines.hasNext())
                    bytes +=
                            JsonpUtils.toJsonString(lines.next(), client._jsonpMapper())
                                            .getBytes(StandardCharsets.UTF_8)
                                            .length
                                    + 1L;
            }
            assertThat(bytes).isLessThanOrEqualTo(1024);
        }
        when(client.bulk(any(BulkRequest.class)))
                .thenThrow(new IOException("first chunk timeout"))
                .thenReturn(response(List.of(item("102", 1, 201))));
        results = writer.writeBatch(List.of(first, second));
        assertThat(results.get(101L).failure()).isInstanceOf(IOException.class);
        assertThat(results.get(102L).failure()).isNull();
    }

    @Test
    void isolatesOversizedAndInvalidDocumentsAndSkipsEmptyBatch() throws Exception {
        properties.setBulkMaxBytes(1024);
        var large = document(101, 3);
        large.setName("餐".repeat(1000));
        var invalid = document(103, 0);
        var results = writer.writeBatch(List.of(large, document(102, 1), invalid));
        assertThat(results.get(101L).failure()).isInstanceOf(IllegalArgumentException.class);
        assertThat(results.get(103L).failure()).isInstanceOf(IllegalArgumentException.class);
        assertThat(results.get(102L).failure()).isNull();
        clearInvocations(client);
        assertThat(writer.writeBatch(List.of())).isEmpty();
        verifyNoInteractions(client);
    }

    private RestaurantSearchDocument document(long id, long version) {
        return RestaurantSearchSyncServiceTest.document(id, version);
    }

    private BulkResponse response(List<BulkResponseItem> items) {
        return BulkResponse.of(
                b ->
                        b.took(1)
                                .errors(items.stream().anyMatch(item -> item.status() >= 400))
                                .items(items));
    }

    private BulkResponseItem item(String id, long version, int status) {
        return BulkResponseItem.of(
                b ->
                        b.operationType(
                                        co.elastic.clients.elasticsearch.core.bulk.OperationType
                                                .Index)
                                .index("test")
                                .id(id)
                                .version(version)
                                .status(status));
    }

    private void stored(long version, String source) throws Exception {
        when(client.get(any(GetRequest.class), eq(JsonData.class)))
                .thenReturn(
                        GetResponse.of(
                                b ->
                                        b.index("test")
                                                .id("101")
                                                .found(true)
                                                .version(version)
                                                .source(JsonData.fromJson(source))));
    }
}
