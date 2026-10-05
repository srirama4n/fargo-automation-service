package com.wf.fargo.automation.dispatch;

import java.util.Map;

/**
 * Standalone substitute for the Supervisor HTTP call. Production has no bean of this type.
 */
public interface CannedA2aReplies {

    boolean active();

    Map<String, Object> replyTo(Map<String, Object> task);
}
