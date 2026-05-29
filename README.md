# settlement-service

> **English summary**
>
> **What it is.** `settlement-service` settles bets in the sportsbook
> microservice system. When a sports event finishes it resolves every affected
> bet slip — Single, Multiple, and System (K-of-N) — to WON / LOST / PUSH /
> VOID, pays out winnings, refunds voids, and records the outcome.
>
> **Architecture (ADR-0006 — async Saga + Outbox).** Settlement is fully
> event-driven, unlike the synchronous placement path (ADR-0017). It
> event-sources a local read model from `BetPlacedRequested` (no direct read of
> the betting-service DB — loose coupling). On a `MatchResult` it resolves the
> bets on that event; on an `EventLifecycle` CANCELLED / POSTPONED it voids them
> (ADR-0012). Winnings and refunds are credited to `wallet-service` over
> synchronous HTTP (`/credit`, `Idempotency-Key = betId`), guarded by a
> Resilience4j circuit breaker. Results are published as `BetSettled` /
> `BetVoided` through a transactional outbox; `betting-service` consumes them to
> flip `bet.status` to SETTLED / VOIDED. Failed settlements retry with backoff
> and ultimately route to a `*.DLT` dead-letter topic for admin replay.
>
> **Features.** Per-slip resolution maths: Multiple re-computes odds with voided
> selections removed; System(K-of-N) sums the payout of every winning K-subset
> (symmetric with betting-service's combination calculator). Idempotent
> settlement keyed by `betId` (DB unique constraint) so a re-delivered
> `MatchResult` never double-pays. Late-settlement window (24h, ADR-0012):
> result corrections inside the window auto-apply, afterwards only admin-api may
> replay.
>
> **Tech stack.** Java 17, Spring Boot 3.2, Maven. PostgreSQL 16 + Flyway
> (settled-bet read model + outbox). Kafka + Avro (no schema registry in V1).
> Resilience4j. Micrometer / OpenTelemetry / Prometheus. No Redis — settlement
> idempotency is the strong DB unique constraint, not a cache fast-path.
>
> **Build & run.** `mvn verify` runs Spotless, Checkstyle and the test suite;
> integration tests use Testcontainers, so Docker must be running, and
> `shared-protocol` 0.2.0 must be installed to mavenLocal first
> (`cd ../shared-protocol && mvn install`).
>
> **Performance.** Target: settle 10 000 bets in under 10 s with zero double
> payouts (see [`load-test/results/BEST.md`](load-test/results/BEST.md) once
> measured).
>
> **Limitations (V1).** No half-won / half-lost (Asian handicap is out of scope,
> ADR-0012) — System slips still settle each K-subset, but each selection is a
> whole WON / LOST / PUSH / VOID. No cash out. Per-selection outcomes are read
> from the `MatchResult.resultDetail` contract; settlement never re-derives a
> result from the raw score (boundary: it does not decide results). See
> ADR-0006 / 0008 / 0012 / 0013 in
> `orchestration/docs/architecture/decisions/`.
>
> **Docs.** Per-commit walkthroughs and the post-build retrospective live in
> [`docs/`](docs/README.md); the interview-critical topics are indexed under
> "L3 quick reference" in [`docs/commits/README.md`](docs/commits/README.md).

---

## 시스템에서의 위치

`settlement-service`는 9개 repo로 구성된 sportsbook 시스템의 **정산 도메인
서비스**다. 경기가 끝나면 해당 경기에 걸린 모든 베팅 슬립을 결과 판정하고
(won/lost/push/void), 당첨금·환불을 wallet에 반영한 뒤 결과를 publish한다.
베팅 *접수*는 동기 orchestration([ADR-0017](../orchestration/docs/architecture/decisions/0017-bet-placement-sync-orchestration.md))이지만,
*정산*은 비동기 Saga + Outbox([ADR-0006](../orchestration/docs/architecture/decisions/0006-messaging-and-saga.md))로
느슨하게 결합한다.

```
┌──────────────────────────────────────────────────────────────────────┐
│ shared-protocol  ←── settlement-service (this repo)                   │
│                                                                        │
│ betting ──outbox──→ Kafka: BetPlacedRequested ──→ settlement (read model)│
│ odds-feed ──→ Kafka: MatchResult / EventLifecycle ──→ settlement       │
│                  │                                                      │
│                  │  ──HTTP /credit──→ wallet-service (payout / refund)  │
│                  └──outbox──→ Kafka: BetSettled / BetVoided             │
│                                  └──→ betting-service (SETTLED/VOIDED)  │
└──────────────────────────────────────────────────────────────────────┘
```

상세 의존성 그래프와 cross-cutting 결정은 상위 폴더의
[`sportsbook/CLAUDE.md`](../CLAUDE.md) 와 ADR 디렉터리를 참조한다.

## 책임 범위

**한다**:

- Kafka subscribe:
  - `bet.placed.v1` — `BetPlacedRequested` 누적 → 자체 정산 read model 구축
    (event sourcing, betting DB 직접 read 안 함)
  - `match.result` — 경기 결과 → 해당 경기 베팅 정산 트리거
  - `event.lifecycle` — CANCELLED/POSTPONED → void 경로 ([ADR-0012](../orchestration/docs/architecture/decisions/0012-v1-scope-decisions.md))
- 베팅별 결과 판정 ([ADR-0008](../orchestration/docs/architecture/decisions/0008-betting-domain-model.md)):
  - Single: selection WON/LOST/PUSH/VOID
  - Multiple: 전부 WON→WON, 하나라도 LOST→LOST, VOID는 제외하고 odds 재계산
  - System(K-of-N): 조합별 판정 → winning combo payout 합
- WON/PUSH → wallet `/credit` (HTTP, `Idempotency-Key=betId`, Resilience4j)
- 정산 결과 publish (outbox): `BetSettled` / `BetVoided`
- **멱등 정산** — 같은 `betId` 재정산 차단 (DB unique constraint)
- 정산 실패 3회 backoff 재시도 → DLQ(`<topic>.DLT`) + 운영 알람 ([ADR-0006](../orchestration/docs/architecture/decisions/0006-messaging-and-saga.md))
- 재정산 / 상태 조회 / 수동 void REST (운영용, admin-api가 호출)

**하지 않는다**:

- 베팅 접수 (betting-service 책임)
- 잔고 직접 변경 (wallet-service 위임)
- **경기 결과 자체 결정** — 외부 데이터 소스(odds-feed)가 주며, settlement은
  `MatchResult.resultDetail`의 selection별 결과를 읽어 *조합 로직*만 수행한다

## 빌드 / 실행 / 테스트

```sh
# shared-protocol 0.2.0 먼저 mavenLocal 설치 (의존 라이브러리)
cd ../shared-protocol && mvn install && cd -

# 컴파일
mvn compile

# 전체 검증 (Spotless + Checkstyle + 테스트). Docker 필요 (Testcontainers).
mvn verify

# 포맷 자동 적용
mvn spotless:apply
```

로컬 실행은 PostgreSQL/Kafka가 필요하다 (Redis 불필요). 환경변수
(`SETTLEMENT_DB_URL`, `SETTLEMENT_KAFKA_BOOTSTRAP`, `WALLET_BASE_URL`,
`SETTLEMENT_HTTP_PORT` 기본 8084)로 엔드포인트를 주입한다.

## 노출 인터페이스

- Kafka subscribe: `bet.placed.v1`, `match.result`, `event.lifecycle`
- Kafka publish (사후, outbox): `BetSettled`, `BetVoided`
- HTTP REST (`/internal/v1`):
  - `GET  /internal/v1/settlements/{betId}` — 정산 상태 조회
  - `POST /internal/v1/settlements/replay/{eventId}` — 재정산 (admin-api용)
  - `POST /internal/v1/settlements/void/{betId}` — 수동 void (admin-api용)

## 제한 사항 (V1)

half-won/half-lost(Asian handicap), cash out은 V1 미지원
([ADR-0012](../orchestration/docs/architecture/decisions/0012-v1-scope-decisions.md)).
Payout odds는 `BetPlacedRequested`가 싣는 제출가(`oddsAtSubmission`)를 쓴다 —
수락가(slippage lock)는 V1 이벤트 스키마에 없어, 수락가와 ≤3% 차이가 허용된다.
selection별 승패는 `MatchResult.resultDetail` 계약으로 받는다.

## 성능

목표: **1만 베팅 정산 < 10초, 중복 payout 0건**. 측정·분석 전문은
[`load-test/results/BEST.md`](load-test/results/BEST.md) (측정 후 박제).

## 문서

학습·면접 준비 문서는 [`docs/`](docs/README.md)에 있다.

- **[docs/commits/](docs/commits/README.md)** — dev 커밋 1개 = 1페이지(9단 구조).
  개발 흐름 속에서 기술을 설명하고, 각 페이지의 "기억·설명 Level"(L1/L2/L3)이
  복습 색인이다.
- **[docs/reflection/](docs/reflection/retrospective.md)** — 회고와 변경 비용
  시뮬레이션.
