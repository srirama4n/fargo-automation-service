package com.wf.fargo.automation.observe;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

@Component
public class RunTrace {

    private final Tracer tracer;

    public RunTrace(Tracer tracer) {
        this.tracer = tracer;
    }

    public String open() {
        Span span = tracer.nextSpan().name("automation.run").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return span.context().traceId();
        } finally {
            span.end();
        }
    }

    public String currentId() {
        Span span = tracer.currentSpan();
        if (span == null || span.context() == null) {
            return null;
        }
        String traceId = span.context().traceId();
        return traceId == null || traceId.isBlank() ? null : traceId;
    }

    public Scope scope(String traceId, String customerId, String agentId, String runId) {
        Span span = child(traceId);
        Tracer.SpanInScope spanScope = tracer.withSpan(span);
        String activeTrace = traceId == null || traceId.isBlank() ? span.context().traceId() : traceId;
        LogContext.put(activeTrace, customerId, agentId, runId);
        return new Scope(span, spanScope);
    }

    private Span child(String traceId) {
        if (traceId == null || traceId.length() != 32) {
            return tracer.nextSpan().name("automation.run").start();
        }
        TraceContext parent = tracer.traceContextBuilder()
                .traceId(traceId)
                .spanId(traceId.substring(0, 16))
                .sampled(true)
                .build();
        return tracer.spanBuilder().setParent(parent).name("automation.run").start();
    }

    public final class Scope implements AutoCloseable {

        private final Span span;
        private final Tracer.SpanInScope spanScope;

        private Scope(Span span, Tracer.SpanInScope spanScope) {
            this.span = span;
            this.spanScope = spanScope;
        }

        @Override
        public void close() {
            LogContext.clear();
            spanScope.close();
            span.end();
        }
    }
}
