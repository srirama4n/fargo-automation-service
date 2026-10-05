package com.wf.fargo.automation.outbox;

import java.util.Map;

/**
 * Mints a grant token when a task leaves the outbox. The token is added to the outbound
 * message only and is never written back to MongoDB.
 */
public interface GrantTokenMinter {

    String mint(Map<String, Object> taskPayload);
}
