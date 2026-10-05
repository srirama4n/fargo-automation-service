Feature: Agent lifecycle
  Scenario: Create requires a matching confirmation token
    Given customer "cust_3" requested a confirmation for template "payday_sweep" with amount 5000
    When the builder creates the agent with amount 6000 and that token
    Then the request is rejected with "confirmation_invalid"

  Scenario: Grant contains only template tools
    When the builder creates an agent and the request also lists tool "transfer.execute_external"
    Then the grant's allowed tools equal the template's required tools

  Scenario: Delete revokes the grant and expires pending approvals
    Given agent "agt_4" has a pending approval
    When the customer deletes "agt_4"
    Then the grant is revoked
    And the approval is "expired"
    And an agent-control "remove" message is published
