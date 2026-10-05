Feature: Payday sweep agent
  Background:
    Given an approved template "payday_sweep" version 1 with mode "ask"
    And customer "cust_1" has an agent "agt_1" from that template moving 5000 from "acct_tok_chk" to "acct_tok_sav"

  Scenario: Approved transfer completes once
    When a trigger fires for "agt_1" with period "2026-10-15"
    Then a preview task is sent for run of "agt_1"
    When the preview reply returns quote "Q-1"
    Then an approval is pending and a push is sent
    When the customer approves with a valid step-up token
    Then an execute task is sent with quote "Q-1"
    When the execute reply succeeds
    Then the run is "done" and a confirmation push is sent

  Scenario: Duplicate trigger is ignored
    When a trigger fires for "agt_1" with period "2026-10-15"
    And a trigger fires for "agt_1" with period "2026-10-15"
    Then exactly one run exists for "agt_1"

  Scenario: Paused agent is skipped
    Given agent "agt_1" is paused
    When a trigger fires for "agt_1" with period "2026-10-15"
    Then the run is "skipped" with reason "agent_paused"
    And no task is sent

  Scenario: Approval expires
    When a trigger fires for "agt_1" with period "2026-10-15"
    And the preview reply returns quote "Q-1"
    And 25 hours pass
    Then the approval is "expired" and the run is "expired"
    And no execute task is sent

  Scenario: Double approve executes once
    Given a pending approval for "agt_1"
    When the customer approves twice at the same time
    Then exactly one execute task is sent
