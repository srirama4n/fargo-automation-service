package com.wf.fargo.automation.agents;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!standalone & !test")
public class UnconfiguredAccountsClient implements CustomerAccountsClient {

    @Override
    public boolean owns(String customerId, String token) {
        throw new IllegalStateException("Accounts API client is not configured");
    }
}
