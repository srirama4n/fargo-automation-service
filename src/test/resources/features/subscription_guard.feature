Feature: Subscription price guard
  Scenario: Denied price increase updates the baseline
    Given customer "cust_2" has a "subscription_price_guard" agent "agt_2"
    When a trigger fires for "agt_2" with merchant "m_streamflix" from 15.49 to 17.99
    And the preview reply returns quote "Q-9"
    And the customer denies the approval
    Then the run is "denied"
    And a baseline update for "m_streamflix" with amount 17.99 is published
