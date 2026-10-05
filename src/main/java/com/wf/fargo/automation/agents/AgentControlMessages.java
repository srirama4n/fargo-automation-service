package com.wf.fargo.automation.agents;

import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.domain.PersonalAgent;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AgentControlMessages {

    private final Clock clock;

    public AgentControlMessages(Clock clock) {
        this.clock = clock;
    }

    public Map<String, Object> message(String customerId, PersonalAgent agent, String op, boolean backfill) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("messageId", Ulids.next(clock.millis()));
        payload.put("occurredAt", clock.instant().toString());
        payload.put("op", op);
        payload.put("customerId", customerId);
        payload.put("agentId", agent.agentId());
        payload.put("agentVersion", agent.version() == null ? 0L : agent.version());
        payload.put("triggerType", agent.trigger() == null ? null : agent.trigger().type());
        payload.put("accountScopes", agent.trigger() == null || agent.trigger().accountScopes() == null
                ? List.of()
                : agent.trigger().accountScopes());
        payload.put("filters", agent.trigger() == null || agent.trigger().filters() == null
                ? Map.of()
                : agent.trigger().filters());
        if (agent.guards() != null) {
            Map<String, Object> guardPayload = new LinkedHashMap<>();
            if (agent.guards().oncePer() != null) {
                guardPayload.put("oncePer", agent.guards().oncePer());
            }
            if (agent.guards().achHold() != null) {
                guardPayload.put("achHold", agent.guards().achHold().toString());
            }
            payload.put("guards", guardPayload);
        }
        if ("upsert".equals(op)) {
            payload.put("backfill", backfill);
        }
        return payload;
    }
}
