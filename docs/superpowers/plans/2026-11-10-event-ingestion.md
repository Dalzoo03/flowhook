# Event Ingestion Implementation Plan

> **For agentic workers:** Use executing-plans inline. Existing functional spec F-04 and the user request authorize this implementation.

**Goal:** POST /v1/events atomically stores an event and matched delivery jobs, with duplicate/conflict handling.

**Architecture:** Spring MVC → transactional EventService → JdbcClient/PostgreSQL. Project 1 is fixed until API-key authentication. Reuse the existing project advisory lock so endpoint registrations and event ingestion serialize per project. PostgreSQL JSONB equality compares payloads without lossy floating point conversion.

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3, PostgreSQL 17, Flyway, Testcontainers.

## Scope and files

- V2 migration: events and deliveries, unique project/client event ID, frozen URL/payload and immutable matched count.
- events package: bounded strict JSON parser, controller/error contract, service transaction, JDBC repository, response record.
- API tests: own isolated database; matching, rollback, duplicates, concurrency, JSON semantics, input boundaries, capacity, no rematching.
- Update existing endpoint test cleanup and Flyway migration assertions for V2.
- README, STATUS and progress/005-event-ingestion.md record the contract and verified limits.

## Tasks

- [x] Write failing event API tests; observe missing-route failures.
- [x] Add V2, parser, persistence, transaction and API/error handling.
- [x] Run test suite; fix failures without weakening acceptance criteria.
- [x] Check local V2 migration preserves endpoints; do not inject sample events into the user DB.
- [ ] Update docs, review diff, commit/push under existing authorization and confirm GitHub CI.

## Contract and limits

Required top-level eventId/type/data only, 1–128 character nonblank identifiers, object data, body at most 65536 bytes, duplicate JSON keys/nonstandard numbers rejected. Numbers parse as BigDecimal/BigInteger. JSONB limits (NUL, invalid Unicode, extreme numeric range) are rejected at input. Event cap 10000 per project; duplicate resolution comes first. Target snapshot and matching use one INSERT SELECT. External HTTP and attempts are out of scope. API-key auth remains missing, so this is not the full production F-04 contract.

## Verification

PostgreSQL-backed tests must prove rollback of event insert when delivery insertion fails, 100 identical concurrent requests yielding one event and one delivery per endpoint, payload equivalence/conflicts, exact size limits, frozen URL/payload, duplicate behavior after endpoint changes and after capacity is reached. Flyway tests cover fresh V1→V2 and baseline V1→V2 with data preserved.

## Execution notes

- 47 tests passed; endpoints preserved during local V2 migration. No sample events inserted into user DB.
- Flyway history metadata may include an ignored V1 SQL before the V1 baseline marker. Updated migration assertion to search for the explicit baseline marker instead of assuming list order; V2 execution and preservation remain asserted.
- Independent review completed: aligned numeric/name token limits with body budget; added failing-then-passing long token test. All 47 tests pass. GitHub CI remains pending.
