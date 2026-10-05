package com.wf.fargo.automation.observe;

import com.wf.fargo.automation.common.Hashing;
import org.slf4j.MDC;

public final class LogContext {

    public static final String TRACE_ID = "traceId";
    public static final String CUSTOMER_ID = "customerId";
    public static final String AGENT_ID = "agentId";
    public static final String RUN_ID = "runId";

    private LogContext() {}

    public static void put(String traceId, String customerId, String agentId, String runId) {
        if (present(traceId)) {
            MDC.put(TRACE_ID, traceId);
        }
        if (present(customerId)) {
            MDC.put(CUSTOMER_ID, Hashing.sha256(customerId));
        }
        if (present(agentId)) {
            MDC.put(AGENT_ID, agentId);
        }
        if (present(runId)) {
            MDC.put(RUN_ID, runId);
        }
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(CUSTOMER_ID);
        MDC.remove(AGENT_ID);
        MDC.remove(RUN_ID);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
