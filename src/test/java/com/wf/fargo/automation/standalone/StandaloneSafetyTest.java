package com.wf.fargo.automation.standalone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wf.fargo.automation.AutomationServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

class StandaloneSafetyTest {

    @Test
    void localhostKafkaAndMongoAreAllowed() {
        assertThat(StandaloneSafety.localBootstrap("localhost:9092")).isTrue();
        assertThat(StandaloneSafety.localBootstrap("127.0.0.1:9092,localhost:9093")).isTrue();
        assertThat(StandaloneSafety.localMongo("mongodb://localhost:27017/fargo_automation?replicaSet=rs0")).isTrue();
    }

    @Test
    void remoteKafkaAndMongoAreRejected() {
        assertThat(StandaloneSafety.localBootstrap("kafka.internal.example:9092")).isFalse();
        assertThat(StandaloneSafety.localMongo("mongodb://mongo.internal.example:27017/fargo_automation")).isFalse();
    }

    @Test
    void standaloneStartupFailsWhenKafkaIsNotLocal() {
        assertThatThrownBy(() -> SpringApplication.run(
                        AutomationServiceApplication.class,
                        "--spring.profiles.active=standalone",
                        "--spring.kafka.bootstrap-servers=kafka.internal.example:9092",
                        "--spring.main.web-application-type=none",
                        "--spring.main.banner-mode=off"))
                .satisfies(error -> assertThat(text(error)).contains("non-local Kafka"));
    }

    private static String text(Throwable error) {
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        while (current != null && builder.length() < 4000) {
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return builder.toString();
    }
}
