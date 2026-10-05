package com.wf.fargo.automation.outbox;

import java.util.Map;

public interface PushClient {

    void send(Map<String, Object> payload);
}
