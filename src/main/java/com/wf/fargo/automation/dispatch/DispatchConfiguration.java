package com.wf.fargo.automation.dispatch;

import com.wf.fargo.automation.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class DispatchConfiguration {

    @Bean
    @Primary
    TaskDispatcher taskDispatcher(
            AppProperties properties,
            KafkaOrchestratorDispatcher kafka,
            A2aSupervisorDispatcher a2a) {
        if ("a2a".equalsIgnoreCase(properties.dispatch().mode())) {
            return a2a;
        }
        return kafka;
    }
}
