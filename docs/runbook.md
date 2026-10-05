# Runbook

## Kill switch

Pause every agent, or every agent on one template.

```bash
curl -s -H 'Content-Type: application/json' -H 'Authorization: Bearer <admin-jwt>' \
  -X POST localhost:8080/v1/admin/agents/pause-all \
  -d '{"templateId":"payday_sweep","reason":"incident INC123","expirePending":false}'
```

`templateId` null pauses every agent. `expirePending` defaults to false: open approvals stay open and can still be approved or denied. `true` expires them with reason `kill_switch`.

The response is a job id. Read progress at `GET /v1/admin/jobs/{jobId}` (`paused`, `batches`, `status`). Each batch writes an `agent-control` `remove` message. In standalone, use `X-Dev-Caller: admin` instead of the admin JWT.

## Replaying `.dlt` messages

Poison messages land on `<topic>.dlt` after 5 retries: `trigger-fired.dlt` and `automation-replies.dlt`.

1. Read the record and fix the payload (schema version, missing ids). Do not replay a message that already committed.
2. Publish the corrected JSON back to the original topic with the same `customerId` key.
3. Triggers are idempotent on `agentId:periodId`. A duplicate logs `duplicate_trigger` and is acknowledged.
4. Replies are idempotent on `taskId`. A reply for an older task is stale: it is logged and acknowledged.

## Failed outbox messages

`automation_outbox_failed_total` increases when a message reaches `maxAttempts` (20). The document stays in `outbox` with `status: failed`.

1. Read `destination`, `attempts`, and the payload. Do not log grant tokens.
2. Fix the downstream (Kafka, push, or the A2A supervisor).
3. Set `status` back to `pending`, `attempts` to 0, and `nextAttemptAt` to now. Clear `lockedBy` and `lockedUntil`. The relay claims it on the next poll.
4. Task messages mint a new grant token at delivery time. Execute tools stay idempotent on `quoteId`.

## `execute_timeout`

A run left in `EXECUTING` longer than `stuckRunTimeout` (10 minutes) is retried up to 3 times with the **same** `quoteId`. After that it becomes `FAILED` with reason `execute_timeout`. Nothing in the service knows whether the tool moved money.

1. Find the run (`state: FAILED`, history reason `execute_timeout`) and its `preview.quoteId`.
2. Ask the orchestrator or supervisor whether that quote executed.
3. Do not send another execute for the same quote until that check is done.
4. Tell the customer from the fixed activity text already stored. Do not invent a new message.
