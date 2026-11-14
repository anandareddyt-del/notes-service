# Team Notes API

A backend REST service for a note-taking tool shared across several small teams.
Users can create notes, share them with individual teammates or whole teams
(read or write), and edit them concurrently without silently losing each other's
changes.

## Running it

Requires JDK 21. No other install is needed - the project uses the Maven
Wrapper (`mvnw`/`mvnw.cmd`) and runs on an in-memory H2 database by default.

```
./mvnw spring-boot:run      # macOS/Linux
mvnw.cmd spring-boot:run    # Windows
```

The service starts on `http://localhost:8080`. There's no real auth (see
"What I'd change" below) - every request identifies its caller via an
`X-User-Id` header, a deliberate stand-in for a real principal.

```bash
# create a user directly via the H2 console (http://localhost:8080/h2-console,
# JDBC URL jdbc:h2:mem:notes), or seed one some other way, then:

curl -X POST localhost:8080/api/v1/notes \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"title":"First note","body":"hello"}'

curl localhost:8080/api/v1/notes/1 -H "X-User-Id: 1"

curl -X PUT localhost:8080/api/v1/notes/1 \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"title":"First note","body":"edited","version":0}'

curl -X POST localhost:8080/api/v1/notes/1/shares \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"principalType":"USER","principalId":2,"permission":"WRITE"}'
```

**Postgres**, the intended production datastore, is included via
`docker-compose.yml` but is not the default - see "On H2 vs Postgres" below for
why, and run `docker compose up -d` + `spring.profiles.active=postgres` to use it.

### Running tests

```
./mvnw test
```

8 tests: 5 pure-unit tests of the access-control logic (Mockito, no DB) and
2 integration tests against real H2 persistence proving the optimistic-lock
and owner-only-resharing mechanisms actually work, not just in theory - writing
the concurrency test caught a real bug in the first implementation (see commit
history).

## Design choices I spent the most time on

### 1. Sharing / permission model

A note has exactly one owner (`Note.owner`, non-null). Access beyond the owner
is granted explicitly via `NoteAcl` rows, to either a user or a team, never
both (enforced by a DB check constraint, not just application logic) - never
through any implicit or global visibility path. A user's effective access =
owned ∪ directly shared with them ∪ shared with any team they belong to.

This is the one requirement in the brief that isn't boilerplate CRUD - "shared
amongst several small teams" is the actual hard part, and it shapes the schema,
every access-check query, and the authorization logic.

**Invariants this guarantees:** deny-by-default (no grant = no access); team
access is live, not cached (removing someone from a team revokes access on
their very next request); no privilege escalation (a WRITE collaborator can
edit content but can never reshare - only the owner can, see
`NoteService.requireOwner`); no existence leakage for a caller with zero access
(`404`, not `403` - see the error-model section below).

**Tradeoff accepted:** no role hierarchy (no "admin" tier between owner and
collaborator), two permission levels only (READ/WRITE). Simple and matches the
actual need; a general RBAC engine would be solving a problem this brief
doesn't have.

### 2. Concurrency control on edits

Optimistic locking via `Note.version` (JPA `@Version`). A client must send back
the version it last read; `NoteService.updateNote` compares it against the true
current version on a fresh read and rejects a stale write with `409` before any
mutation happens - the server never auto-retries or silently merges, because
that would reintroduce the exact lost-update bug this exists to prevent.

Once a note can be edited by more than one team member (the sharing model
above), last-write-wins silently destroys data. This was worth real
implementation time specifically because it's cheap (one column, one
comparison) and directly testable - and writing the test for it caught a real
bug: an earlier version of this code tried to force the version check by
manually overwriting the entity's version field before `save()`, which doesn't
reliably drive Hibernate's own WHERE-clause generation for an
already-attached entity. The fix was an explicit comparison against a freshly
read value instead, letting Hibernate's own untouched auto-increment continue
to handle the genuinely-concurrent case. (Full before/after in the commit
history.)

**Tradeoff accepted:** no real-time collaborative merge. A conflict means
"re-fetch and redo your edit," not "we merged it for you" - correct for
async note-taking, not for live co-editing.

### 3. Relational schema & transactional integrity (why not NoSQL)

Postgres-shaped schema (currently running on H2 - see below), with foreign-key
and check-constraint-enforced integrity between `app_user`, `team`,
`team_membership`, `note`, and `note_acl`.

The dominant read - "every note visible to user X" - is a join across notes,
ACL grants, and team membership: inherently relational. A document store would
mean either denormalizing ACL/team data into every note document (so a
team-membership change means rewriting every affected note to stay consistent,
breaking the "access is live" invariant above) or doing the joins in
application code anyway, which gives up free referential integrity and
multi-row transactions while keeping none of NoSQL's actual advantages.
"Several small teams" is also explicitly not a write-scale problem, so NoSQL's
real strength - horizontal write throughput - isn't a strength this system
needs.

**Tradeoff accepted:** vertical-scale-first design, no horizontal sharding, no
eventual consistency. Correct at the stated scale; the limit of that choice is
named explicitly below.

## On H2 vs Postgres

Postgres was and is the intended production datastore - the schema, JPA
mappings, and optimistic-locking semantics above are unchanged by which engine
runs them, and `docker-compose.yml` + a `postgres` Spring profile are included.
The build machine didn't have Docker installed, and getting it running required
enabling WSL2, which needed a restart mid-assessment that couldn't be completed
in the time window. Rather than silently ship H2 as if it had been the original
choice, or lose the remaining time entirely, the pragmatic call was to run on
H2 by default so the service is guaranteed runnable right now, and say so
here plainly. Swapping to Postgres is `spring.profiles.active=postgres` plus
`docker compose up -d` away.

## Trade-offs summary

| Decision | Gain | Cost |
|---|---|---|
| Owner + ACL (user or team) instead of general RBAC | Simple, unambiguous accountability | No admin tier, no org-wide default policies |
| Optimistic locking instead of real-time merge | Cheap, prevents silent lost updates, directly testable | Conflicts require manual re-edit, not live co-editing |
| Relational (Postgres-shaped) instead of NoSQL | Free referential integrity, atomic multi-row writes, natural fit for ACL joins | No horizontal write-scale story if team count explodes |
| Header-based stub principal instead of real auth | Lets the sharing/concurrency logic be exercised end to end within the time box | Not real authentication - see below |
| Single service, no API gateway | Nothing extra for a reviewer to stand up | No centralized rate-limiting/routing - correct only while there's one service |

## What I'd change, add, or stop doing with more time

- **Real authentication.** The `X-User-Id` header is a stub. Production would
  use short-lived, sender-constrained JWTs (DPoP for user clients) so a leaked
  bearer token alone is useless, not full mTLS - this is a single service with
  human end users, not a service mesh, so mTLS would be solving a problem this
  system doesn't have.
- **Wire up Postgres as the running default**, once Docker/WSL2 is available -
  the schema and code are already written for it.
- **Never print stack traces to a client** - already true today
  (`GlobalExceptionHandler` never puts an exception message/stack trace in a
  response body; `server.error.include-stacktrace=never` is set explicitly
  rather than left as an assumed default). What I'd add: a real log aggregator
  so the `X-Request-Id` trace correlation is actually queryable, not just
  grep-able in local logs.
- **DB resilience**: bounded connection pool + query timeouts, and retry with
  exponential backoff specifically for transient infrastructure failures (DB
  unreachable) - explicitly never for optimistic-lock conflicts, which must
  never be retried automatically. Not yet implemented; named here rather than
  silently skipped.
- **Authorization/entitlement caching**, once the ACL join shows up in a slow
  query log - short TTL, invalidated on every write to `note_acl` or
  `team_membership`, so the "access is live" invariant holds even with caching
  added. Premature at "several small teams" scale today.
- **ETag/conditional GET** using the existing `version` column - nearly free,
  not yet wired into the controller.
- **API gateway** - deliberately not added now (single service, nothing to
  route between); the right call today, the wrong one the moment a second
  service (e.g. search, notifications) exists.
- **Stop**: resist adding a role hierarchy or general RBAC until there's an
  actual requirement for it - the two-level owner/collaborator model is doing
  its job at this scale, and more flexibility now would be speculative.

## Assumptions

The brief leaves scope open by design. Where it doesn't specify something,
here's what was assumed and why:

| Assumption | Why |
|---|---|
| Notes can have concurrent writers | "Shared amongst teams" implies more than one person can hold write access at once |
| Sharing happens at both user and team granularity | The brief says "shared amongst teams" but doesn't rule out sharing with one teammate directly |
| A note's owner is a single person, never a team | Avoids a second, nested RBAC problem over who inside a team could act as owner |
| Scale is "several small teams," not internet-scale | Taken directly from the brief's own wording; justifies not building CQRS, partitioning, or an API gateway |
| Permission levels are read/write only | Simplest model that satisfies "capture and work with their notes" |
| The repo must be reviewer-accessible with no extra permissions | "Submit a link... that reviewers can access" - repo is public |

## Commit history

Built and committed iteratively rather than as one final dump - scaffold,
then domain model, then access-control/service layer, then the REST API and
error model, then tests (which caught and fixed a real concurrency bug). See
`git log` for the full sequence and reasoning in each commit message.
