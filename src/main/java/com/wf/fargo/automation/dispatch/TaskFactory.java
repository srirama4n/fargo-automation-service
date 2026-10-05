package com.wf.fargo.automation.dispatch;

import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.observe.RunTrace;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TaskFactory {

    private final Clock clock;
    private final RunTrace traces;

    public TaskFactory(Clock clock, RunTrace traces) {
        this.clock = clock;
        this.traces = traces;
    }

    public Map<String, Object> preview(AgentRun run, PersonalAgent agent, AgentTemplate template, String taskId) {
        Map<String, Object> params = agent.params() == null ? Map.of() : new LinkedHashMap<>(agent.params());
        return task(run, agent.customerId(), template.action().previewIntent(), "preview", params, null, taskId);
    }

    public Map<String, Object> execute(
            AgentRun run, PersonalAgent agent, AgentTemplate template, String taskId, String quoteId) {
        return task(run, agent.customerId(), template.action().executeIntent(), "execute", Map.of(), quoteId, taskId);
    }

    private Map<String, Object> task(
            AgentRun run,
            String customerId,
            String intent,
            String phase,
            Map<String, Object> params,
            String quoteId,
            String taskId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", 1);
        body.put("messageId", Ulids.next(clock.millis()));
        body.put("occurredAt", clock.instant().toString());
        body.put("channel", "automation");
        body.put("taskId", taskId);
        body.put("runId", run.runId());
        body.put("customerId", customerId);
        body.put("agentId", run.agentId());
        body.put("phase", phase);
        body.put("intent", intent);
        body.put("params", params);
        body.put("quoteId", quoteId);
        body.put("traceId", traceId(run));
        return body;
    }

    private String traceId(AgentRun run) {
        if (run.traceId() != null && !run.traceId().isBlank()) {
            return run.traceId();
        }
        String current = traces.currentId();
        if (current != null && !current.isBlank()) {
            return current;
        }
        return traces.open();
    }
}
