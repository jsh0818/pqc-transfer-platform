# Git 협업 규칙

2인 팀이 12주 동안 충돌을 줄이고, 면접에서 "내가 한 일"을 증명할 수 있게 하는 규칙이다.

## 1. 저장소 구조 (모노레포 1개)

```
pqc-transfer-platform/
├─ services/
│  ├─ transfer-api/      # 이체 API, 원장, 결재, 배치 (Java, Spring Boot)
│  └─ gateway/           # 암호 민첩성 게이트웨이 (Java)
├─ tools/
│  └─ diagnosis/         # 암호자산 진단 (Python, Semgrep, CodeBERT)
├─ web/
│  └─ admin/             # React 어드민
├─ infra/                # docker-compose, 클라우드 설정, Prometheus/Grafana
├─ loadtest/             # k6 스크립트
├─ docs/
│  ├─ adr/               # 설계 결정 기록 (0001-제목.md)
│  ├─ api/               # OpenAPI 명세 (openapi.yaml)
│  ├─ erd/               # ERD
│  └─ threat-model.md
├─ .github/
│  └─ pull_request_template.md
├─ .gitignore
└─ README.md
```

폴더가 곧 담당 영역이라 서로의 파일을 덜 건드린다. 공용 영역(`docs/api`, `docs/erd`, `infra`)을 고칠 때는 상대에게 먼저 알린다.

## 2. 브랜치 전략

| 브랜치 | 용도 | 직접 push |
| --- | --- | --- |
| `main` | 발표·제출 가능한 안정 버전 | 금지 |
| `develop` | 통합 브랜치. 평소 병합 대상 | 금지 |
| `feat/<영역>-<내용>` | 기능 개발 | 본인만 |
| `fix/<영역>-<내용>` | 버그 수정 | 본인만 |
| `docs/<내용>` | 문서 | 본인만 |

- 영역: `ledger`, `approval`, `transfer`, `batch`, `gateway`, `crypto`, `diagnosis`, `infra`, `loadtest`, `admin`
- 예: `feat/ledger-double-entry`, `feat/crypto-hybrid-kdf`, `fix/transfer-idempotency-race`
- 브랜치는 **작게, 짧게** 쓴다. 1~3일 안에 `develop`으로 병합하는 것을 목표로 한다.
- `main`으로의 병합은 **관문 시점**(W3, W7, W10, W12)에만 한다.

## 3. 일하는 순서

```bash
git switch develop
git pull
git switch -c feat/ledger-double-entry
# 작업 + 커밋
git push -u origin feat/ledger-double-entry
# GitHub에서 develop 대상으로 PR 생성
```

1. 최신 `develop`에서 브랜치를 딴다.
2. 작은 단위로 커밋한다.
3. PR을 열면 **상대방이 리뷰하고 승인**한다.
4. 승인되면 본인이 **Squash and merge**로 병합하고 브랜치를 지운다.
5. 매주 금요일에는 `develop`이 빌드·테스트를 통과하는지 같이 확인한다.

## 4. 커밋 메시지

형식: `<type>(<영역>): <한 줄 요약>`

| type | 의미 |
| --- | --- |
| feat | 기능 추가 |
| fix | 버그 수정 |
| test | 테스트 추가·수정 |
| docs | 문서 |
| refactor | 동작 변화 없는 구조 개선 |
| perf | 성능 개선 (전후 수치를 본문에) |
| chore | 설정, 빌드, 의존성 |

예:
```
feat(ledger): 복식부기 분개 엔티티와 차대 합계 검증 추가
fix(transfer): 동시 요청에서 멱등성 키 중복 저장 방지
perf(gateway): 세션 키 캐시 적용, HYBRID TPS 측정 결과를 본문에 기록
```

## 5. PR 규칙

- PR 하나는 **하나의 주제**만 담는다. 변경이 400줄을 넘으면 나눈다.
- PR 템플릿(`.github/pull_request_template.md`)의 항목을 모두 채운다.
- 리뷰어는 24시간 안에 응답한다. 급하면 메신저로 알린다.
- **빌드·테스트가 실패한 PR은 병합하지 않는다.**
- 의견이 갈리면 ADR(`docs/adr/`)에 근거를 남기고 결정한다.

## 6. GitHub 설정 (저장소 만든 직후 1회)

1. 저장소 생성 후 `main`, `develop` 브랜치를 만든다.
2. Settings → Branches → Branch protection rule을 `main`과 `develop`에 각각 추가한다.
   - Require a pull request before merging 체크
   - Required approvals: 1
   - Require status checks to pass (CI 구성 후 체크 추가)
3. Settings → General → Pull Requests에서 **Squash merging만 허용**하고 병합 후 브랜치 자동 삭제를 켠다.
4. 기본 브랜치를 `develop`으로 바꾼다.
5. 두 사람 모두 Collaborator로 등록한다.

## 7. 면접용 기여 기록 규칙

기여도를 증명할 수 있도록 다음을 지킨다.

- **본인 이름으로 커밋한다.** 같은 컴퓨터를 같이 쓰더라도 `git config user.name`과 `user.email`을 각자 설정한다.
- 파트별 README에 **"내 역할 / 문제 / 해결 / 수치"**를 쓴다.
- 설계 선택은 ADR로 남긴다.
- 성능·정합성 수치는 PR 본문이나 `docs/`에 측정 조건과 함께 기록한다.

## 8. Claude 등 AI 도구를 쓸 때

- **"왜 이렇게 했는지" 한 줄로 설명할 수 있을 때만 커밋한다.** PR 템플릿의 이해 확인 항목이 이를 위한 것이다.
- AI가 만든 코드를 그대로 붙여넣지 말고, 직접 실행해서 동작을 확인한다.
- 비밀값(키, 비밀번호, 토큰)을 프롬프트에 붙여넣지 않는다.

## 9. 절대 커밋하지 않는 것

- 비밀번호, API 키, 클라우드 접근 키, 개인 키(.pem), `.env`
- 대용량 모델 파일(CodeBERT 가중치 등)과 데이터셋 원본 → 다운로드 스크립트와 README만 남긴다.
- 빌드 산출물(`build/`, `target/`, `node_modules/`)

실수로 비밀값을 올렸다면 **키를 즉시 폐기·재발급**한다. 커밋 기록에서 지워도 이미 유출된 것으로 본다.

## 10. 초기 세팅 명령

```bash
# 저장소 생성 후
git clone <저장소 주소>
cd pqc-transfer-platform
git config user.name "본인 이름"
git config user.email "깃허브 가입 이메일"

# 최초 1회 (한 사람이 실행)
git switch -c develop
git push -u origin develop
```
