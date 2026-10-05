package com.wf.fargo.automation.standalone;

import com.wf.fargo.automation.dispatch.CannedA2aReplies;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile({"standalone", "test"})
public class FakeSupervisorA2a implements CannedA2aReplies {

    private final CannedReplies replies;
    private final boolean live;

    public FakeSupervisorA2a(CannedReplies replies, Environment environment) {
        this.replies = replies;
        this.live = FakeOrchestrator.standaloneOnly(environment);
    }

    @Override
    public boolean active() {
        return live;
    }

    @Override
    public Map<String, Object> replyTo(Map<String, Object> task) {
        long delay = Math.max(0, replies.delay().toMillis());
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        return replies.build(task);
    }
}
