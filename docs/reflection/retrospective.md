# settlement-service 회고

> Phase 4 첫 repo. betting/wallet/risk/odds-feed(Phase 2~3)가 끝난 뒤 진입.
> 정산(settlement)은 "경기 종료 → 베팅 결과 판정 → wallet 반영 → 이벤트 publish"를
> 비동기로 수행한다. 베팅 *접수*는 동기([ADR-0017](../../../orchestration/docs/architecture/decisions/0017-bet-placement-sync-orchestration.md))지만
> *정산*은 비동기 Saga + Outbox([ADR-0006](../../../orchestration/docs/architecture/decisions/0006-messaging-and-saga.md)) 그대로다.

## 1. 무엇을 만들었나

경기 결과 이벤트를 받아 베팅을 정산하는 이벤트 기반 서비스. 6개 축으로 구성:

1. **Event sourcing read model** — betting-service DB를 직접 읽지 않고 `bet.placed.v1`(`BetPlacedRequested`)을 consume해 정산에 필요한 bet 스냅샷(slip type, selection, 제출 odds, stake)을 자체 구축. betting과의 결합을 끊었다.
2. **결과 판정 엔진** (`SettlementResolver`) — Single / Multiple / System(K-of-N)을 **하나의 line 모델**로 통일. `payout = unitStake × Σ_lines Π_legs multiplier` (WON=odds, PUSH/VOID=1.0, LOST=그 line 사망). betting의 `SystemBetCalculator`와 대칭이라 all-win 정산액이 접수 시점 max-payout과 일치한다.
3. **2-phase 정산** (`SettlementService`) — prepare(tx+row lock) → wallet credit(tx 밖) → finalize(tx, PENDING 재가드). 동기 wallet HTTP를 DB 트랜잭션 밖으로 빼고, PENDING 재가드 + betId 기반 멱등 키로 중복 정산/중복 payout을 막는다.
4. **트리거 + materialized 결과** — `match.result`/`event.lifecycle` consumer가 SettlementService로 fan-out. MatchResult는 `match_result`로 영속화해 replay와 late-arriving bet에 대비.
5. **실패 처리** — 3회 backoff 후 `<topic>.DLT` + 운영 메트릭. 정산 실패한 베팅은 half-settled가 아니라 PENDING으로 남아 admin replay 대상.
6. **운영 REST** — 상태 조회 / replay / 수동 void + RFC 7807, late settlement 24h 윈도우(ADR-0012).

20개 dev 커밋, `mvn verify` 44 테스트 green, 부하 하니스로 1만 베팅 정산을 실측.

## 2. 시작 시점의 가설

- **"정산은 betting 도메인을 그대로 뒤집으면 된다"** — 접수에서 stake를 차감했으니, 정산에서 payout을 더하면 끝. resolver는 odds 곱셈 몇 줄.
- **"판정 입력은 이미 있다"** — MatchResult가 결과를 주니 selection별 승패를 바로 알 수 있을 것.
- **"payout은 한 번의 wallet credit"** — WON이면 `stake × odds`를 한 번 credit.
- **"betting 패턴(outbox/consumer/resilience4j) 복붙이면 빠르다"** — Phase 2~3에서 검증된 코드가 많으니 인프라는 금방.

## 3. 가설 vs 실제 — 어디서 실제로 시간을 잃었나

### (가장 큼) 판정 입력 계약의 공백

가설 "판정 입력은 이미 있다"가 틀렸다. odds-feed의 `MatchResult`를 열어보니 `score`="2-1" 문자열 + **`resultDetail`=빈 맵**이었고(`MockOddsProvider.synthesizeOutcome`), 내 read model은 `BetPlacedRequested`로 만들어져 selection의 **ID만** 안다 — market type도, "이 selection = 홈승"인지도 모른다. 즉 "2-1"만으로는 어떤 selection이 이겼는지 **구조적으로 판정 불가**. 게다가 이 repo의 책임 경계는 "경기 결과 자체 결정 금지(외부가 줌)"다.

→ 코딩 전에 멈추고 사용자에게 **인터페이스 결정**을 물었다(절대원칙 2). 결론: `MatchResult.resultDetail`에 `selectionId → {WON/LOST/PUSH/VOID}` 계약을 두고 settlement은 *조합 로직*만 한다. odds-feed가 그 맵을 채우는 건 cross-repo 후속(ADR 후보). 이 결정을 안 하고 코딩했으면 score 파싱이 settlement에 스며들어 경계가 무너졌을 것이다. **여기서 "빠른 진행"을 택하지 않은 게 이 repo에서 가장 잘한 결정.**

### payout이 한 번의 credit이 아니었다

wallet의 `CreditCommand.Source` 문서를 읽고서야 알았다: WON 정산은 **2-leg**다 — locked에 잡아둔 stake를 USER_LOCKED로 반환 + house 이익을 HOUSE_POOL로 지급. 단일 credit으로는 double-entry가 깨진다(house가 stake까지 과지급하거나 locked가 안 풀림). LOST는 wallet 호출이 아예 없다(이미 차감됨). 이 사실이 resolver의 payout(총 반환액)과 wallet leg(반환/이익 분리)를 분리하게 만들었고, `committed = unit × lineCount`를 다시 계산해야 해서 resolver에 `lineCount`를 추가했다. **"의존 서비스의 계약을 먼저 읽었어야"** 하는 전형적 케이스.

### HTTP-in-transaction 회피의 비용

betting의 교훈("외부 호출을 DB 트랜잭션 밖으로")을 정산에도 적용하려니 단순 `@Transactional` 한 메서드로 안 되고 **2-phase**(prepare/credit/finalize) + `TransactionTemplate` + row lock + PENDING 재가드가 필요했다. 코드가 늘었지만, 이게 멀티이벤트 multi의 "모든 이벤트 도착 시 1회 정산"과 동시성 안전(16스레드→1회)을 동시에 푸는 구조라 결과적으로 핵심 자산이 됐다.

### 작은 함정들

- **패키지 순환**: `MatchResultRecord`(domain 엔티티)가 `EventResolution.Mode`(service)를 참조하면 domain↔service 순환. mode enum을 `MatchOutcomeMode`로 domain에 올려 해결(EventResolution 리팩터 + 테스트 1곳 수정).
- **토픽 불일치 발견**: betting은 `bet.placed.v1`에 publish하는데 risk는 `bet.placed`를 consume — 기존 cross-service 버그. settlement은 betting 실제값을 따르고 hub에 보고(고치는 건 내 repo 범위 밖).
- **빌드 함정**: scaffold yml의 resilience4j `record-exceptions`가 아직 없는 예외 클래스를 가리켜 context load 실패 위험 → `DependencyUnavailableException`을 미리 생성. `FOR UPDATE` + `JOIN FETCH` 동시 사용은 Postgres가 거부 → 락은 fetch 없이, selection은 lazy.
- **부하 측정 방식**: 정산은 이벤트 기반이라 k6 HTTP로 못 때린다. JVM 처리량 하니스로 방향 전환(엔진 직접 호출 측정).

## 4. 다시 한다면

- **의존 서비스 계약(wallet Source, MatchResult 스키마)을 코딩 전에 먼저 정독한다.** 2-leg payout과 resultDetail 공백을 더 일찍 알았으면 resolver/SettlementService 설계를 한 번에 잡았을 것.
- **resolver를 가장 먼저** 짠 건 옳았다(순수 함수, 의존 0, 면접 핵심). 이 순서는 유지.
- mode enum은 **처음부터 domain에** 두었어야(순환을 나중에 풀며 committed 파일 3개를 다시 건드림).
- 부하 하니스에 **outcome 파라미터(WON/LOST)를 처음부터** 넣었으면 "엔진 vs wallet I/O" 분해를 한 번에 측정했을 것(LOST run을 위해 한 번 더 돌림).

## 5. 남은 한계 (의도적으로 닫지 않은 범위)

- **half-won / half-lost 미지원** — Asian handicap 마켓이 V1에 없으므로([ADR-0012](../../../orchestration/docs/architecture/decisions/0012-v1-scope-decisions.md)) selection은 항상 통짜 WON/LOST/PUSH/VOID. resolver에 fractional multiplier 자리는 비워둠.
- **payout odds = 제출가(`oddsAtSubmission`)** — 수락가(slippage lock 후 odds)가 V1 이벤트 스키마에 없어 제출가로 정산. 수락가와 ≤3% 차이. 정확히 하려면 shared-protocol에 `acceptedOdds` 추가(hub 변경).
- **SETTLED 베팅의 정정(reversal) 미지원** — late settlement 24h는 *PENDING* 베팅의 자동 정정 + admin replay까지만. 이미 SETTLED된 베팅을 뒤집는(payout 역분개) 건 V2.
- **LOST stake의 house 이전 미수행** — LOST는 wallet 호출 없음. locked의 stake가 house로 capture되는 ledger 이동은 V1 settlement 범위 밖(stake는 접수 때 available에서 이미 빠짐).
- **odds-feed의 resultDetail enrichment 미완** — settlement은 계약에 맞춰 개발·테스트했지만, 실제 odds-feed가 `detail`을 채우는 cross-repo 작업은 후속(현재 빈 맵).
- **late-arriving bet 자동 정산 미구현** — MatchResult가 베팅보다 먼저 와도 read model에 없으면 그 순간엔 못 정산. `match_result` 영속화로 admin replay는 가능하나, 베팅 도착 시 자동 catch-up은 안 함(V1은 replay로 대응).
