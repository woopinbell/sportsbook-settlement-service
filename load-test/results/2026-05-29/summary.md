# Settlement throughput — 2026-05-29

Harness: `SettlementThroughputLoadTest` (Testcontainers PostgreSQL 16 + embedded
Kafka + WireMock wallet), 32 settle threads, dev-host (Docker Desktop, macOS,
all components co-located).

| Run | Outcome | Bets | Wall clock (ms) | Throughput | Settled (outbox rows) |
|---|---|---|---|---|---|
| 1 | WON (full payout) | 10,000 | 16,987 | 589 bets/s | 10,000 |
| 2 | LOST (engine only) | 10,000 | 10,923 | 915 bets/s | 10,000 |

Command:

```sh
mvn test -Dsurefire.excludedGroups= -Dtest=SettlementThroughputLoadTest \
  -Dsettlement.load.bets=10000 -Dsettlement.load.threads=32 [-Dsettlement.load.outcome=LOST]
```

Observations:
- WON makes 2 wallet credits per bet → 20,000 WireMock round-trips dominate the
  17 s. LOST makes none → 10.9 s (~915 bets/s), close to the 1,000 bets/s target
  on a single un-pooled PostgreSQL.
- Both runs settled all 10,000 bets exactly once (one `BetSettled` outbox row
  each) — the no-double-payout invariant at scale.
