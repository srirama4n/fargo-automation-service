package com.wf.fargo.automation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class TestClockConfig {

    @Bean
    @Primary
    AdjustableClock adjustableClock() {
        return new AdjustableClock();
    }
}
