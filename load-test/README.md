# settlement-service load test

**Target** (sportsbook/CLAUDE.md, Level 2): settle **10,000 bets in < 10 s**, with
**zero double payouts**.

## Why a JVM harness, not a k6 HTTP script

The other services expose a high-throughput HTTP ingest that k6 hammers directly.
Settlement is **event-driven** — it ingests `MatchResult` over Kafka — and its
only HTTP surface is the low-rate operator API (status / replay / void). So the
throughput proof drives the settle engine directly from a JVM harness
([`SettlementThroughputLoadTest`](../src/test/java/com/sportsbook/settlement/load/SettlementThroughputLoadTest.java),
tagged `@Tag("load")`, Testcontainers PostgreSQL + WireMock wallet): seed N
PENDING bets, settle them in parallel, and time the wall clock.

This isolates the part settlement owns — **resolve → wallet credit → persist
transition → outbox** — from the broker, which scales independently with
partitions and consumer instances.

## Run

```sh
# full payout path (2 wallet credits per bet) — worst case
mvn test -Dsurefire.excludedGroups= -Dtest=SettlementThroughputLoadTest \
  -Dsettlement.load.bets=10000 -Dsettlement.load.threads=32

# settle engine only (LOST bets, no wallet round-trips)
mvn test -Dsurefire.excludedGroups= -Dtest=SettlementThroughputLoadTest \
  -Dsettlement.load.bets=10000 -Dsettlement.load.threads=32 -Dsettlement.load.outcome=LOST
```

The harness asserts every bet settled **exactly once** (`outbox.count() == N`):
the no-double-settle invariant holds at batch scale, and because every wallet
credit carries the same `betId`-derived idempotency key, the real wallet dedups
to a single payout.

## Results

Best numbers are in [`results/BEST.md`](results/BEST.md); per-run detail under
`results/<date>/`.
