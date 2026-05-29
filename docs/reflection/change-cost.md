# 변경 비용 시뮬레이션 — settlement-service

6~12개월 안에 현실적으로 들어올 변경 요청을 기준으로, **어디가 깨지고 / 어떻게 복구하며 / 비용은 얼마인지** 추정한다. 핵심 질문: "정산 도메인의 어떤 결정이 변경에 강하고, 어떤 게 약한가?"

| # | 변경 요청 | 깨질 위치 | 복구 동선 | 비용 |
|---|---|---|---|---|
| 1 | **odds-feed가 resultDetail을 채움** (selectionId→결과 계약 실현) | settlement 코드는 **안 깨짐** (이미 계약에 맞춰 개발). odds-feed의 `MockOddsProvider`/provider가 `detail`을 채워야 함 | odds-feed에서 market type별 결과 합성 → `detail` map 작성. settlement은 무변경. ADR로 계약 박제 | **S** (odds-feed only) |
| 2 | **Asian handicap + half-won/half-lost** 도입 | `SettlementResolver`의 line multiplier(현재 WON=odds, 그 외 1.0/0), `SettlementResult` enum(WON/LOST/PUSH/VOID), selection outcome의 통짜 가정, BetSettled Avro enum | resolver에 fractional multiplier(예: HALF_WON=0.5×odds+0.5 환불) 추가, enum 확장, shared-protocol BetSettled 스키마 진화, resolver 테스트 대량 추가 | **L** (도메인 핵심 + 스키마) |
| 3 | **acceptedOdds로 payout 정확화** (제출가 대신 수락가) | read model `bet_selection.odds`의 의미, `BetPlacedRequested` 스키마(현재 `oddsAtSubmission`만) | shared-protocol에 `acceptedOdds` 필드 추가(hub) → betting 재발행 → settlement read model writer가 새 필드 저장. resolver는 무변경(odds 출처만 바뀜) | **M** (shared-protocol + betting + settlement) |
| 4 | **SETTLED 베팅 정정** (24h 이후 / payout 역분개) | 현재 `settleOnEvent`는 PENDING만 처리(재가드), reversal 경로 없음. wallet엔 payout 역분개 op 필요 | 새 상태 흐름(SETTLED→재정산), wallet reverse-credit(또는 debit), 보상 outbox 이벤트, admin 전용 endpoint. 멱등 키 재설계(정정분 구분) | **L** (분산 보상 트랜잭션 신설) |
| 5 | **처리량 10배** (Kafka partition + 다중 인스턴스) | 단일 partition 가정의 consumer, 단일 PostgreSQL | topic partition 수↑ + `@KafkaListener` concurrency, settlement 다중 인스턴스(partition 분배 자동), wallet connection pool↑. partition key는 이미 eventId라 순서 보장 유지 | **M** (인프라·설정 중심, 코드 소폭) |

비용 범례: **S** ≤ 0.5일, **M** 1~3일, **L** 1주+.

## 관찰 — 무엇이 변경에 강했나

- **계약을 외부화한 결정(#1)이 가장 강하다.** resultDetail 계약 + "settlement은 결과 판정 안 함" 경계 덕에, odds-feed가 결과 합성을 어떻게 바꾸든 settlement은 무변경이다. 경계가 비용을 한쪽 repo에 가둔다.
- **resolver의 line 통일(#2의 일부)** — Single/Multiple/System을 한 모델로 둔 덕에 새 System 변형(Trixie/Yankee 등)은 코드 0. 단 half-won은 multiplier 차원을 바꾸므로 통일 모델로도 L이다(odds 곱셈→부분 환불 혼합).
- **payout odds 출처(#3)를 read model 한 컬럼에 격리**한 덕에 제출가→수락가 전환이 resolver를 안 건드린다. odds의 *출처*와 *사용*을 분리한 효과.
- **약한 곳은 reversal(#4)** — 현재 "정산은 1회 terminal" 가정이 깊다(PENDING 재가드, 멱등 키, outbox 단방향). 역분개는 이 가정을 정면으로 깨므로 분산 보상 트랜잭션을 새로 설계해야 한다. V1이 의도적으로 안 연 부분이고, 임계점이 가장 낮다.

## 의도적으로 미룬 진화

- **half-won/half-lost, cash out, SETTLED reversal** — ADR-0012 V1 scope 외. resolver multiplier·SettlementResult enum에 확장 자리만 남김.
- **late-arriving bet 자동 catch-up** — `match_result`를 영속화해 둔 건 이 진화의 씨앗이다. 지금은 admin replay로 대응하지만, read model writer가 bet 저장 직후 stored match_result를 조회해 자동 정산하는 훅을 추가하면 자동화된다(S, 미구현).
- **DB read model 최적화** — 현재 `findPendingByEvent`는 selection join. 정산 폭주 시 event별 인덱스(`ix_bet_selection_event`)로 충분하지만, exposure/리포팅 쿼리가 늘면 별도 read model(CQRS projection) 분리 검토.

## 재설계가 합리적인 임계점

- **half-won/cash out이 들어오면** resolver의 "통짜 outcome → line multiplier" 가정을 "selection별 fractional settlement"로 일반화해야 한다. 이때 resolver를 selection-level settlement과 slip-level aggregation 두 단계로 쪼개는 재설계가 합리적.
- **reversal이 들어오면** "정산 = 1회 terminal" 대신 "정산 = 이벤트 소싱된 정산 트랜잭션 시퀀스(settle/correct/reverse)"로 모델을 바꾸는 게 맞다. 이 시점이 settlement 도메인 모델의 가장 큰 재설계.
- **처리량이 단일 PostgreSQL을 넘기면** read model을 쓰기 전용(consume)과 읽기 전용(조회/replay)으로 물리 분리(CQRS)하거나, 정산 결과를 별도 store로.
