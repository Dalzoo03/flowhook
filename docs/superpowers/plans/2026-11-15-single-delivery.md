# Single Delivery Implementation Plan

> Implement inline with executing-plans and TDD. User authorized connecting FlowHook to the local demo receiver. Existing remote URLs must not be called automatically.

**Goal:** In explicit local mode, Swagger can list jobs and send one selected PENDING delivery, with attempt results and bounded HTTP.

**Architecture:** DeliveryService (NEVER transaction) → DeliveryTransactions.claim (short REQUIRES_NEW) → LocalWebhookSender HTTP → DeliveryTransactions.finish (short REQUIRES_NEW). Atomic conditional UPDATE claims a specific active PENDING row. One attempt per delivery in this phase. No scheduler, retries, lease recovery, signing or remote network access.

**Tech stack:** Existing Spring MVC/JDBC, PostgreSQL/Flyway, Java21 HttpClient.

## Files and contract

- V3 migration creates delivery_attempts with UNKNOWN initial outcome, timestamps, status/error, duration and response bytes ≤4096. UNIQUE delivery_id represents one-shot scope.
- LocalReceiverPolicy: default false. local profile allows only http://127.0.0.1:<configured trusted port>/webhooks and supported demo mode query. Apply to endpoint registration exception AND every actual send. No DNS names, redirects, proxies or arbitrary HTTPS requests.
- Deliveries controller (conditional local mode): GET /api/projects/{projectId}/deliveries (latest100) and POST /api/projects/{projectId}/deliveries/{deliveryId}/send. Fixed project1. Repeated/inactive/nonpending sends409, inaccessible404.
- Manual send API200 means attempt processed; payload reports SUCCEEDED/DEAD, HTTP/error and duration. 500/timeout/network/blocked URL recorded DEAD in this one-attempt lab; automatic retry policy is deferred.
- HttpClient: connect2s, response5s, whole future≤10s including body; cancel on timeout, no redirects/proxies; bounded subscriber cancels body beyond4096 bytes, binary snippet avoids Unicode/NUL storage failures.
- Swagger instructions, STATUS, progress008 and integration tests. Existing migration tests assert V3 fresh/baseline upgrade. Existing fixture cleanup uses cascade only in isolated containers.

## Tasks

- [x] API tests fail before implementation.
- [x] V3, policy, sender, short transactions, local-only API.
- [x] Tests: success500timeout, no HTTP during DB transaction, same-ID concurrency, paused endpoint, project boundary, blocked/redirect no-contact, response limit, finish failure leaves durable UNKNOWN, default mode safety.
- [x] Local V3 preserves existing application rows; no real delivery sent without user selecting it.
- [x] Document limitations and run full tests/receiver checks, independent code review, commit/push and GitHub CI.

## Known boundary

A process crash or result-storage failure after claim leaves PROCESSING plus UNKNOWN/unfinished attempt; do not reset or resend blindly. Lease-based recovery is a separate next step. One-shot terminal DEAD is not final V1 retry behavior. No exactly-once delivery guarantee. Manual IDs and local mode keep this learning slice reviewable.

## Verification ledger

- Docker initially stopped: started Docker and existing PostgreSQL without deleting volume. Observed missing send API test fail after Docker became available.
- 62 PostgreSQL-backed tests and assemble passed. Real loopback response/time/body limits tested. Independent read-only review found no critical/important findings.
- Local V3 applied, snapshots of endpoints/events/deliveries unchanged; no attempt/outgoing request created in user database. local-profile list and Swagger verified through packaged JAR.
- GitHub CI run 43610025906 passed both jobs for commit 9afe4d5.
