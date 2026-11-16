# Retry Reservation Implementation Plan

> Execute inline with TDD. Scope: persistent retry reservation and manual execution when due, not a scheduler or lease recovery.

**Goal:** Retryable failure reserves RETRY_WAIT/next_attempt_at and preserves one row per attempt, at most five attempts including the first.

**Architecture:** Keep existing claim → HTTP outside transaction → finish. Atomic claim increments attempt_count and accepts only PENDING or due RETRY_WAIT below max. RetryPolicy classifies results and determines DB-relative delay. List uses latest-attempt lateral join to avoid duplicate jobs.

## Policy

- SUCCESS → SUCCEEDED, next timestamp NULL.
- TIMEOUT/NETWORK_ERROR, HTTP408/429/5xx → RETRY_WAIT if count<5, otherwise DEAD.
- Other HTTP4xx/3xx, URL_BLOCKED, interrupted UNKNOWN → DEAD. No blind replay of ambiguous unfinished jobs.
- Backoff5/10/20/40sec ±20% jitter. 429 Retry-After delta seconds or HTTP date: cap at10min, do not retry earlier than backoff; invalid values ignored.
- Existing DEAD/SUCCEEDED/PROCESSING rows and attempt records retained; no automatic resurrection.

## Files

V4 migration: attempt_count/next_attempt_at, backfill existing counts and sequential attempt_no, replace delivery unique constraint with (delivery_id,attempt_no), partial due index.
RetryPolicy + tests; LocalWebhookSender carries Retry-After; DeliveryTransactions claim/finish/list use sequence and scheduling; controller/docs reflect manual due retry.
Migration test covers V3→V4 preservation. Existing caller and API tests revised only where intended behavior changed.

## Tasks

- [x] Failing API retry test.
- [x] V4 and retry policy, sender header, claim/finish/list, Swagger.
- [x] Validate policies, immediate retry rejection, five-attempt exhaustion, eventual success, restart-independent schedule, history/list cardinality, due concurrency, inactive endpoint and terminal blocks, latest-claim result fencing.
- [x] Local migration preserves all previous state/history; no user jobs sent or reset.
- [x] Docs, independent review and full backend suite/build. Receiver code unchanged; GitHub CI not run because changes remain uncommitted under AGENTS.md.

## Boundary

There is no scheduler, lease owner/version recovery or exactly-once claim. Interrupted/result-storage-failed PROCESSING+UNKNOWN jobs still require the future recovery design; never reset manually. Existing local-only URL, proxy/redirect and HTTP/body limits remain unchanged.
