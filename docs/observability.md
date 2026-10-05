# Observability

Metrics are Micrometer meters scraped at `GET /actuator/prometheus`. Names below are the Prometheus series.

## Dashboard panels

| Panel | Series | Notes |
| --- | --- | --- |
| Run transitions | `automation_run_transitions_total` | tags `from`, `to`, `template` |
| Runs in state | `automation_runs_in_state` | tag `state` |
| Duplicate triggers | `automation_trigger_duplicates_total` | tag `template` |
| Approval decisions | `automation_approvals_decided_total` | tag `decision`: `approved`, `denied`, `expired` |
| Time to decide | `automation_approval_time_to_decide_seconds` | histogram, tag `template` |
| Dispatch latency | `automation_dispatch_latency_seconds` | histogram, tags `phase`, `mode` (`kafka`, `a2a`) |
| Outbox pending | `automation_outbox_pending` | tag `destination` |
| Oldest pending | `automation_outbox_oldest_pending_seconds` | tag `destination` |
| Outbox failures | `automation_outbox_failed_total` | tag `destination` |
| Stuck runs | `automation_stuck_runs_total` | tags `state`, `action` (`retried`, `failed`) |
| Policy rejections | `automation_policy_rejections_total` | tag `code` |
| Confirmation rejections | `automation_confirmation_rejections_total` | tag `reason` |

Logs are JSON. Where known, each line has `traceId`, hashed `customerId`, `agentId`, and `runId`. State changes log `run_transition from=… to=… reason=…` at INFO. Security events log `security=true` at WARN.

## Alerts

| Condition | For | Action |
| --- | --- | --- |
| `automation_outbox_oldest_pending_seconds > 120` | 5 minutes | Outbox is not draining. See the runbook. |
| `increase(automation_outbox_failed_total[5m]) > 0` | any increase | A destination exhausted retries. |
| `automation_stuck_runs_total{state="executing",action="failed"}` increases | immediate | Execute timed out. Outcome is unknown. Page a human. |
| Consumer lag on `trigger-fired` > 10000 | 10 minutes | Trigger consumer is behind Flink. |
