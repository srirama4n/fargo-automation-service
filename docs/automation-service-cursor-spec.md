# Fargo Automation Service — Build Specification for Cursor

> **Purpose of this file:** a complete, self-contained specification for building the Fargo **Automation Service** from scratch with Cursor.
> Put this file in the repo at `docs/automation-service-spec.md` and reference it in every Cursor prompt with `@docs/automation-service-spec.md`.
> Build it phase by phase (section 15), test-first, one phase per Cursor session.

---

## 0. How to use this file with Cursor

1. Create an empty repo `fargo-automation-service` and add this file as `docs/automation-service-spec.md`.
2. Add your team's existing `.md` rule files under `.cursor/rules/`, plus the feature rule file in section 0.1.
3. For each phase in section 15:
   - Open Cursor **Plan mode**, paste the phase prompt, and ask for a file-by-file plan. Correct the plan before any code is written.
   - Switch to **Agent mode** and run the prompt. Cursor must write tests first (TDD), run them, then implement until green.
   - Check the phase's acceptance criteria before moving on. Commit after each phase.
4. Never let Cursor skip ahead to a later phase or invent fields, topics or endpoints that aren't in this file. If something is missing, add it to this file first, then prompt.

### 0.1 Cursor rule file: `.cursor/rules/automation-service.mdc`

```markdown
---
description: Fargo Automation Service rules
alwaysApply: true
---
- The source of truth is @docs/automation-service-spec.md. Do not invent fields, topics, endpoints or states.
- TDD: write or update tests first, run them, then implement until green. BDD scenarios in src/test/resources/features must pass.
- Every state change on agent_runs or approvals is a conditional update on the expected current state (RunStateStore).
- Every write that also sends a message (Kafka, push, A2A) uses the transactional outbox. Business code never calls Kafka, push or A2A directly.
- Duplicate-key errors on agent_runs.idempotencyKey mean "already processed": log at INFO and acknowledge. They are not failures.
- Never log or store raw account or card numbers. Only tokens. Never log grant tokens or confirmation tokens.
- No execute task is ever sent for a run in mode=ask without an approved approval whose previewHash matches the stored quote.
- Customer-facing message text comes from fixed templates, never from LLM output.
- Use Java 21 records for DTOs and messages. No Lombok.
- Errors are RFC 7807 problem+json with a stable `code` field.
- No Docker anywhere: no docker-compose, no Testcontainers. Tests and the `standalone` profile use embedded MongoDB, embedded Kafka and in-process stubs (section 13).
- Code under the `standalone` package is only active in the `standalone` and `test` profiles and never ships in the production jar.
```

---

## 1. Context and scope

Fargo is Wells Fargo's AI banking assistant: Chat API → Kafka → Orchestrator (Flink, A2A Java SDK) → Supervisor (Google ADK) → remote sub-agents (A2A) → MCP server → Chat-Data-Service tools → API layer.

**Personal agents** let a customer create a background agent from a chat request, for example:
- "Move anything over $5,000 to savings on payday" (trigger: payroll credit; action: transfer; mode: ask).
- "Cancel subscriptions that raise prices" (trigger: recurring charge increased; action: card merchant block; mode: ask).
- "Every Monday tell me my electricity bill" (trigger: schedule; action: bill read; mode: read).
- "Lock my card if there's a charge abroad while I'm home" (trigger: foreign card charge; action: card lock; mode: act_then_tell).

**The Automation Service** owns agents, grants, runs and approvals. It is the **only** component that reads or writes the personal-agent MongoDB database.

### 1.1 Responsibilities
1. Agent lifecycle: validate, confirm, create, list, pause, resume, delete.
2. Grants: issue from the template's tools only; revoke on delete; serve to the MCP grant check; sign short-lived grant tokens.
3. Run lifecycle: start runs from triggers, block duplicates, drive every run to a final state.
4. Dispatch: send preview and execute tasks into Fargo.
5. Approvals: create, verify step-up and preview hash, handle approve, deny and expiry.
6. Scheduling: fire time-based agents.
7. Notifications and activity: push messages and the customer-facing activity feed.
8. Reliability and operations: outbox relay, sweeper, kill switch.

### 1.2 Out of scope (owned by other components)
- Detecting triggers from banking events (Flink trigger job).
- Matching utterances to templates and building review cards (Personal Agent Builder sub-agent).
- Enforcing grants on tool calls (MCP server grant-check interceptor; this service only serves grant data and signing keys).
- Preview and execute tool logic (Chat-Data-Service tools).
- UI (Fargo app).

---

## 2. Architecture and interfaces

```
                  Fargo app (REST, customer JWT via API gateway)
                         │  confirmations, agents, approvals, activity
                         ▼
MCP server ──(REST, mTLS)──►  ┌──────────────────────────────────────────┐ ◄── trigger-fired (Kafka) ── Flink trigger job
 (builder's agent tools,      │            AUTOMATION SERVICE            │ ──► agent-control (Kafka) ──► Flink trigger job
  grant lookup, JWKS)         │                                          │ ──► baseline-updates (Kafka) ► Flink trigger job
                              │  agents · grants · runs · approvals      │
                              │  scheduler · sweeper · outbox relay      │ ──► orchestrator input (Kafka) ─► Orchestrator ─► Supervisor
                              │                                          │ ◄── automation-replies (Kafka) ◄─ Orchestrator
                              └───────┬───────────────┬──────────────────┘     (or direct A2A to Supervisor: dispatch.mode=a2a)
                                      │               │
                                   MongoDB      push service, cvams (REST)
```

### 2.1 Messaging decision (hybrid)
- **Kafka stays** on all Flink-facing edges: `trigger-fired` (in), `agent-control` (out), `baseline-updates` (out). Reasons: bursty payday volume, buffering when this service is down, replay, exactly-once from Flink.
- **Dispatch to Fargo is pluggable** behind a `TaskDispatcher` interface:
  - `KafkaOrchestratorDispatcher` (default, `automation.dispatch.mode=kafka`): publishes `AutomationTask` to the Orchestrator input topic; replies arrive on `automation-replies`.
  - `A2aSupervisorDispatcher` (`automation.dispatch.mode=a2a`): calls the Supervisor directly over A2A (HTTP) using A2A tasks; the reply is handed to the same `ReplyHandler` used by the Kafka consumer.
  - Both are delivered through the outbox relay, so business code is identical in both modes.
- **REST** is used wherever the caller needs an immediate answer: the app, the MCP server (builder tools, grant lookup), push, cvams.

### 2.2 Interface summary

| Direction | Interface | Count |
| --- | --- | --- |
| Inbound REST (app) | Section 9.1 | 9 endpoints |
| Inbound REST (MCP server, builder tools) | Section 9.2 | 3 endpoints |
| Inbound REST (MCP server, grant check) | Section 9.3 | 2 endpoints |
| Inbound REST (admin) | Section 9.4 | 2 endpoints |
| Kafka consumed | `trigger-fired`, `automation-replies` | 2 topics |
| Kafka produced | `agent-control`, `baseline-updates`, orchestrator input | 3 topics |
| Outbound REST | push service, cvams step-up verify, A2A Supervisor (optional) | 3 clients |

---

## 3. Tech stack

| Concern | Choice |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3.3.x (web, validation, actuator) |
| Database | MongoDB 7 (replica set; multi-document transactions required) via Spring Data MongoDB |
| Messaging | Spring for Apache Kafka (idempotent producer, manual ack consumers) |
| JSON | Jackson; canonical JSON for hashing per RFC 8785 (JCS) |
| Tokens | Nimbus JOSE + JWT (RS256 for grant tokens, HS256 for confirmation tokens) |
| Distributed locks | ShedLock (Mongo provider) |
| Resilience | Resilience4j (retry, circuit breaker on push, cvams, A2A) |
| Observability | Micrometer + OpenTelemetry, structured JSON logs |
| Tests | JUnit 5, AssertJ, embedded MongoDB (Flapdoodle, single-node replica set), embedded Kafka (`spring-kafka-test`, KRaft), WireMock (in-JVM), Cucumber (BDD) |
| Standalone run | Same embedded MongoDB and Kafka plus in-process stubs, as `developmentOnly` dependencies (section 13). No Docker required |
| Build | Gradle (Kotlin DSL) or Maven; follow the team standard |

---

## 4. Project structure

```
fargo-automation-service/
  docs/automation-service-spec.md
  .cursor/rules/automation-service.mdc
  src/main/java/com/wf/fargo/automation/
    AutomationServiceApplication.java
    config/          AppProperties, KafkaConfig, MongoConfig, SecurityConfig, JwtKeyConfig, ClockConfig
    common/          ProblemCodes, ApiException, CanonicalJson, Hashing, Ids, Clock wrapper
    domain/          enums (RunState, ApprovalStatus, AgentStatus, AgentMode, ActivityType, OutboxDestination),
                     RunStateMachine, documents (records) for every collection
    store/           repositories, RunStateStore, ApprovalStore, TransactionRunner, IndexInitializer
    outbox/          OutboxWriter, OutboxRelay, OutboxDeliverer (Kafka, Push, A2A)
    agents/          AgentController, AgentToolsController (MCP-facing), AgentService, PolicyChecker,
                     ConfirmationService, GrantService, GrantTokenSigner, TemplateService
    runs/            TriggerFiredConsumer, RunService
    dispatch/        TaskDispatcher, KafkaOrchestratorDispatcher, A2aSupervisorDispatcher, TaskFactory
    replies/         AutomationReplyConsumer, ReplyHandler
    approvals/       ApprovalController, ApprovalService, StepUpVerifier (cvams client)
    scheduler/       ScheduleCalculator, ScheduleTriggerJob
    sweeper/         ApprovalExpiryJob, StuckRunJob
    notify/          Notifier, ActivityWriter, MessageTemplates
    admin/           AdminController, KillSwitchService
    grants/          GrantLookupController, JwksController
    standalone/      EmbeddedMongoConfig, EmbeddedKafkaConfig, StubPushClient, StubCvamsClient, StubAccountsClient,
                     FakeOrchestrator, FakeSupervisorA2a, DevTriggerController, DevAuthFilter, TemplateSeeder
  src/main/resources/application.yml
  src/main/resources/application-standalone.yml
  src/main/resources/standalone/templates/*.json   seed templates for the standalone profile
  src/test/java/...                       unit + integration tests mirror main packages
  src/test/resources/features/*.feature    BDD scenarios (section 14.3)
  src/test/resources/samples/*.json        message and document samples
```

---

## 5. Configuration (`application.yml`)

```yaml
automation:
  dispatch:
    mode: kafka                 # kafka | a2a
  topics:
    triggerFired: trigger-fired
    automationReplies: automation-replies
    agentControl: agent-control
    baselineUpdates: baseline-updates
    orchestratorInput: fargo-orchestrator-input   # confirm the real topic name
  limits:
    maxAgentsPerCustomer: 20
    confirmationTtl: PT5M
    grantTokenTtl: PT15M
    defaultApprovalTtl: PT24H
    stuckRunTimeout: PT10M
  sweeper:
    approvalExpiryCron: "0 * * * * *"     # every minute
    stuckRunCron: "30 * * * * *"
  scheduler:
    pollInterval: PT30S
    batchSize: 200
  outbox:
    pollInterval: PT2S
    leaseDuration: PT30S
    maxAttempts: 20
  push:
    baseUrl: https://push.internal.example
  cvams:
    baseUrl: https://cvams.internal.example
  a2a:
    supervisorUrl: https://supervisor.internal.example
    timeout: PT60S
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/fargo_automation?replicaSet=rs0
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all
      enable-idempotence: true
    consumer:
      enable-auto-commit: false
      isolation-level: read_committed
```

All durations and limits are read through a typed `AppProperties` record. Tests override them.

---

## 6. Domain model

### 6.1 Enums

```java
enum AgentMode { ASK, ACT_THEN_TELL, READ }
enum AgentStatus { ON, PAUSED, DELETED }
enum RunState { TRIGGERED, PREVIEWING, AWAITING_APPROVAL, EXECUTING, DONE, SKIPPED, DENIED, EXPIRED, FAILED }
enum ApprovalStatus { PENDING, APPROVED, DENIED, EXPIRED }
enum ActivityType { CREATED, PAUSED, RESUMED, DELETED, FIRED, SKIPPED, APPROVAL_REQUESTED,
                    APPROVED, DENIED, EXPIRED, COMPLETED, FAILED, NOTIFIED }
enum OutboxDestination { AGENT_CONTROL, BASELINE_UPDATES, ORCHESTRATOR_TASK, A2A_TASK, PUSH }
```

Serialize enums as lowercase snake case in JSON (`act_then_tell`, `awaiting_approval`).

### 6.2 Run state machine

| From | Allowed to |
| --- | --- |
| TRIGGERED | PREVIEWING, SKIPPED |
| PREVIEWING | AWAITING_APPROVAL, EXECUTING, DONE, FAILED |
| AWAITING_APPROVAL | EXECUTING, DENIED, EXPIRED |
| EXECUTING | DONE, FAILED |
| DONE, SKIPPED, DENIED, EXPIRED, FAILED | (terminal) |

- `PREVIEWING → DONE` is only for mode `READ`.
- `PREVIEWING → EXECUTING` is only for mode `ACT_THEN_TELL`.
- `RunStateMachine.canTransition(from, to)` is the single source of truth. Every transition elsewhere calls it.

### 6.3 Collections

All documents have `_id` = their business id (e.g. `agentId`), `createdAt`, `updatedAt` (UTC instants), and `schemaVersion` (int, starts at 1).

**`agent_templates`** (bank-managed, read-only for customers)

| Field | Type | Notes |
| --- | --- | --- |
| templateId | string | e.g. `payday_sweep` |
| version | int | Templates are immutable; a change is a new version |
| name | string | Display name |
| description | string | Shown in suggested agents |
| trigger.type | string | `payroll_credit`, `recurring_charge_increased`, `foreign_card_charge`, `balance_below`, `schedule` |
| action.type | string | `transfer`, `card.merchant_block`, `card.lock`, `bill.read`, `notify` |
| action.previewIntent / executeIntent | string | e.g. `transfer.preview`, `transfer.execute` |
| mode | AgentMode | |
| paramSchema | object | JSON Schema the params must satisfy |
| limits | object | `maxAmount`, `maxRunsPerDay`, `maxRunsPerPeriod` |
| requiredTools | string[] | The only tools a grant may contain |
| approvalTtl | ISO duration | Defaults to `automation.limits.defaultApprovalTtl` |
| effectText | string | Plain-language effect for review and approval cards |
| status | string | `draft`, `approved`, `retired` |
| reviewedBy, reviewedAt | string, instant | |

**`personal_agents`**

| Field | Type | Notes |
| --- | --- | --- |
| agentId | string | `agt_` + ULID |
| customerId | string | |
| templateId, templateVersion | string, int | |
| name | string | From template, user can't rename in v1 |
| params | object | Validated against `paramSchema`; tokens only |
| trigger | object | type, accountScopes, filters (e.g. threshold), schedule (cron, timezone) |
| schedule.nextFireAt | instant or null | Only for schedule triggers |
| guards | object | `oncePer` (`pay_period`, `billing_cycle`, `day`), `achHold` (duration) |
| mode | AgentMode | Copied from template |
| status | AgentStatus | |
| grantId | string | |
| sourceUtterance | string | What the user typed |
| sessionId | string | Chat session that created it |
| version | long | Optimistic locking (`@Version`) |

**`grants`**

| Field | Type | Notes |
| --- | --- | --- |
| grantId | string | `grt_` + ULID |
| agentId, customerId | string | |
| allowedTools | string[] | Exactly the template's `requiredTools` |
| accountScopes | string[] | Account and card tokens from params |
| limits | object | From template and params |
| issuedAt, expiresAt | instant | `expiresAt` null = until revoked |
| revokedAt, revokedReason | instant, string | |

**`agent_runs`**

| Field | Type | Notes |
| --- | --- | --- |
| runId | string | `run_` + ULID |
| agentId, customerId | string | |
| periodId | string | From trigger, e.g. `2026-10-15` or `streamflix:2026-10` |
| idempotencyKey | string | `agentId + ":" + periodId`, **unique** |
| triggerEventRef | string | Banking event id or schedule instant |
| triggerContext | object | Data from the trigger (merchant, old/new amount, payroll amount) |
| state | RunState | |
| stateHistory | array | `{from, to, at, reason}` appended on every transition |
| preview | object | `quoteId`, `effect`, `warnings[]`, `data` |
| outcome | object | Result of execute (e.g. `transferId`, `blockId`) |
| error | object | `code`, `message` |
| currentTaskId | string | The task this run is waiting on; replies with another taskId are stale |
| attempts | object | `preview`, `execute` counters |

**`approvals`**

| Field | Type | Notes |
| --- | --- | --- |
| approvalId | string | `apr_` + ULID, used in the push deep link |
| runId, agentId, customerId | string | |
| previewHash | string | `sha256:` + hex of JCS-canonical `preview` |
| status | ApprovalStatus | |
| expiresAt | instant | |
| stepUpRef | string | cvams reference on approve |
| notificationId | string | |
| decidedAt | instant | |

**`agent_activity`**

| Field | Type | Notes |
| --- | --- | --- |
| eventId | string | `evt_` + ULID |
| customerId, agentId, runId | string | runId optional |
| type | ActivityType | |
| message | string | Rendered from `MessageTemplates`, never LLM text |
| data | object | Small structured payload for the UI |

**`confirmations`**

| Field | Type | Notes |
| --- | --- | --- |
| confirmationId | string | JWT `jti` |
| customerId | string | |
| draftHash | string | Hash of the canonical draft |
| usedAt | instant or null | Single use |
| expiresAt | instant | TTL index allowed here (not an audit record) |

**`outbox`**

| Field | Type | Notes |
| --- | --- | --- |
| messageId | string | `msg_` + ULID |
| destination | OutboxDestination | |
| key | string | Kafka key (customerId) or push target |
| payload | object | The message |
| status | string | `pending`, `sent`, `failed` |
| attempts | int | |
| nextAttemptAt | instant | Backoff |
| lockedBy, lockedUntil | string, instant | Lease so only one instance sends a message |
| sentAt | instant | |

### 6.4 Indexes (created by `IndexInitializer` at startup, idempotent)

| Collection | Index | Purpose |
| --- | --- | --- |
| agent_templates | `{templateId:1, version:1}` unique | Lookup |
| personal_agents | `{customerId:1, status:1}` | Agents tab |
| personal_agents | `{"schedule.nextFireAt":1, status:1}` | Scheduler |
| grants | `{agentId:1}` unique | One grant per agent |
| agent_runs | `{idempotencyKey:1}` unique | Duplicate protection |
| agent_runs | `{state:1, updatedAt:1}` | Stuck runs |
| agent_runs | `{customerId:1, createdAt:-1}` | Support queries |
| approvals | `{status:1, expiresAt:1}` | Expiry |
| approvals | `{runId:1}` unique | One approval per run |
| agent_activity | `{customerId:1, createdAt:-1}` | Feed |
| confirmations | `{expiresAt:1}` TTL 0 | Cleanup |
| outbox | `{status:1, nextAttemptAt:1}` | Relay |

**No TTL indexes on `agent_runs`, `approvals`, `grants` or `agent_activity`.** They are audit records; retention is a separate archival job.

---

## 7. Kafka contracts

All messages are JSON with `schemaVersion`, `messageId` (ULID) and `occurredAt`. Key is always `customerId`, so one customer's messages stay in order. Producers: idempotent, `acks=all`. Consumers: manual ack **after** the MongoDB transaction commits; poison messages go to `<topic>.dlt` after 5 retries with backoff.

### 7.1 `trigger-fired` (consumed, from Flink)

```json
{
  "schemaVersion": 1,
  "messageId": "01J9Z...",
  "occurredAt": "2026-10-15T13:05:00Z",
  "customerId": "cust_8841",
  "agentId": "agt_77",
  "periodId": "2026-10-15",
  "eventRef": "post_99812",
  "triggerType": "payroll_credit",
  "context": { "amount": 1000.00, "currency": "USD", "payerId": "payer_acme", "confidence": "low" }
}
```

For `recurring_charge_increased`, `context` = `{ "merchantId", "merchantName", "oldAmount", "newAmount", "cardToken" }`.

### 7.2 `agent-control` (produced, to Flink)

```json
{
  "schemaVersion": 1,
  "messageId": "01J9Z...",
  "occurredAt": "2026-10-01T09:00:00Z",
  "op": "upsert",
  "customerId": "cust_8841",
  "agentId": "agt_77",
  "agentVersion": 3,
  "triggerType": "recurring_charge_increased",
  "accountScopes": ["card_tok_9034"],
  "filters": { "threshold": "any_increase" },
  "guards": { "oncePer": "billing_cycle" },
  "backfill": true
}
```

`op` is `upsert` (create, resume, edit) or `remove` (pause, delete, kill switch). Flink keeps the highest `agentVersion` it has seen per agent and ignores older messages. `backfill: true` only on create.

### 7.3 `baseline-updates` (produced, to Flink)

Sent when a price-increase approval is denied or expires, so the agent doesn't ask again about a price the user accepted.

```json
{ "schemaVersion": 1, "messageId": "01J9Z...", "occurredAt": "...",
  "customerId": "cust_8841", "cardToken": "card_tok_9034",
  "merchantId": "m_streamflix", "amount": 17.99, "reason": "approval_denied" }
```

### 7.4 Orchestrator input (produced) — `AutomationTask`

```json
{
  "schemaVersion": 1,
  "messageId": "01J9Z...",
  "occurredAt": "...",
  "channel": "automation",
  "taskId": "tsk_01J9Z...",
  "runId": "run_1042",
  "customerId": "cust_8841",
  "agentId": "agt_77",
  "phase": "preview",
  "intent": "card.merchant_block.preview",
  "params": { "card": "card_tok_9034", "merchant": "m_streamflix" },
  "quoteId": null,
  "grantToken": "<signed JWT, section 8.2>",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

For `phase: "execute"`, `intent` is the execute intent, `params` is `{}` and `quoteId` is set.

### 7.5 `automation-replies` (consumed, from Orchestrator) — `AutomationReply`

```json
{
  "schemaVersion": 1,
  "messageId": "01J9Z...",
  "occurredAt": "...",
  "taskId": "tsk_01J9Z...",
  "runId": "run_1042",
  "customerId": "cust_8841",
  "phase": "preview",
  "status": "ok",
  "quote": {
    "quoteId": "Q-123",
    "effect": "Future StreamFlix charges on Debit ••9034 will be declined",
    "warnings": ["Today's $17.99 charge already posted", "Your StreamFlix account is not cancelled"],
    "data": { "nextExpectedCharge": "2026-10-26" }
  },
  "outcome": null,
  "error": null
}
```

- `status`: `ok` or `error`. On `error`, `error = { "code", "message", "retryable": bool }`.
- For `phase: "execute"`, `quote` is null and `outcome` holds the result (e.g. `{ "blockId": "blk_552" }`).
- A reply whose `taskId` doesn't match the run's current task is stale: log and acknowledge.

---

## 8. Security

### 8.1 Caller authentication

| Caller | Mechanism | Identity used |
| --- | --- | --- |
| Fargo app (section 9.1) | Customer JWT validated at the API gateway and again here (issuer, audience, expiry) | `customerId` claim. Every request is checked for ownership: the resource's `customerId` must equal the claim, otherwise 404 (not 403, to avoid revealing existence). |
| MCP server (9.2, 9.3) | mTLS service identity + header `X-Fargo-Customer-Id` taken from the end-user session by the MCP server | Customer id from header, only trusted for the MCP service identity |
| Admin (9.4) | Internal SSO JWT with role `automation-admin` | Admin user id, written to the audit log |

### 8.2 Grant token (signed by this service, verified by the MCP grant check)
- JWT, RS256, key from the bank's key management service; public keys served at `GET /.well-known/jwks.json`.
- Claims: `iss=fargo-automation`, `aud=fargo-mcp`, `sub=agentId`, `grantId`, `customerId`, `runId`, `taskId`, `iat`, `exp` (now + `grantTokenTtl`).
- Minted fresh for every `AutomationTask`. Never stored, never logged.

### 8.3 Confirmation token (proves the user tapped Create)
- Issued by `POST /confirmations` when the user taps Create in the app.
- JWT, HS256 with a service secret. Claims: `jti` (= confirmationId), `customerId`, `draftHash`, `exp` (now + `confirmationTtl`).
- `draftHash` = `sha256:` + hex of the JCS-canonical draft `{templateId, templateVersion, params}`.
- `POST /agents` requires it, recomputes the hash from the request, checks `customerId`, expiry and that `confirmations.usedAt` is null, then sets `usedAt` in the same transaction as the create. A reused or mismatched token returns 403 `confirmation_invalid`.

### 8.4 Step-up on approval
- The app runs the existing cvams step-up flow and sends the resulting token in header `X-StepUp-Token`.
- `StepUpVerifier` calls cvams to verify it is valid, unexpired, for this `customerId`, and bound to this `approvalId` (pass the approvalId as the step-up context when the app starts the flow). Failure returns 401 `step_up_required`.

### 8.5 Data handling
- Accept only tokens for accounts and cards (pattern `^(acct|card)_tok_[A-Za-z0-9]+$`). Reject anything that looks like a raw number (13–19 digits) with 400 `raw_account_number`.
- Mask tokens in logs to the last 4 characters.

---

## 9. REST API

Base path `/v1`. JSON only. Errors are RFC 7807:

```json
{ "type": "https://errors.fargo/automation/agent_limit_reached", "title": "Agent limit reached",
  "status": 409, "code": "agent_limit_reached", "detail": "You can have up to 20 agents." }
```

### 9.1 App endpoints (9)

**1. `POST /v1/confirmations`** — user tapped Create.
- Request: `{ "templateId": "payday_sweep", "templateVersion": 3, "params": { ... } }`
- Behaviour: compute `draftHash`, insert `confirmations` doc, return token.
- Response 201: `{ "confirmationToken": "<jwt>", "expiresAt": "..." }`
- The app sends the token back into chat as a structured confirm message; the builder passes it to `create_agent`.

**2. `GET /v1/customers/{customerId}/agents?status=on,paused`**
- Response 200: `{ "agents": [ { "agentId", "name", "templateId", "mode", "status", "effectText", "createdAt", "lastRun": { "state", "at" } } ] }`

**3. `GET /v1/agents/{agentId}`**
- Response 200: agent detail including `params` (display form), `trigger`, `guards`, `grant: { "allowedTools", "accountScopes" }`, last 10 runs.

**4. `PATCH /v1/agents/{agentId}`**
- Request: `{ "status": "paused" }` or `{ "status": "on" }`, header `If-Match: <version>`.
- Behaviour (one transaction): update status (optimistic version), activity `PAUSED`/`RESUMED`, outbox `agent-control` (`remove` on pause, `upsert` on resume), recompute `schedule.nextFireAt` on resume.
- Errors: 409 `version_conflict`, 409 `agent_deleted`.

**5. `DELETE /v1/agents/{agentId}`**
- Behaviour (one transaction): status `DELETED`, grant `revokedAt=now, revokedReason="agent_deleted"`, activity `DELETED`, outbox `agent-control remove`. Pending approvals for this agent are expired by the same transaction (runs → `EXPIRED`, reason `agent_deleted`).
- Response 204.

**6. `GET /v1/approvals/{approvalId}`**
- Response 200: `{ "approvalId", "status", "expiresAt", "agent": { "name" }, "trigger": { ...context for display }, "preview": { "effect", "warnings", "data" } }`

**7. `POST /v1/approvals/{approvalId}/approve`** — header `X-StepUp-Token`.
- Behaviour: section 10.6.
- Response 200: `{ "status": "approved", "runState": "executing" }`
- Errors: 401 `step_up_required`, 409 `approval_not_pending` (already decided or expired), 409 `preview_changed`.

**8. `POST /v1/approvals/{approvalId}/deny`**
- Response 200: `{ "status": "denied" }`. Errors: 409 `approval_not_pending`.

**9. `GET /v1/customers/{customerId}/activity?before=<cursor>&limit=20`**
- Response 200: `{ "items": [ { "eventId", "type", "message", "agentId", "runId", "createdAt" } ], "nextCursor": "..." }`

### 9.2 MCP endpoints for the Personal Agent Builder (3)

These sit behind MCP tools of the same purpose; the MCP server forwards calls.

**10. `GET /v1/templates?status=approved`** → tool `list_templates`
- Response 200: `{ "templates": [ { "templateId", "version", "name", "description", "mode", "paramSchema", "limits", "effectText" } ] }`

**11. `POST /v1/agents/validate`** → tool `validate_agent`
- Request: `{ "templateId", "templateVersion", "params" }`
- Behaviour: `PolicyChecker` (section 10.2). Saves nothing.
- Response 200: `{ "valid": true, "draftHash": "sha256:...", "effectText": "...", "errors": [] }` or `{ "valid": false, "errors": [ { "field": "params.amount", "code": "over_limit", "message": "The most this agent can move is $10,000." } ] }`

**12. `POST /v1/agents`** → tool `create_agent`
- Request: `{ "templateId", "templateVersion", "params", "confirmationToken", "sourceUtterance", "sessionId" }`
- Behaviour: section 10.3.
- Response 201: `{ "agentId", "status": "on", "grantId" }`
- Errors: 403 `confirmation_invalid`, 422 `policy_violation`, 409 `agent_limit_reached`.

The builder's list, pause and delete tools reuse endpoints 2, 4 and 5 through MCP.

### 9.3 MCP endpoints for the grant check (2)

**13. `GET /v1/grants/{grantId}`** (internal only)
- Response 200: `{ "grantId", "agentId", "customerId", "allowedTools", "accountScopes", "limits", "revoked": false }`
- The MCP server caches this in Redis for 60 seconds.

**14. `GET /.well-known/jwks.json`** — public keys for grant tokens.

### 9.4 Admin endpoints (2)

**15. `POST /v1/admin/agents/pause-all`**
- Request: `{ "templateId": "payday_sweep" | null, "reason": "incident INC123" }` (null = every agent).
- Behaviour: pauses matching agents in batches of 500 (each batch one transaction with outbox `agent-control remove` messages), records the admin and reason in activity and logs. Returns a job id; progress at `GET /v1/admin/jobs/{jobId}`.

**16. `POST /v1/admin/templates`**
- Publishes a reviewed template version (`status: approved`). Rejects edits to an existing `{templateId, version}`.

---

## 10. Module behaviour (what happens inside)

### 10.1 `TransactionRunner`, `RunStateStore`, `OutboxWriter`

- `TransactionRunner.inTransaction(Supplier<T>)`: MongoDB multi-document transaction; retries on `TransientTransactionError` and `UnknownTransactionCommitResult` (max 3).
- `RunStateStore.transition(runId, expectedState, newState, reason, Consumer<Update> extra)`:
  1. Assert `RunStateMachine.canTransition(expectedState, newState)`.
  2. `findOneAndUpdate({_id: runId, state: expectedState}, {$set: {state: newState, updatedAt: now, ...extra}, $push: {stateHistory: {from, to, at, reason}}})`.
  3. Return `Optional<AgentRun>`; empty means another actor already moved the run. Callers treat empty as "nothing to do", never as an error.
- `ApprovalStore.decide(approvalId, PENDING → newStatus, ...)` works the same way.
- `OutboxWriter.write(destination, key, payload)` only inserts into `outbox` and **must** be called inside `inTransaction`.

### 10.2 `PolicyChecker`
Checks, in order, returning all errors (not just the first):
1. Template exists, `status=approved`, version matches.
2. `params` validates against `paramSchema` (use a JSON Schema validator, draft 2020-12).
3. Amounts ≤ `limits.maxAmount`; positive; currency USD in v1.
4. Every account or card token in params belongs to the customer (call the accounts API through a `CustomerAccountsClient`; mock in tests).
5. Customer has fewer than `maxAgentsPerCustomer` agents with status `on` or `paused`.
6. No other active agent with the same template and the same params (duplicate agent) → `duplicate_agent`.

### 10.3 Create agent (`AgentService.create`)
1. Verify the confirmation token (section 8.3). Recompute `draftHash` from the request.
2. Run `PolicyChecker`; any error → 422 with the errors.
3. In one transaction:
   - set `confirmations.usedAt` (filter `usedAt: null`; no match → 403 `confirmation_invalid`),
   - insert `grants` with `allowedTools = template.requiredTools` (ignore anything else in the request), `accountScopes` = tokens from params,
   - insert `personal_agents` with status `ON`, `mode` from template, `schedule.nextFireAt` from `ScheduleCalculator` if it's a schedule trigger,
   - insert activity `CREATED`,
   - outbox `AGENT_CONTROL` upsert with `backfill: true` (skip for schedule triggers; Flink doesn't handle them).
4. Return 201.

### 10.4 Trigger consumer (`TriggerFiredConsumer` → `RunService.start`)
1. Load the agent. If not found → log WARN and ack.
2. Insert the run with state `TRIGGERED`, `idempotencyKey = agentId:periodId`, `triggerContext = message.context`.
   - Duplicate key → log INFO `duplicate_trigger` and ack. Done.
3. If agent status ≠ `ON` or grant revoked, or `maxRunsPerDay` already reached today → transition `TRIGGERED → SKIPPED` with reason (`agent_paused`, `grant_revoked`, `daily_limit`), activity `SKIPPED`, ack.
4. Otherwise, in one transaction: transition `TRIGGERED → PREVIEWING`, set `currentTaskId`, increment `attempts.preview`, outbox the preview task via `TaskFactory` (destination `ORCHESTRATOR_TASK` or `A2A_TASK` by dispatch mode), activity `FIRED`.
5. Ack the Kafka message only after the transaction commits.

The scheduler (10.7) calls `RunService.start` directly with the same input shape; there is one code path for starting runs.

### 10.5 Reply handling (`AutomationReplyConsumer` and `A2aSupervisorDispatcher` → `ReplyHandler`)
1. Load the run; if `taskId ≠ run.currentTaskId` → stale, log and ack.
2. **Preview reply, status ok** — branch on agent mode:
   - `ASK`: one transaction: transition `PREVIEWING → AWAITING_APPROVAL` storing `preview`; insert approval (`previewHash` of canonical `preview`, `expiresAt = now + template.approvalTtl`); activity `APPROVAL_REQUESTED`; outbox `PUSH` (deep link `wf://fargo/approvals/{approvalId}`, text from `MessageTemplates`).
   - `ACT_THEN_TELL`: one transaction: transition `PREVIEWING → EXECUTING` storing `preview`; new `currentTaskId`; outbox execute task with `quoteId`.
   - `READ`: one transaction: transition `PREVIEWING → DONE` storing `preview`; activity `NOTIFIED`; outbox `PUSH` with the read result.
3. **Execute reply, status ok**: transition `EXECUTING → DONE` with `outcome`; activity `COMPLETED`; outbox `PUSH` confirmation. For `ACT_THEN_TELL`, the push includes an undo deep link where the action supports it (card lock → unlock).
4. **Any reply, status error**:
   - `retryable: true` and attempts < 3 → keep state, new `currentTaskId`, outbox the same task again with backoff (`nextAttemptAt`).
   - otherwise → transition to `FAILED` with `error`; activity `FAILED`; outbox `PUSH` ("We couldn't complete … nothing was changed" for preview failures; for execute failures use the error's customer message template).

### 10.6 Approvals (`ApprovalService`)
**Approve:**
1. Load approval; ownership check; status must be `PENDING` and `expiresAt > now`, else 409 `approval_not_pending`.
2. `StepUpVerifier.verify(token, customerId, approvalId)`; failure → 401.
3. Load run; recompute `sha256(JCS(run.preview))` and compare with `previewHash`; mismatch → 409 `preview_changed` (log as a security event).
4. One transaction:
   - `ApprovalStore.decide(PENDING → APPROVED, stepUpRef, decidedAt)`; empty → 409 `approval_not_pending`,
   - `RunStateStore.transition(AWAITING_APPROVAL → EXECUTING)`; new `currentTaskId`; `attempts.execute++`,
   - outbox execute task (`quoteId = run.preview.quoteId`),
   - activity `APPROVED`.

**Deny:**
1. Ownership; one transaction: approval `PENDING → DENIED`, run `AWAITING_APPROVAL → DENIED`, activity `DENIED`.
2. If the template's trigger is `recurring_charge_increased`, also outbox `BASELINE_UPDATES` with the new amount.

### 10.7 Scheduler (`ScheduleTriggerJob`)
- Runs every `scheduler.pollInterval` on every instance.
- Claims due agents one at a time with `findOneAndUpdate({status: ON, "schedule.nextFireAt": {$lte: now}}, {$set: {"schedule.nextFireAt": next}})` where `next` comes from `ScheduleCalculator` (cron + IANA timezone, DST-safe). Because the claim moves `nextFireAt` forward atomically, two instances can't fire the same occurrence.
- For each claimed agent: `RunService.start(agentId, periodId = scheduled instant in ISO, eventRef = "schedule:" + instant)`.
- Process up to `batchSize` per tick.

### 10.8 Sweeper (`ApprovalExpiryJob`, `StuckRunJob`)
Both guarded by ShedLock so one instance runs each at a time.
- **ApprovalExpiryJob**: for each `approvals{status: PENDING, expiresAt <= now}`: one transaction: approval `PENDING → EXPIRED`, run `AWAITING_APPROVAL → EXPIRED`, activity `EXPIRED`, outbox `PUSH` ("This request expired, nothing was changed"), plus `BASELINE_UPDATES` for price-increase agents.
- **StuckRunJob**: runs in `PREVIEWING` or `EXECUTING` with `updatedAt < now - stuckRunTimeout`:
  - `PREVIEWING`: retry (re-send the preview task with a new `currentTaskId`) if `attempts.preview < 3`, else `FAILED` reason `preview_timeout`.
  - `EXECUTING`: **never blindly re-send execute**. Re-send the same execute task with the **same quoteId** (execute tools are idempotent on quoteId) if `attempts.execute < 3`; else `FAILED` reason `execute_timeout`, activity `FAILED`, and raise an operational alert because the outcome is unknown.

### 10.9 Outbox relay (`OutboxRelay`, `OutboxDeliverer`)
1. Wake on a MongoDB change stream on `outbox` (insert events) and also poll every `outbox.pollInterval` as a fallback.
2. Claim a message with `findOneAndUpdate({status: pending, nextAttemptAt <= now, $or: [{lockedUntil: null}, {lockedUntil: {$lt: now}}]}, {$set: {lockedBy: instanceId, lockedUntil: now + leaseDuration}})`.
3. Deliver by destination:
   - `AGENT_CONTROL`, `BASELINE_UPDATES`, `ORCHESTRATOR_TASK` → Kafka send, wait for ack.
   - `A2A_TASK` → `A2aSupervisorDispatcher.send` (timeout `a2a.timeout`); pass the reply to `ReplyHandler`.
   - `PUSH` → push service client (Resilience4j retry + circuit breaker).
   - Mint the grant token **at delivery time** for task destinations, so a delayed message still gets a fresh token.
4. On success: `status: sent, sentAt`. On failure: `attempts++`, `nextAttemptAt = now + backoff(attempts)` (exponential, max 5 minutes), release the lease. After `maxAttempts` → `status: failed`, alert.
5. Delivery is at-least-once. Every consumer of these messages is idempotent (Flink by `agentVersion`, Orchestrator replies by `taskId`, execute tools by `quoteId`).

### 10.10 Notifier and activity
- `MessageTemplates` maps `ActivityType` (and push kinds) to fixed text with placeholders, e.g. `APPROVAL_REQUESTED` → "{merchantName} went from ${oldAmount} to ${newAmount}. Block future {merchantName} charges on {cardDisplay}?".
- `ActivityWriter.write(...)` is always called inside the same transaction as the state change it describes.
- Push payload: `{ customerId, title, body, deepLink, category: "agent_approval" | "agent_update", collapseKey: runId }`.

### 10.11 Kill switch (`KillSwitchService`)
- Pauses agents in batches (section 9.4), each batch in one transaction with outbox `agent-control remove`.
- Request flag `expirePending` (default `false`). When `false`, pending approvals stay open and the user can still approve or deny them. When `true`, they are expired in the same batches with reason `kill_switch`, and the user is notified.

---

## 11. Observability

### 11.1 Metrics (Micrometer, Prometheus names)

| Metric | Type | Tags |
| --- | --- | --- |
| `automation_run_transitions_total` | counter | `from`, `to`, `template` |
| `automation_runs_in_state` | gauge | `state` |
| `automation_trigger_duplicates_total` | counter | `template` |
| `automation_approvals_decided_total` | counter | `decision` (approved, denied, expired) |
| `automation_approval_time_to_decide_seconds` | histogram | `template` |
| `automation_dispatch_latency_seconds` | histogram | `phase`, `mode` (kafka, a2a) — task sent to reply received |
| `automation_outbox_pending` | gauge | `destination` |
| `automation_outbox_oldest_pending_seconds` | gauge | `destination` |
| `automation_outbox_failed_total` | counter | `destination` |
| `automation_stuck_runs_total` | counter | `state`, `action` (retried, failed) |
| `automation_policy_rejections_total` | counter | `code` |
| `automation_confirmation_rejections_total` | counter | `reason` |

### 11.2 Logs
- Structured JSON. Every line carries `traceId`, `customerId` (hashed), `agentId`, `runId` where known.
- Log every state transition at INFO: `run_transition from=… to=… reason=…`.
- Security events at WARN with `security=true`: `preview_changed`, `confirmation_invalid`, `step_up_failed`, ownership mismatch.

### 11.3 Tracing
- OpenTelemetry. Propagate `traceId` into every `AutomationTask` and back via the reply, so one trace covers trigger → preview → approval → execute.

### 11.4 Alerts (define in your monitoring tool)
- `automation_outbox_oldest_pending_seconds > 120` for 5 minutes.
- Any `automation_outbox_failed_total` increase.
- `EXECUTING → FAILED` with reason `execute_timeout` (outcome unknown, needs a human).
- Consumer lag on `trigger-fired` > 10,000 for 10 minutes.

---

## 12. Error handling and resilience rules

| Situation | Behaviour |
| --- | --- |
| Duplicate `trigger_fired` | Unique index rejects the insert; log INFO; ack |
| Stale or duplicate reply | `taskId` mismatch or transition returns empty; log INFO; ack |
| MongoDB transient error | `TransactionRunner` retries up to 3 times; then the Kafka message is not acked and is redelivered |
| Poison Kafka message (bad JSON, unknown schemaVersion) | 5 retries with backoff, then `<topic>.dlt` with the error |
| Push service down | Outbox retries with backoff; circuit breaker opens after 50% failures in 30 s |
| cvams down during approve | 503 `step_up_unavailable`; approval stays pending; app shows retry |
| A2A Supervisor timeout (a2a mode) | Treated as a retryable error reply (section 10.5 step 4) |
| Agent deleted while a run is in flight | Run continues to its next transition; the execute task is still sent only if already approved; the grant is revoked, so the MCP grant check blocks the execute and the run ends `FAILED` reason `grant_revoked` |
| Clock | Inject `java.time.Clock` everywhere; tests use a fixed or mutable clock |

---

## 13. Running in isolation (`standalone` profile)

The service must start and run the full flow **on a laptop with only a JDK**: no Docker, no shared MongoDB or Kafka, and no other Fargo component.

```bash
./gradlew bootRun --args='--spring.profiles.active=standalone'
```

### 13.1 What the `standalone` profile provides

| Real dependency | Standalone replacement | Notes |
| --- | --- | --- |
| MongoDB cluster | Embedded MongoDB (Flapdoodle `de.flapdoodle.embed.mongo.spring3x`), started as a **single-node replica set** (`rs0`) so transactions work | Data in a temp directory, wiped on restart. Set `automation.standalone.mongoDataDir` to keep it |
| Kafka cluster | Embedded Kafka broker (`EmbeddedKafkaKraftBroker` from `spring-kafka-test`), started in-process on a random port; the five topics plus `.dlt` topics are created at startup | `spring.kafka.bootstrap-servers` is set from the broker at startup |
| Flink trigger job | `DevTriggerController`: `POST /v1/dev/trigger` publishes a `trigger-fired` message (body as in 7.1; defaults filled in) | Also `POST /v1/dev/trigger/{agentId}` with only `periodId` |
| Orchestrator + Supervisor (kafka mode) | `FakeOrchestrator`: consumes the orchestrator input topic and, after `automation.standalone.replyDelay` (default 2 s), publishes a canned `automation-replies` message | Canned replies per intent in `standalone/replies/*.json`; `POST /v1/dev/replies/next` overrides the next reply (e.g. an error) |
| Supervisor (a2a mode) | `FakeSupervisorA2a`: an in-process `TaskDispatcher` target returning the same canned replies | Selected automatically when `dispatch.mode=a2a` |
| Push service | `StubPushClient`: logs the push and stores it in memory | `GET /v1/dev/pushes` lists sent pushes |
| cvams step-up | `StubCvamsClient`: accepts token `dev-stepup-ok`, rejects anything else | |
| Accounts API (PolicyChecker rule 4) | `StubAccountsClient`: every token starting `acct_tok_` or `card_tok_` belongs to the caller | |
| API gateway customer JWT | `DevAuthFilter`: reads header `X-Dev-Customer-Id` | |
| MCP mTLS identity | `DevAuthFilter`: header `X-Dev-Caller: mcp` | |
| Admin SSO | `DevAuthFilter`: header `X-Dev-Caller: admin` | |
| KMS signing key | RS256 key pair generated in memory at startup | JWKS endpoint serves it as usual |
| Templates | `TemplateSeeder` loads `standalone/templates/*.json` at startup: `payday_sweep`, `subscription_price_guard`, `weekly_bill_check`, `foreign_charge_guard` | One per mode, so every branch can be exercised |

### 13.2 `application-standalone.yml`

```yaml
automation:
  standalone:
    replyDelay: PT2S
    mongoDataDir: ""            # empty = temp dir
  dispatch:
    mode: kafka
de:
  flapdoodle:
    mongodb:
      embedded:
        version: 7.0.12
spring:
  mongodb:
    embedded:
      storage:
        repl-set-name: rs0
logging:
  level:
    com.wf.fargo.automation: DEBUG
```

### 13.3 Build setup

- Flapdoodle, `spring-kafka-test` and WireMock are declared as `developmentOnly` (for `bootRun`) **and** `testImplementation`. `developmentOnly` dependencies are excluded from the production `bootJar`.
- Every class in `standalone/` is annotated `@Profile({"standalone", "test"})`.
- Add a build check that fails if any `standalone` class or the libraries above end up in the `bootJar` (inspect the jar contents in a Gradle task).
- **First run downloads MongoDB binaries.** Inside the bank network, point Flapdoodle at your internal artifact mirror (Flapdoodle's download-path setting) or pre-provision the binaries, so the first start doesn't need internet access.

### 13.4 Trying the full flow by hand

```bash
H='-H Content-Type:application/json -H X-Dev-Customer-Id:cust_1'
M='-H Content-Type:application/json -H X-Dev-Customer-Id:cust_1 -H X-Dev-Caller:mcp'

# 1. User taps Create -> confirmation token
curl -s $H -X POST localhost:8080/v1/confirmations \
  -d '{"templateId":"payday_sweep","templateVersion":1,"params":{"from":"acct_tok_chk","to":"acct_tok_sav","amountAbove":5000}}'

# 2. Builder creates the agent through the MCP endpoint (use the token from step 1)
curl -s $M -X POST localhost:8080/v1/agents \
  -d '{"templateId":"payday_sweep","templateVersion":1,"params":{...same...},"confirmationToken":"<token>","sourceUtterance":"Move anything over 5000 on payday","sessionId":"s1"}'

# 3. Simulate Flink: payroll lands
curl -s $H -X POST localhost:8080/v1/dev/trigger/<agentId> -d '{"periodId":"2026-10-15"}'

# 4. After ~2 s the fake orchestrator replies with a preview; check the push and the approval
curl -s $H localhost:8080/v1/dev/pushes

# 5. Approve with the stub step-up token
curl -s $H -H X-StepUp-Token:dev-stepup-ok -X POST localhost:8080/v1/approvals/<approvalId>/approve

# 6. After ~2 s the run is done
curl -s $H localhost:8080/v1/customers/cust_1/activity
```

### 13.5 Safety rules for standalone code
- `DevAuthFilter`, the `/v1/dev/**` endpoints and every stub must not load in any profile other than `standalone` and `test`. Add a test that starts the app with the `default` profile and asserts `/v1/dev/trigger` returns 404 and `X-Dev-Customer-Id` is ignored.
- Standalone never connects to a real cluster: if `spring.kafka.bootstrap-servers` or the Mongo URI is set to a non-local host while `standalone` is active, fail startup with a clear message.

---

## 14. Testing strategy

### 14.1 Unit tests
- `RunStateMachine`: every allowed and disallowed pair (table-driven, 81 cases).
- `PolicyChecker`: each rule, valid and invalid.
- `CanonicalJson` + `Hashing`: key order and number formatting don't change the hash.
- `ScheduleCalculator`: cron in `America/New_York` across both DST changes.
- `MessageTemplates`: every `ActivityType` renders with no missing placeholders.
- `GrantTokenSigner` and confirmation tokens: valid, expired, wrong audience, tampered.

### 14.2 Integration tests (embedded MongoDB replica set + embedded Kafka, WireMock; no Docker)
- Base classes `EmbeddedMongoTest` and `EmbeddedKafkaTest` start the same embedded infrastructure as the `standalone` profile once per test JVM and clean collections and topics between tests.
- `RunStateStore` concurrency: 50 threads try `AWAITING_APPROVAL → EXECUTING` and `AWAITING_APPROVAL → EXPIRED` on the same run; exactly one wins (repeat 100 times).
- Transaction rollback: an exception after the state change rolls back the state change, activity and outbox together.
- Outbox: kill delivery between Kafka send and `status: sent` → message is sent again (duplicate), never lost.
- Each REST endpoint: happy path, ownership violation (404), validation errors.
- Kafka consumers ack only after commit (simulate a commit failure → message redelivered).

### 14.3 BDD scenarios (`src/test/resources/features`, Cucumber)

`payday_sweep.feature`
```gherkin
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
```

`subscription_guard.feature`
```gherkin
Feature: Subscription price guard
  Scenario: Denied price increase updates the baseline
    Given customer "cust_2" has a "subscription_price_guard" agent "agt_2"
    When a trigger fires for "agt_2" with merchant "m_streamflix" from 15.49 to 17.99
    And the preview reply returns quote "Q-9"
    And the customer denies the approval
    Then the run is "denied"
    And a baseline update for "m_streamflix" with amount 17.99 is published
```

`agent_lifecycle.feature`
```gherkin
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
```

`act_then_tell.feature` and `read_mode.feature`: one scenario each covering the mode branch in section 10.5.

---

## 15. Build phases and Cursor prompts

Each phase: Plan mode first, then Agent mode. Every prompt starts with `Using @docs/automation-service-spec.md and the rules in .cursor/rules,` (omitted below for brevity).

### Phase 0 — Scaffold
**Prompt:** "Create the Spring Boot 3.3 / Java 21 project with the package structure in section 4 and the `application.yml` in section 5 bound to a typed `AppProperties` record. Set up the `standalone` profile infrastructure from section 13.1–13.3 only: embedded MongoDB as a single-node replica set, embedded Kafka with the topics created at startup, `DevAuthFilter`, the `developmentOnly` dependencies and the bootJar check. Add test base classes `EmbeddedMongoTest` and `EmbeddedKafkaTest` and a health endpoint. Add Cucumber with an empty feature runner. No Docker, no Testcontainers."
**Done when:** `./gradlew test` passes with Docker not installed; `./gradlew bootRun --args='--spring.profiles.active=standalone'` starts with only a JDK; a test commits a MongoDB multi-document transaction on the embedded replica set; the bootJar check passes.

### Phase 1 — Domain and state machine
**Prompt:** "Implement the enums (6.1), `RunStateMachine` (6.2) and document records for every collection (6.3). Write the table-driven state machine tests first (all 81 pairs). Implement `CanonicalJson` (RFC 8785) and `Hashing` with tests from 14.1."
**Done when:** all unit tests pass; no Spring dependency in `domain/`.

### Phase 2 — Persistence
**Prompt:** "Implement repositories, `IndexInitializer` (6.4, idempotent), `TransactionRunner`, `RunStateStore` and `ApprovalStore` exactly as in 10.1. Write the concurrency and rollback integration tests from 14.2 first."
**Done when:** concurrency test passes 100 runs in a row; running `IndexInitializer` twice is harmless; no TTL index on audit collections (assert in a test).

### Phase 3 — Outbox
**Prompt:** "Implement `OutboxWriter`, `OutboxRelay` (change stream + polling, leases, backoff) and `OutboxDeliverer` with Kafka and Push delivery as in 10.9. Leave A2A delivery as an interface for Phase 6. Write the crash-between-send-and-mark test first."
**Done when:** two relay instances never send the same message concurrently; failed messages back off and end as `failed` after `maxAttempts`.

### Phase 4 — Templates, confirmations, agents and grants
**Prompt:** "Implement `TemplateService`, `PolicyChecker` (10.2), confirmation tokens (8.3), `GrantService`, endpoints 1–5 and 10–12 (section 9), and `AgentService.create` (10.3). Start with the `agent_lifecycle.feature` scenarios and endpoint integration tests."
**Done when:** `agent_lifecycle.feature` passes; a create emits exactly one `agent-control` upsert; requests listing extra tools never change the grant.

### Phase 5 — Grant tokens and grant lookup
**Prompt:** "Implement `GrantTokenSigner` (8.2), `GET /.well-known/jwks.json` and `GET /v1/grants/{grantId}` (9.3) with mTLS-only access. Tests first: token claims, expiry, JWKS verification with the public key."
**Done when:** a token minted by the service verifies against the JWKS; the grant endpoint rejects non-MCP callers.

### Phase 6 — Triggers and dispatch
**Prompt:** "Implement `TriggerFiredConsumer`, `RunService.start` (10.4), `TaskFactory` (7.4), `TaskDispatcher` with `KafkaOrchestratorDispatcher` and `A2aSupervisorDispatcher` selected by `automation.dispatch.mode` (2.1), and A2A delivery in the outbox. Tests first: duplicate trigger, paused agent, daily limit, both dispatch modes with WireMock for A2A."
**Done when:** duplicate and paused scenarios in `payday_sweep.feature` pass in both dispatch modes.

### Phase 7 — Replies
**Prompt:** "Implement `AutomationReplyConsumer` and `ReplyHandler` (10.5) for all three modes, retryable and non-retryable errors, and stale replies. Tests first, plus `act_then_tell.feature` and `read_mode.feature`."
**Done when:** each mode ends in the correct state with the correct activity and push.

### Phase 8 — Approvals and step-up
**Prompt:** "Implement endpoints 6–8, `ApprovalService` (10.6) and `StepUpVerifier` (8.4, WireMock cvams). Tests first: approve, deny, approve after expiry, double approve, preview_changed, step-up failure, cvams down."
**Done when:** `payday_sweep.feature` and `subscription_guard.feature` pass end to end.

### Phase 9 — Scheduler and sweeper
**Prompt:** "Implement `ScheduleCalculator`, `ScheduleTriggerJob` (10.7), `ApprovalExpiryJob` and `StuckRunJob` (10.8) with ShedLock. Use an injectable Clock. Tests first: DST, two instances claiming the same occurrence, expiry, stuck preview retry, stuck execute re-sent with the same quoteId."
**Done when:** the "Approval expires" scenario passes; no occurrence fires twice across two instances.

### Phase 10 — Notifications, activity, admin
**Prompt:** "Implement `MessageTemplates`, `Notifier`, `ActivityWriter` (10.10), endpoint 9, the kill switch (9.4, 10.11) and template publishing (endpoint 16). Tests first."
**Done when:** every `ActivityType` has a template; kill switch pauses 10,000 agents in batches and publishes a remove message per agent.

### Phase 11 — Security hardening
**Prompt:** "Implement section 8.1 caller authentication for the three caller types, ownership checks returning 404, raw account number rejection (8.5), token masking in logs, and security event logging. Tests first for each rule."
**Done when:** a request for another customer's agent returns 404; no log line contains a token or a 13–19 digit sequence (add a log-capture test).

### Phase 12 — Observability
**Prompt:** "Add the metrics in 11.1, structured logging and tracing (11.2–11.3), and trace propagation through tasks and replies. Add tests that assert the key metrics change."
**Done when:** one trace id is visible from trigger to execute in an integration test.

### Phase 13 — End-to-end
**Prompt:** "Complete the remaining standalone pieces from section 13.1 (FakeOrchestrator, FakeSupervisorA2a, DevTriggerController, stub clients, TemplateSeeder with the four templates, `/v1/dev/pushes`, `/v1/dev/replies/next`) and the safety rules in 13.5. Then run all feature files against the full service on embedded MongoDB and Kafka, with WireMock for push, cvams and A2A. Fix any failures. Generate the OpenAPI spec for all endpoints and save it to docs/openapi.yaml."
**Done when:** section 16 is satisfied and the manual walkthrough in 13.4 works end to end in `standalone`.

---

## 16. Definition of done

- [ ] All unit, integration and BDD tests pass in CI on a runner without Docker.
- [ ] The service starts with `--spring.profiles.active=standalone` on a machine with only a JDK, and the 13.4 walkthrough completes.
- [ ] The production `bootJar` contains no `standalone` classes, embedded MongoDB, embedded Kafka or WireMock.
- [ ] Both dispatch modes (`kafka`, `a2a`) pass the BDD suite.
- [ ] Concurrency, crash and duplicate tests pass 100 runs in a row.
- [ ] OpenAPI spec generated and reviewed (16 endpoints).
- [ ] Kafka message samples in `src/test/resources/samples` match section 7 and are shared with the Flink, Orchestrator and MCP teams.
- [ ] Metrics, dashboards and alerts from section 11 exist.
- [ ] Security review signed off: tokens, ownership, grants, logging.
- [ ] Runbook covers the kill switch, replaying `.dlt` messages, failed outbox messages and `execute_timeout` runs.

---

## 17. Open items to confirm before Phase 6

| Item | Owner | Default assumed here |
| --- | --- | --- |
| Real name of the Orchestrator input topic | Orchestrator team | `fargo-orchestrator-input` |
| Whether the Orchestrator can route `channel=automation` replies to `automation-replies` | Orchestrator team | Yes |
| A2A task and push-notification support in the Supervisor for `a2a` mode | Supervisor team | Synchronous A2A call with 60 s timeout |
| cvams step-up context binding to an approvalId | cvams team | Supported |
| Push service API and deep-link format | Mobile platform | `wf://fargo/approvals/{approvalId}` |
| Key management for RS256 grant signing keys | Security | Bank KMS with JWKS rotation |
| Accounts API for token ownership checks (`PolicyChecker` rule 4) | Accounts team | Available |
| Retention period for runs, approvals and activity | Compliance | 7 years, archived after 13 months |
