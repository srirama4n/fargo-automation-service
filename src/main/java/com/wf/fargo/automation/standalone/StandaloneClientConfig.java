package com.wf.fargo.automation.standalone;

import com.wf.fargo.automation.outbox.PushClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("standalone & !test")
public class StandaloneClientConfig {

    @Bean
    @Primary
    PushClient pushClient(StubPushClient stubs) {
        return stubs::send;
    }
}
