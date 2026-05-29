# 커밋 문서 — settlement-service

dev 커밋 1개 = 1 페이지(9단 구조). 개발 흐름 속에서 기술을 설명하고, 아래 **L3/L2 빠른 참조**가 면접 직전 복습 색인이다. (retrospective 커밋 자체는 문서화 대상 아님.)

## 목차

| # | 커밋 | 내용 |
|---|---|---|
| [000](000.md) | chore(project) | scaffold (Redis 제외, shared-protocol 0.2.0) |
| [001](001.md) | build(flyway) | V1 bet read-model 스키마 |
| [002](002.md) | feat(readmodel) | BetPlacedRequested로 read model 구축 (event sourcing) |
| [003](003.md) | test(readmodel) | consume + 멱등 증명 |
| [004](004.md) | feat(resolver) | **Single/Multiple/System 판정 (line 모델 통일)** |
| [005](005.md) | test(resolver) | void 재계산 + System 부분 적중 (21 케이스) |
| [006](006.md) | build(flyway) | V3 transactional outbox 스키마 |
| [007](007.md) | feat(outbox) | outbox publisher (BetSettled/BetVoided) |
| [008](008.md) | feat(payout) | **wallet credit client (WON 2-leg)** |
| [009](009.md) | feat(settlement) | **resolver+payout+outbox 오케스트레이션 (2-phase)** |
| [010](010.md) | test(settlement) | WireMock payout/void/멱등 증명 |
| [011](011.md) | feat(trigger) | MatchResult/EventLifecycle consumer (판정 입력 계약) |
| [012](012.md) | test(trigger) | settle/void/no-op end-to-end |
| [013](013.md) | build(flyway) | V4 materialized match-result 스키마 |
| [014](014.md) | feat(settlement) | match result 영속화 + replay + **동시성 증명** |
| [015](015.md) | feat(dlq) | retry backoff → dead-letter |
| [016](016.md) | feat(api) | 상태/replay/void REST + late-settlement 24h |
| [017](017.md) | test(api) | web-layer (RFC 7807) |
| [018](018.md) | test(load) | 처리량 베이스라인 (915/589 bets/s) |
| [019](019.md) | docs(readme) | 성능 섹션 박제 |

## L3 빠른 참조 (외워서 설명 — 면접 핵심)

- **결과 판정 수학** ([004](004.md)): `payout = unitStake × Σ_lines Π_legs multiplier`. multiplier WON=odds, PUSH/VOID=1.0, LOST=line 사망. Single/Multiple/System을 한 모델로 통일, betting `SystemBetCalculator`와 대칭(all-win = 접수 max-payout). System K-of-N = C(N,K) line.
- **WON 정산 2-leg payout** ([008](008.md)): locked stake 반환(USER_LOCKED) + house 이익 지급(HOUSE_POOL). 단일 credit으론 double-entry 깨짐. LOST=무호출, PUSH/VOID=1-leg 환불.
- **2-phase 정산 + 중복 차단** ([009](009.md)): prepare(tx+lock) → credit(tx 밖) → finalize(tx, isPending 재가드). HTTP-in-tx 회피 + isPending 재가드·betId 멱등 키 → 중복 정산/payout 0. 멀티이벤트는 전부 resolve 시 1회 정산.
- **판정 입력 계약** ([011](011.md)): `MatchResult.resultDetail` = selectionId→{WON/LOST/PUSH/VOID}. settlement은 score 재해석 안 함(경계: 결과 판정 안 함). odds-feed가 채우는 건 cross-repo 후속(ADR 후보).
- **동시 재정산 → 1회** ([014](014.md)): 16스레드 동일 betId → outbox 1행. Level 3 정합성(중복 payout 0).
- **처리량 narrative** ([018](018.md)): 엔진 ~915/s(목표 근접), full-payout 589/s(wallet 2왕복 지배). dev-host vs production(partition/instance/pool) 정직.

## L2 빠른 참조 (문서 보며 설명)

- **outbox 패턴** ([006](006.md)/[007](007.md)): 상태 전이 + 이벤트 발행을 한 트랜잭션에(dual-write 해결). publisher retry-until-acked = at-least-once, consume 측 멱등이 보완. partition key=eventId.
- **event sourcing read model** ([001](001.md)/[002](002.md)): betting DB 직접 read 안 함, `bet.placed.v1` 누적. Avro는 event 레이어에 가둠. partial index(`status='PENDING'`).
- **late settlement 24h** ([016](016.md)): correction만 윈도우 게이트(첫 결과는 수용), 이후 admin replay (ADR-0012).
- **DLQ** ([015](015.md)): 3회 backoff → `<topic>.DLT` + 메트릭, poison pill 즉시 격리, 실패 베팅은 PENDING(half-settled 아님).
- **테스트 인프라**: Testcontainers PG + EmbeddedKafka + WireMock wallet 조합 ([003](003.md)/[010](010.md)/[012](012.md)), `during` 윈도우 멱등 단언, `@WebMvcTest` 슬라이스 ([017](017.md)).
- **함정들**: FOR UPDATE+JOIN FETCH 충돌(락은 fetch 없이) ([009](009.md)), 패키지 순환 회피(MatchOutcomeMode를 domain으로) ([014](014.md)), surefire excludedGroups property override ([018](018.md)), ProblemDetail JSON 필드는 `errorCode` ([017](017.md)).
