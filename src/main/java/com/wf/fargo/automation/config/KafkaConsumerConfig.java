package com.wf.fargo.automation.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.wf.fargo.automation.runs.PoisonMessageException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 5L));
        handler.setBackOffFunction((record, exception) -> poison(exception)
                ? new FixedBackOff(200L, 5L)
                : new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        return handler;
    }

    private static boolean poison(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof PoisonMessageException
                    || current instanceof JsonProcessingException
                    || current instanceof DeserializationException) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }
}
