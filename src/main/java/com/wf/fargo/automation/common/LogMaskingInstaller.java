package com.wf.fargo.automation.common;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LogMaskingInstaller {

    @PostConstruct
    public void install() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        for (TurboFilter filter : context.getTurboFilterList()) {
            if (filter instanceof LogMaskingFilter) {
                return;
            }
        }
        LogMaskingFilter filter = new LogMaskingFilter();
        filter.setContext(context);
        filter.start();
        context.addTurboFilter(filter);
    }
}
