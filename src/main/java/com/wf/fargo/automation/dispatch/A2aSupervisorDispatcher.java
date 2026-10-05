package com.wf.fargo.automation.dispatch;

import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.outbox.A2aTaskDeliverer;
import com.wf.fargo.automation.replies.ReplyHandler;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class A2aSupervisorDispatcher implements TaskDispatcher, A2aTaskDeliverer {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final ReplyHandler replyHandler;
    private final Clock clock;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ObjectProvider<CannedA2aReplies> cannedReplies;

    public A2aSupervisorDispatcher(
            AppProperties properties,
            @Lazy ReplyHandler replyHandler,
            Clock clock,
            ObjectProvider<CannedA2aReplies> cannedReplies) {
        this.cannedReplies = cannedReplies;
        Duration timeout = properties.a2a().timeout();
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(properties.a2a().supervisorUrl())
                .requestFactory(requestFactory)
                .build();
        this.replyHandler = replyHandler;
        this.clock = clock;
        this.circuitBreaker = CircuitBreaker.of("a2a", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize(30)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        this.retry = Retry.of("a2a", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(20))
                .retryOnException(error -> !(error instanceof CallNotPermittedException) && !timedOut(error))
                .build());
    }

    @Override
    public OutboxDestination destination() {
        return OutboxDestination.A2A_TASK;
    }

    @Override
    public void deliver(OutboxMessage message) {
        send(message.payload() == null ? Map.of() : message.payload());
    }

    public void send(Map<String, Object> task) {
        CannedA2aReplies canned = cannedReplies.getIfAvailable();
        if (canned != null && canned.active()) {
            replyHandler.handle(canned.replyTo(task));
            return;
        }
        Supplier<Map<String, Object>> call = CircuitBreaker.decorateSupplier(circuitBreaker, () -> post(task));
        try {
            Map<String, Object> reply = Retry.decorateSupplier(retry, call).get();
            replyHandler.handle(reply == null ? Map.of() : reply);
        } catch (RuntimeException ex) {
            if (timedOut(ex)) {
                replyHandler.handle(timeoutReply(task));
                return;
            }
            throw ex;
        }
    }

    private Map<String, Object> post(Map<String, Object> task) {
        return restClient.post()
                .uri("/")
                .contentType(MediaType.APPLICATION_JSON)
                .body(task)
                .retrieve()
                .body(MAP);
    }

    private Map<String, Object> timeoutReply(Map<String, Object> task) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "a2a_timeout");
        error.put("message", "The supervisor did not answer in time.");
        error.put("retryable", true);
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("schemaVersion", 1);
        reply.put("messageId", Ulids.next(clock.millis()));
        reply.put("occurredAt", clock.instant().toString());
        reply.put("taskId", task.get("taskId"));
        reply.put("runId", task.get("runId"));
        reply.put("customerId", task.get("customerId"));
        reply.put("phase", task.get("phase"));
        reply.put("status", "error");
        reply.put("quote", null);
        reply.put("outcome", null);
        reply.put("error", error);
        return reply;
    }

    private static boolean timedOut(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timed out")) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }
}
