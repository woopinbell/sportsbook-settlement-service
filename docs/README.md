# settlement-service 문서

학습·면접 준비 문서 진입점. 코드/실행은 상위 [README.md](../README.md), cross-cutting 결정은 [ADR](../../orchestration/docs/architecture/decisions/).

## 구성

- **[commits/](commits/README.md)** — dev 커밋 1개 = 1 페이지(`000.md`~`019.md`, 9단 구조). 개발 흐름 속에서 기술을 설명하고, [commits/README.md](commits/README.md)의 **L3/L2 빠른 참조**가 복습 색인이다.
- **[reflection/](reflection/retrospective.md)** — 회고([retrospective.md](reflection/retrospective.md), 5단)와 변경 비용 시뮬레이션([change-cost.md](reflection/change-cost.md)).

## 한눈에

settlement-service는 경기 종료 이벤트를 받아 베팅을 정산하는 **이벤트 기반** 서비스다. 핵심 자산 3가지:

1. **결과 판정 엔진** — Single/Multiple/System(K-of-N)을 한 line 모델로 통일 ([commits/004](commits/004.md)).
2. **2-phase 정산** — HTTP-in-tx 회피 + 멱등으로 중복 payout 0 ([commits/009](commits/009.md)).
3. **느슨한 결합** — event sourcing read model + outbox + DLQ + replay ([commits/002](commits/002.md), [007](007.md), [015](commits/015.md)).

면접 핵심 토픽은 [commits/README.md](commits/README.md)의 **L3 빠른 참조**부터.
