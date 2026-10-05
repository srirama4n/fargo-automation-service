package com.wf.fargo.automation.outbox;

import com.wf.fargo.automation.config.AppProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Profile({"!standalone", "test"})
public class HttpPushClient implements PushClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    @Autowired
    public HttpPushClient(AppProperties properties) {
        this(properties.push().baseUrl());
    }

    public HttpPushClient(String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.circuitBreaker = CircuitBreaker.of("push", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize(30)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        this.retry = Retry.of("push", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(20))
                .retryOnException(error -> !(error instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException))
                .build());
    }

    @Override
    public void send(Map<String, Object> payload) {
        Supplier<Void> call = CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
            restClient.post()
                    .uri("/")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
        Retry.decorateSupplier(retry, call).get();
    }
}
