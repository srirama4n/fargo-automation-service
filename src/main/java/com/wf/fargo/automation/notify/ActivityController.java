package com.wf.fargo.automation.notify;

import com.wf.fargo.automation.common.CallerContext;
import com.wf.fargo.automation.notify.ActivityFeed.ActivityPage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityController {

    private final ActivityFeed feed;

    public ActivityController(ActivityFeed feed) {
        this.feed = feed;
    }

    @GetMapping("/v1/customers/{customerId}/activity")
    public ActivityPage list(
            HttpServletRequest request,
            @PathVariable String customerId,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) Integer limit) {
        return feed.list(CallerContext.requireCustomerId(request), customerId, before, limit);
    }
}
