package com.wf.fargo.automation.dispatch;

import com.wf.fargo.automation.domain.OutboxDestination;
import org.springframework.stereotype.Component;

@Component
public class KafkaOrchestratorDispatcher implements TaskDispatcher {

    @Override
    public OutboxDestination destination() {
        return OutboxDestination.ORCHESTRATOR_TASK;
    }
}
