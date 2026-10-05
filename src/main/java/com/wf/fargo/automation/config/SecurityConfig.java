package com.wf.fargo.automation.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<CallerAuthenticationFilter> callerAuthentication(AccessTokens tokens) {
        FilterRegistrationBean<CallerAuthenticationFilter> registration =
                new FilterRegistrationBean<>(new CallerAuthenticationFilter(tokens));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
