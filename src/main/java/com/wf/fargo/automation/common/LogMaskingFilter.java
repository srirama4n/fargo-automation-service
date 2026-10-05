package com.wf.fargo.automation.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

public class LogMaskingFilter extends TurboFilter {

    private static final ThreadLocal<Boolean> REENTRY = ThreadLocal.withInitial(() -> false);

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable throwable) {
        if (format == null || level == null || REENTRY.get()) {
            return FilterReply.NEUTRAL;
        }
        try {
            String sanitized = LogSanitizer.sanitize(format);
            Object[] sanitizedParams = sanitizeParams(params);
            if (!sanitized.equals(format) || sanitizedParams != params) {
                REENTRY.set(true);
                try {
                    logger.log(
                            marker,
                            LogMaskingFilter.class.getName(),
                            Level.toLocationAwareLoggerInteger(level),
                            sanitized,
                            sanitizedParams,
                            throwable);
                } finally {
                    REENTRY.set(false);
                }
                return FilterReply.DENY;
            }
            return FilterReply.NEUTRAL;
        } catch (RuntimeException ex) {
            return FilterReply.DENY;
        }
    }

    private static Object[] sanitizeParams(Object[] params) {
        if (params == null) {
            return null;
        }
        Object[] cleaned = null;
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            Object replacement = value;
            if (value instanceof String text) {
                String sanitized = LogSanitizer.sanitize(text);
                if (!sanitized.equals(text)) {
                    replacement = sanitized;
                }
            } else if (value != null && !(value instanceof Throwable)) {
                String rendered = String.valueOf(value);
                String sanitized = LogSanitizer.sanitize(rendered);
                if (!sanitized.equals(rendered)) {
                    replacement = sanitized;
                }
            }
            if (replacement != value) {
                if (cleaned == null) {
                    cleaned = params.clone();
                }
                cleaned[i] = replacement;
            }
        }
        return cleaned == null ? params : cleaned;
    }
}
