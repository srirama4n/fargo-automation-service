package com.wf.fargo.automation.standalone;

import com.wf.fargo.automation.agents.CustomerAccountsClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"standalone", "test"})
public class StubAccountsClient implements CustomerAccountsClient {

    @Override
    public boolean owns(String customerId, String token) {
        return token != null && (token.startsWith("acct_tok_") || token.startsWith("card_tok_"));
    }
}
