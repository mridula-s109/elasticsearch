/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.upgrades;

import com.carrotsearch.randomizedtesting.annotations.Name;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.time.DateFormatter;
import org.elasticsearch.common.time.FormatNames;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.ClassRule;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class LogsIndexModeRollingUpgradeIT extends AbstractRollingUpgradeTestCase {

    private static final String USER = "test_admin";
    private static final String PASS = "x-pack-test-password";

    @ClassRule()
    public static final ElasticsearchCluster cluster = ElasticsearchCluster.local()
        .distribution(DistributionType.DEFAULT)
        .module("constant-keyword")
        .module("data-streams")
        .module("mapper-extras")
        .module("x-pack-aggregate-metric")
        .module("x-pack-stack")
        .setting("xpack.security.autoconfiguration.enabled", "false")
        .user(USER, PASS)
        .setting("xpack.license.self_generated.type", initTestSeed().nextBoolean() ? "trial" : "basic")
        // We upgrade from standard to logsdb, so we need to start with logsdb disabled,
        // then later cluster.logsdb.enabled gets set to true and next rollover data stream is in logsdb mode.
        .setting("cluster.logsdb.enabled", "false")
        .setting("stack.templates.enabled", "false")
        .build();

    public LogsIndexModeRollingUpgradeIT(@Name("upgradedNodes") int upgradedNodes) {
        super(upgradedNodes);
    }

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }

    protected Settings restClientSettings() {
        String token = basicAuthHeaderValue(USER, new SecureString(PASS.toCharArray()));
        return Settings.builder().put(super.restClientSettings()).put(ThreadContext.PREFIX + ".Authorization", token).build();
    }

    private static final String BULK_INDEX_REQUEST = """
        { "create": {} }
        { "@timestamp": "%s", "host.name": "%s", "method": "%s", "ip.address": "%s", "message": "%s" }
        """;

    private static final String STANDARD_TEMPLATE = """
        {
          "index_patterns": [ "logs-*-*" ],
          "data_stream": {},
          "priority": 500,
          "template": {
            "mappings": {
              "properties": {
                "@timestamp" : {
                  "type": "date"
                },
                "host.name": {
                  "type": "keyword"
                },
                "method": {
                  "type": "keyword"
                },
                "message": {
                  "type": "text"
                },
                "ip.address": {
                  "type": "ip"
                }
              }
            }
          }
        }""";

    public void testLogsIndexing() throws IOException {
        if (isOldCluster()) {
            assertOK(client().performRequest(putTemplate(client(), "logs-template", STANDARD_TEMPLATE)));
            assertOK(client().performRequest(createDataStream("logs-apache-production")));
            final Response bulkIndexResponse = client().performRequest(bulkIndex("logs-apache-production", () -> {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < randomIntBetween(10, 20); i++) {
                    sb.append(
                        String.format(
                            BULK_INDEX_REQUEST,
                            DateFormatter.forPattern(FormatNames.DATE_TIME.getName()).format(Instant.now()),
                            randomFrom("foo", "bar"),
                            randomFrom("PUT", "POST", "GET"),
                            InetAddresses.toAddrString(randomIp(randomBoolean())),
                            randomIntBetween(20, 50)
                        )
                    );
                    sb.append("\n");
                }
                return sb.toString();
            }));
            assertOK(bulkIndexResponse);
            assertThat(entityAsMap(bulkIndexResponse).get("errors"), Matchers.is(false));
        } else if (isMixedCluster()) {
            assertOK(client().performRequest(rolloverDataStream(client(), "logs-apache-production")));
            final Response bulkIndexResponse = client().performRequest(bulkIndex("logs-apache-production", () -> {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < randomIntBetween(10, 20); i++) {
                    sb.append(
                        String.format(
                            BULK_INDEX_REQUEST,
                            DateFormatter.forPattern(FormatNames.DATE_TIME.getName()).format(Instant.now()),
                            randomFrom("foo", "bar"),
                            randomFrom("PUT", "POST", "GET"),
                            InetAddresses.toAddrString(randomIp(randomBoolean())),
                            randomIntBetween(20, 50)
                        )
                    );
                    sb.append("\n");
                }
                return sb.toString();
            }));
            assertOK(bulkIndexResponse);
            assertThat(entityAsMap(bulkIndexResponse).get("errors"), Matchers.is(false));
        } else if (isUpgradedCluster()) {
            enableLogsdbByDefault();
            assertOK(client().performRequest(rolloverDataStream(client(), "logs-apache-production")));
            final Response bulkIndexResponse = client().performRequest(bulkIndex("logs-apache-production", () -> {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < randomIntBetween(10, 20); i++) {
                    sb.append(
                        String.format(
                            BULK_INDEX_REQUEST,
                            DateFormatter.forPattern(FormatNames.DATE_TIME.getName()).format(Instant.now()),
                            randomFrom("foo", "bar"),
                            randomFrom("PUT", "POST", "GET"),
                            InetAddresses.toAddrString(randomIp(randomBoolean())),
                            randomIntBetween(20, 50)
                        )
                    );
                    sb.append("\n");
                }
                return sb.toString();
            }));
            assertOK(bulkIndexResponse);
            assertThat(entityAsMap(bulkIndexResponse).get("errors"), Matchers.is(false));

            assertIndexSettings(0, Matchers.nullValue());
            assertIndexSettings(1, Matchers.nullValue());
            assertIndexSettings(2, Matchers.nullValue());
            assertIndexSettings(3, Matchers.equalTo("logsdb"));
        }
    }

    static void enableLogsdbByDefault() throws IOException {
        var request = new Request("PUT", "/_cluster/settings");
        request.setJsonEntity("""
            {
                "persistent": {
                    "cluster.logsdb.enabled": true
                }
            }
            """);
        assertOK(client().performRequest(request));
    }

    private void assertIndexSettings(int backingIndex, final Matcher<Object> indexModeMatcher) throws IOException {
        assertThat(
            getSettings(client(), getWriteBackingIndex(client(), "logs-apache-production", backingIndex)).get("index.mode"),
            indexModeMatcher
        );
    }

    private static Request createDataStream(final String dataStreamName) {
        return new Request("PUT", "/_data_stream/" + dataStreamName);
    }

    private static Request bulkIndex(final String dataStreamName, final Supplier<String> bulkIndexRequestSupplier) {
        final Request request = new Request("POST", dataStreamName + "/_bulk");
        request.setJsonEntity(bulkIndexRequestSupplier.get());
        request.addParameter("refresh", "true");
        return request;
    }

    private static Request putTemplate(final RestClient client, final String templateName, final String mappings) throws IOException {
        final Request request = new Request("PUT", "/_index_template/" + templateName);
        request.setJsonEntity(mappings);
        return request;
    }

    private static Request rolloverDataStream(final RestClient client, final String dataStreamName) throws IOException {
        return new Request("POST", "/" + dataStreamName + "/_rollover");
    }

    @SuppressWarnings("unchecked")
    static String getWriteBackingIndex(final RestClient client, final String dataStreamName, int backingIndex) throws IOException {
        final Request request = new Request("GET", "_data_stream/" + dataStreamName);
        final List<Object> dataStreams = (List<Object>) entityAsMap(client.performRequest(request)).get("data_streams");
        final Map<String, Object> dataStream = (Map<String, Object>) dataStreams.get(0);
        final List<Map<String, String>> backingIndices = (List<Map<String, String>>) dataStream.get("indices");
        return backingIndices.get(backingIndex).get("index_name");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getSettings(final RestClient client, final String indexName) throws IOException {
        final Request request = new Request("GET", "/" + indexName + "/_settings?flat_settings");
        return ((Map<String, Map<String, Object>>) entityAsMap(client.performRequest(request)).get(indexName)).get("settings");
    }
}
