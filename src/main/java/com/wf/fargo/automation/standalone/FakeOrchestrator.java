package com.wf.fargo.automation.standalone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.config.AppProperties;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile({"standalone", "test"})
public class FakeOrchestrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FakeOrchestrator.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String LISTENER_ID = "fake-orchestrator";

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafka;
    private final AppProperties properties;
    private final CannedReplies replies;
    private final KafkaListenerEndpointRegistry registry;
    private final boolean consume;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fake-orchestrator");
        thread.setDaemon(true);
        return thread;
    });

    public FakeOrchestrator(
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafka,
            AppProperties properties,
            CannedReplies replies,
            KafkaListenerEndpointRegistry registry,
            Environment environment) {
        this.objectMapper = objectMapper;
        this.kafka = kafka;
        this.properties = properties;
        this.replies = replies;
        this.registry = registry;
        this.consume = standaloneOnly(environment);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!consume) {
            return;
        }
        MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
        if (container != null && !container.isRunning()) {
            container.start();
        }
    }

    @KafkaListener(
            id = LISTENER_ID,
            topics = "${automation.topics.orchestrator-input}",
            groupId = "fake-orchestrator",
            autoStartup = "false")
    public void onTask(String body, Acknowledgment acknowledgment) throws Exception {
        acknowledgment.acknowledge();
        Map<String, Object> task = objectMapper.readValue(body, MAP);
        long delay = Math.max(0, replies.delay().toMillis());
        scheduler.schedule(() -> publish(task), delay, TimeUnit.MILLISECONDS);
    }

    private void publish(Map<String, Object> task) {
        try {
            Map<String, Object> reply = replies.build(task);
            String key = reply.get("customerId") == null ? null : String.valueOf(reply.get("customerId"));
            kafka.send(properties.topics().automationReplies(), key, objectMapper.writeValueAsString(reply))
                    .get(10, TimeUnit.SECONDS);
            log.info("canned_reply phase={} taskId={}", reply.get("phase"), reply.get("taskId"));
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("canned_reply_failed error={}", ex.getClass().getSimpleName());
        }
    }

    static boolean standaloneOnly(Environment environment) {
        boolean standalone = false;
        boolean test = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("standalone".equals(profile)) {
                standalone = true;
            }
            if ("test".equals(profile)) {
                test = true;
            }
        }
        return standalone && !test;
    }
}
