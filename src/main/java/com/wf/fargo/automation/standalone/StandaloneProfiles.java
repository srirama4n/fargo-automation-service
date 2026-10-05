package com.wf.fargo.automation.standalone;

import org.springframework.core.env.ConfigurableEnvironment;

final class StandaloneProfiles {

    private StandaloneProfiles() {}

    static boolean active(ConfigurableEnvironment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("standalone".equals(profile) || "test".equals(profile)) {
                return true;
            }
        }
        return false;
    }
}
