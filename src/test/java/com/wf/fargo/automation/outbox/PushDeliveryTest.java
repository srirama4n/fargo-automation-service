package com.wf.fargo.automation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PushDeliveryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsThePushPayload() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        start(204, hits);
        PushClient client = new HttpPushClient(baseUrl());

        client.send(Map.of("customerId", "cust_1", "body", "Approved", "category", "agent_update"));

        assertThat(hits).hasValue(1);
        JsonNode json = objectMapper.readTree(lastBody.get());
        assertThat(json.get("customerId").asText()).isEqualTo("cust_1");
        assertThat(json.get("body").asText()).isEqualTo("Approved");
        assertThat(json.get("category").asText()).isEqualTo("agent_update");
    }

    @Test
    void circuitOpensAfterRepeatedPushFailures() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        start(500, hits);
        PushClient client = new HttpPushClient(baseUrl());

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> client.send(Map.of("customerId", "cust_1"))).isInstanceOf(RuntimeException.class);
        }
        int hitsBeforeOpen = hits.get();
        assertThatThrownBy(() -> client.send(Map.of("customerId", "cust_1")))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(hits).hasValue(hitsBeforeOpen);
    }

    private void start(int status, AtomicInteger hits) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
