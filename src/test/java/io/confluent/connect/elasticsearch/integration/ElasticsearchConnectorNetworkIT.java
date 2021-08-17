package io.confluent.connect.elasticsearch.integration;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.confluent.connect.elasticsearch.ElasticsearchSinkConnector;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.storage.StringConverter;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.BATCH_SIZE_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.CONNECTION_URL_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.IGNORE_KEY_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.IGNORE_SCHEMA_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.MAX_IN_FLIGHT_REQUESTS_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.MAX_RETRIES_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.READ_TIMEOUT_MS_CONFIG;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.RETRY_BACKOFF_MS_CONFIG;
import static org.apache.kafka.connect.json.JsonConverterConfig.SCHEMAS_ENABLE_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.*;
import static org.apache.kafka.connect.runtime.ConnectorConfig.VALUE_CONVERTER_CLASS_CONFIG;
import static org.apache.kafka.connect.runtime.SinkConnectorConfig.TOPICS_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class ElasticsearchConnectorNetworkIT extends BaseConnectorIT {

  @Rule
  public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort(), false);

  protected static final int NUM_RECORDS = 5;
  protected static final int TASKS_MAX = 1;

  protected static final String CONNECTOR_NAME = "es-connector";
  protected static final String TOPIC = "test";
  protected Map<String, String> props;

  @Before
  public void setup() {
    startConnect();
    connect.kafka().createTopic(TOPIC);
    props = createProps();

    stubFor(any(anyUrl()).atPriority(10).willReturn(ok()));
  }

  @After
  public void cleanup() {
    stopConnect();
  }

  @Test
  public void testReadTimeout() throws Exception {
    props.put(READ_TIMEOUT_MS_CONFIG, "1000");
    props.put(MAX_RETRIES_CONFIG, "2");
    props.put(RETRY_BACKOFF_MS_CONFIG, "10");
    props.put(BATCH_SIZE_CONFIG, "1");
    props.put(MAX_IN_FLIGHT_REQUESTS_CONFIG, Integer.toString(NUM_RECORDS - 1));

    wireMockRule.stubFor(post(urlPathEqualTo("/_bulk"))
            .willReturn(ok().withFixedDelay(2_000)));

    connect.configureConnector(CONNECTOR_NAME, props);
    waitForConnectorToStart(CONNECTOR_NAME, TASKS_MAX);
    writeRecords(NUM_RECORDS);

    // Connector should fail since the request takes longer than request timeout
    await().atMost(Duration.ofMinutes(1)).untilAsserted(() ->
            assertThat(connect.connectorStatus(CONNECTOR_NAME).tasks().get(0).state())
                    .isEqualTo("FAILED"));

    assertThat(connect.connectorStatus(CONNECTOR_NAME).tasks().get(0).trace())
            .contains("Failed to execute bulk request due to 'java.net.SocketTimeoutException: " +
                    "1,000 milliseconds timeout on connection")
            .contains("after 3 attempt(s)");
  }

  @Test
  public void testTooManyRequests() throws Exception {
    props.put(READ_TIMEOUT_MS_CONFIG, "1000");
    props.put(MAX_RETRIES_CONFIG, "2");
    props.put(RETRY_BACKOFF_MS_CONFIG, "10");
    props.put(BATCH_SIZE_CONFIG, "1");
    props.put(MAX_IN_FLIGHT_REQUESTS_CONFIG, Integer.toString(NUM_RECORDS - 1));

    wireMockRule.stubFor(post(urlPathEqualTo("/_bulk"))
            .willReturn(aResponse()
                    .withStatus(429)
                    .withHeader(CONTENT_TYPE, "application/json")
                    .withBody("{\n" +
                    "  \"error\": {\n" +
                    "    \"type\": \"circuit_breaking_exception\",\n" +
                    "    \"reason\": \"Data too large\",\n" +
                    "    \"bytes_wanted\": 123848638,\n" +
                    "    \"bytes_limit\": 123273216,\n" +
                    "    \"durability\": \"TRANSIENT\"\n" +
                    "  },\n" +
                    "  \"status\": 429\n" +
                    "}")));

    connect.configureConnector(CONNECTOR_NAME, props);
    waitForConnectorToStart(CONNECTOR_NAME, TASKS_MAX);
    writeRecords(NUM_RECORDS);

    // Connector should fail since the request takes longer than request timeout
    await().atMost(Duration.ofMinutes(1)).untilAsserted(() ->
            assertThat(connect.connectorStatus(CONNECTOR_NAME).tasks().get(0).state())
                    .isEqualTo("FAILED"));

    assertThat(connect.connectorStatus(CONNECTOR_NAME).tasks().get(0).trace())
            .contains("Failed to execute bulk request due to 'ElasticsearchStatusException" +
                    "[Elasticsearch exception [type=circuit_breaking_exception, " +
                    "reason=Data too large]]' after 3 attempt(s)");
  }

  protected Map<String, String> createProps() {
    Map<String, String> props = new HashMap<>();

    // generic configs
    props.put(CONNECTOR_CLASS_CONFIG, ElasticsearchSinkConnector.class.getName());
    props.put(TOPICS_CONFIG, TOPIC);
    props.put(TASKS_MAX_CONFIG, Integer.toString(TASKS_MAX));
    props.put(KEY_CONVERTER_CLASS_CONFIG, StringConverter.class.getName());
    props.put(VALUE_CONVERTER_CLASS_CONFIG, JsonConverter.class.getName());
    props.put("value.converter." + SCHEMAS_ENABLE_CONFIG, "false");

    // connectors specific
    props.put(CONNECTION_URL_CONFIG, wireMockRule.url("/"));
    props.put(IGNORE_KEY_CONFIG, "true");
    props.put(IGNORE_SCHEMA_CONFIG, "true");

    return props;
  }

  protected void writeRecords(int numRecords) {
    for (int i = 0; i < numRecords; i++) {
      connect.kafka().produce(TOPIC, String.valueOf(i), String.format("{\"doc_num\":%d}", i));
    }
  }

}