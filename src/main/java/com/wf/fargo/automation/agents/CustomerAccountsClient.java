package com.wf.fargo.automation.agents;

public interface CustomerAccountsClient {

    boolean owns(String customerId, String token);
}
