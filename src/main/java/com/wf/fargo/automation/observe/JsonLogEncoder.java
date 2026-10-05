package com.wf.fargo.automation.observe;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.common.LogSanitizer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class JsonLogEncoder extends EncoderBase<ILoggingEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        line.put("level", event.getLevel().toString());
        line.put("logger", event.getLoggerName());
        line.put("message", LogSanitizer.sanitize(event.getFormattedMessage()));
        Map<String, String> context = event.getMDCPropertyMap();
        if (context != null) {
            copy(line, context, LogContext.TRACE_ID);
            copy(line, context, LogContext.CUSTOMER_ID);
            copy(line, context, LogContext.AGENT_ID);
            copy(line, context, LogContext.RUN_ID);
        }
        if (event.getThrowableProxy() != null) {
            line.put("error", event.getThrowableProxy().getClassName());
        }
        try {
            return (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException ex) {
            return ("{\"message\":\"" + LogSanitizer.sanitize(event.getFormattedMessage()) + "\"}\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private static void copy(Map<String, Object> line, Map<String, String> context, String key) {
        String value = context.get(key);
        if (value != null && !value.isBlank()) {
            line.put(key, value);
        }
    }
}
