package com.wf.fargo.automation.dispatch;

import com.wf.fargo.automation.domain.OutboxDestination;

public interface TaskDispatcher {

    OutboxDestination destination();
}
