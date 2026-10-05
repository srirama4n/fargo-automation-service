# Security review

Reviewed against sections 8 and 11.2 of the automation service spec. The controls below are in the service and covered by tests.

## Tokens

- Customer and admin callers present a Bearer JWT. Issuer, audience, and expiry are checked. A bad token is 401 even when `X-Dev-Customer-Id` is also set (`CallerAuthenticationTest`).
- Grant tokens are RS256, minted at task delivery, and the public key is on `GET /.well-known/jwks.json`. The grant lookup is MCP-only.
- Confirmation tokens are single use, bound to the draft hash, and expire. A mismatched draft is 403 `confirmation_invalid`.
- Step-up uses `X-StepUp-Token` and cvams. Failure is 401 `step_up_required` and logs `security=true step_up_failed` without the token.
- Dev auth (`X-Dev-Customer-Id`, `X-Dev-Caller`) loads only on the `standalone` and `test` profiles.

## Ownership

- App reads and writes use the authenticated customer id. Another customer's agent or approval is 404 `not_found`.
- The ownership log is `security=true ownership_mismatch` and does not include either customer id.
- MCP may pass `X-Fargo-Customer-Id` only when the caller is MCP. The header alone is ignored.

## Grants

- `allowedTools` is copied from the template's `requiredTools`. Extra tools on the create request are ignored (`agent_lifecycle.feature`).
- Delete revokes the grant and expires pending approvals in the same transaction.
- No execute task is sent for `mode=ask` without an approved approval whose preview hash matches the stored quote.

## Logging

- Account and card tokens are masked to the last four characters. Sequences of 13–19 digits are redacted (`SecurityRulesTest`).
- Grant tokens and confirmation tokens are not logged.
- Customer ids on run logs are SHA-256 hashes.
- Raw account numbers in requests are 400 `raw_account_number` and the value is not logged.
- Customer-facing text comes from `MessageTemplates`, not from model output.

## Standalone

- `/v1/dev/**` is absent on the default profile and returns 404.
- `X-Dev-Customer-Id` does not authenticate a default-profile request.
- Standalone startup fails when Kafka or MongoDB is configured at a non-local host.
