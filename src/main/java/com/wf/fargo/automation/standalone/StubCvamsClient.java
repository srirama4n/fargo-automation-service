package com.wf.fargo.automation.standalone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

@Profile({"standalone", "test"})
public class StubCvamsClient implements EnvironmentPostProcessor, Ordered {

    static final String ACCEPTED_TOKEN = "dev-stepup-ok";

    private static volatile HttpServer server;

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!StandaloneSafety.profile(environment, "standalone") || StandaloneSafety.profile(environment, "test")) {
            return;
        }
        HttpServer started = start();
        int port = started.getAddress().getPort();
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("automation.cvams.base-url", "http://127.0.0.1:" + port);
        environment.getPropertySources().addFirst(new MapPropertySource("standaloneStubCvams", overrides));
    }

    private static synchronized HttpServer start() {
        if (server != null) {
            return server;
        }
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ObjectMapper objectMapper = new ObjectMapper();
            created.createContext("/step-up/verify", exchange -> {
                byte[] request = exchange.getRequestBody().readAllBytes();
                boolean accepted = false;
                try {
                    JsonNode body = objectMapper.readTree(request);
                    accepted = ACCEPTED_TOKEN.equals(body.path("token").asText());
                } catch (Exception ignored) {
                    accepted = false;
                }
                byte[] response = (accepted
                                ? "{\"valid\":true,\"stepUpRef\":\"stepup_dev\"}"
                                : "{\"valid\":false}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(accepted ? 200 : 401, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            created.start();
            server = created;
            return created;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not start the standalone cvams stub", ex);
        }
    }
}
