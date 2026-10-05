Feature: Act then tell
  Scenario: Preview executes and the completion push can undo a card lock
    Given an approved template "card_lock" version 1 with mode "act_then_tell"
    And customer "cust_1" has an agent "agt_lock" from that template moving 0 from "card_tok_1" to "card_tok_1"
    When a trigger fires for "agt_lock" with period "2026-10-15"
    And the preview reply returns quote "Q-1"
    Then the run is "executing"
    And an execute task is sent with quote "Q-1"
    And no approval is pending
    When the execute reply succeeds
    Then the run is "done"
    And activity "completed" is recorded
    And a confirmation push is sent with an undo link
