Feature: Read mode
  Scenario: A read result is delivered without approval
    Given an approved template "weekly_bill_check" version 1 with mode "read"
    And customer "cust_1" has an agent "agt_read" from that template moving 0 from "acct_tok_chk" to "acct_tok_sav"
    When a trigger fires for "agt_read" with period "2026-10-15"
    And the preview reply returns quote "Q-read"
    Then the run is "done"
    And activity "notified" is recorded
    And a push is sent with the read result
    And no approval is pending
