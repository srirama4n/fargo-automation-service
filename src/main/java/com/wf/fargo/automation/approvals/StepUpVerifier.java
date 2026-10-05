package com.wf.fargo.automation.approvals;

import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.config.AppProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class StepUpVerifier {

    private static final Logger log = LoggerFactory.getLogger(StepUpVerifier.class);

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public StepUpVerifier(AppProperties properties) {
        Duration timeout = Duration.ofSeconds(5);
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(properties.cvams().baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.circuitBreaker = CircuitBreaker.of("cvams", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize(30)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .ignoreExceptions(ApiException.class)
                .build());
        this.retry = Retry.of("cvams", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(20))
                .retryOnException(error -> !(error instanceof ApiException)
                        && !(error instanceof CallNotPermittedException))
                .build());
    }

    public void reset() {
        circuitBreaker.reset();
    }

    public String verify(String token, String customerId, String approvalId) {
        if (token == null || token.isBlank()) {
            log.warn("security=true step_up_failed");
            throw new ApiException(401, "step_up_required", "A step-up token is required.");
        }
        StepUpResponse response;
        try {
            response = Retry.decorateSupplier(
                            retry, CircuitBreaker.decorateSupplier(circuitBreaker, () -> post(token, customerId, approvalId)))
                    .get();
        } catch (ApiException exception) {
            log.warn("security=true step_up_failed");
            throw exception;
        } catch (RuntimeException exception) {
            ApiException rejected = find(exception);
            if (rejected != null) {
                log.warn("security=true step_up_failed");
                throw rejected;
            }
            log.warn("step_up_unavailable");
            throw new ApiException(503, "step_up_unavailable", "Step-up verification is unavailable.");
        }
        if (response == null || !response.valid() || response.stepUpRef() == null || response.stepUpRef().isBlank()) {
            log.warn("security=true step_up_failed");
            throw new ApiException(401, "step_up_required", "Step-up verification failed.");
        }
        return response.stepUpRef();
    }

    private StepUpResponse post(String token, String customerId, String approvalId) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("customerId", customerId);
        body.put("approvalId", approvalId);
        return restClient.post()
                .uri("/step-up/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    drain(response);
                    throw new ApiException(401, "step_up_required", "Step-up verification failed.");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    drain(response);
                    throw new StepUpTransportException();
                })
                .body(StepUpResponse.class);
    }

    private static void drain(ClientHttpResponse response) throws IOException {
        try (InputStream body = response.getBody()) {
            body.readAllBytes();
        }
    }

    private static ApiException find(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ApiException api) {
                return api;
            }
            current = current.getCause();
        }
        return null;
    }

    private record StepUpResponse(boolean valid, String stepUpRef) {}

    private static final class StepUpTransportException extends RuntimeException {}
}
