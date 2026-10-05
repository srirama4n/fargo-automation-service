package com.wf.fargo.automation;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Applies {@code automation.dispatch.mode} only when the {@code bddA2a} task sets
 * {@code automation.it.dispatch-mode}. An empty change leaves the shared Spring test context intact.
 */
public class DispatchModeInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String PROPERTY = "automation.it.dispatch-mode";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        String mode = System.getProperty(PROPERTY);
        if (mode == null || mode.isBlank()) {
            return;
        }
        applicationContext.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("itDispatchMode", Map.of("automation.dispatch.mode", mode)));
    }
}
