# 진단 학습용 데이터셋 출처 조사 (2026-11-25)

> 목적: W4 CodeBERT 학습에 필요한 라벨 데이터(500개 이상)를 어디서 구할지 정한다.
> 기준: 찾는 것은 "암호 오용"이 아니라 **양자에 취약한 공개키 암호(RSA, EC, DSA, DH, Ed25519 등) 사용**이다.

## 결론

- **"양자 취약"으로 라벨이 붙은 공개 Java 데이터셋은 없다.** 기존 벤치마크는 ECB 모드, 고정 IV 같은 암호 오용을 기준으로 라벨이 붙어 있어 우리 기준과 다르다.
- 그래서 **GitHub에서 직접 수집해 라벨을 붙이는 것을 주력으로 하고**, 공개 벤치마크는 보조와 시험용으로 쓴다.
- 표본으로 추정하면 GitHub 검색어 4개만으로 허용 라이선스 파일 약 800개를 모을 수 있어 500개 목표는 충분하다(아래 "수집 가능량").

## 1. 라벨 정의

| 항목 | 정의 |
| --- | --- |
| 샘플 단위 | 암호 호출 위치 1건 = 샘플 1건. `docs/benchmark-plan.md`(PR #11) M7의 평가 단위와 같다 |
| 모델 입력 | 그 호출을 감싼 메서드 전체. 호출 한 줄만으로는 알고리즘 값이 어디서 왔는지 알 수 없기 때문이다 |
| `VULNERABLE` | 양자 취약 공개키 암호를 쓴다(RSA, EC/ECDSA/ECDH, DSA, DH, EdDSA/Ed25519, X25519 등). 규칙의 ERROR와 같다 |
| `SAFE` | 암호를 쓰지만 양자 취약이 아니다(AES, SHA-256, ML-KEM, ML-DSA 등), 또는 HYBRID에서 의도적으로 쓴 RSA(`nosemgrep` 표시) |
| `REVIEW` | 알고리즘이 실행 중에 정해져 코드만으로 판단할 수 없다(설정 값, 요청 값). 규칙의 WARNING(`dynamic-crypto-alg`)과 같다 |
| 암호처럼 보이는 코드 | 오탐 측정을 위해 암호 API가 아닌 비슷한 코드(문자열 `"RSA"` 출력, 주석 속 `getInstance("RSA")` 등)도 `SAFE`로 넣는다. 비율은 전체의 20% 안팎 |

`REVIEW`를 따로 둔 이유: 규칙도 이미 WARNING으로 나누고 있어 규칙과 CodeBERT를 같은 기준으로 비교할 수 있다. 취약으로 합치면 "판단 불가"를 맞힌 것인지 알 수 없다.

**목표 수량**(`docs/benchmark-plan.md` 3-5절 기준)
- 중복을 뺀 고유 샘플 500건 이상, 라벨 종류(`VULNERABLE`, `SAFE`, `REVIEW`)별 150건 이상
- `REVIEW`는 공개 벤치마크를 모두 합쳐도 서로 다른 코드가 43개뿐이다(2절). GitHub 수집으로 150건을 못 채우면 채운 수와 이유를 그대로 보고한다
- 라벨은 사람이 확정하고, 20% 안팎은 두 사람이 따로 라벨을 붙여 불일치를 확인한다

## 2. 후보별 확인 결과

각 후보를 받아 우리 Semgrep 규칙(`tools/diagnosis/rules/`)으로 돌렸다. 라이선스는 GitHub API로 확인했다. 괄호 안은 확인한 커밋이다.

| 후보 | 라이선스 | 규모 | 우리 규칙 결과 | 용도 |
| --- | --- | --- | --- | --- |
| [CryptoAPI-Bench](https://github.com/CryptoAPI-Bench/CryptoAPI-Bench) (`e6b6b50`) | MIT | Java 파일 203개 | RSA 17건(파일 6개), 동적 알고리즘 32건(파일 32개) | **숨긴 시험 세트.** 학습에 넣지 않는다 |
| [CamBench](https://github.com/CROSSINGTUD/CamBench) Cap (`3c5aeb7`) | Apache-2.0 | Java 파일 623개(합성) | RSA 200건(파일 100개), 동적 알고리즘 144건(파일 131개) | 보조. 중복이 심하다(아래) |
| CamBench Real (`3c5aeb7`) | Apache-2.0 | 실제 프로젝트 20개, 라벨 34개 | 공개키 암호 사용이 거의 없음 | 사용 안 함 |
| [OWASP Benchmark](https://github.com/OWASP-Benchmark/BenchmarkJava) v1.2 (`10dfcc5`) | **GPL-2.0** | 시험 2,740개 중 암호 246개(취약 130 / 정상 116), 해시 236개 | RSA 2건, 동적 알고리즘 60건 | `SAFE` 예시와 `REVIEW` 예시. **GPL이므로 코드를 저장소에 복사하지 않는다** |
| [The Stack v2](https://huggingface.co/datasets/bigcode/the-stack-v2) | 파일마다 원래 라이선스 | 대량 | 확인 안 함 | 이용 동의가 필요하고 파일별 라이선스를 지켜야 해서 보류 |

중복 확인(탐지된 코드 줄을 공백·변수 이름만 정리해 비교):

| 후보 | 규칙 | 탐지 | 서로 다른 코드 줄 |
| --- | --- | --- | --- |
| CamBench Cap | RSA | 200건 | 3개 |
| CamBench Cap | 동적 알고리즘 | 144건 | 37개 |
| CryptoAPI-Bench | RSA | 17건 | 2개 |
| CryptoAPI-Bench | 동적 알고리즘 | 32건 | 5개 |
| OWASP Benchmark | 동적 알고리즘 | 60건 | 1개 |

- **모든 후보에서 EC, DSA, DH, Ed25519 탐지는 0건이었다.** RSA 외의 양자 취약 암호 예시는 직접 수집해야 한다.
- 공개 벤치마크는 같은 줄이 반복되는 합성 코드라 학습 데이터로서 다양성이 낮다. 메서드 문맥은 조금씩 다르므로 일부만 보조로 쓴다.

## 3. 수집 가능량 (GitHub)

코드 검색 결과 수(`<검색어> language:java`, 2026-11-25):

| 검색어 | 결과 수 | 표본 20개 중 실제 사용 |
| --- | --- | --- |
| `KeyPairGenerator getInstance RSA` | 54,528 | 18 |
| `KeyPairGenerator getInstance EC` | 73,344 | 8 |
| `KeyPairGenerator getInstance DSA` | 21,856 | 12 |
| `KeyAgreement getInstance ECDH` | 4,928 | 9 |
| `KeyAgreement getInstance DH` | 11,760 | 측정 안 함 |
| `Signature getInstance SHA256withRSA` | 25,824 | 측정 안 함 |
| `Signature getInstance SHA256withECDSA` | 9,904 | 측정 안 함 |
| `KeyPairGenerator getInstance Ed25519` | 3,548 | 측정 안 함 |

- "실제 사용"은 문자열 그대로 `getInstance("EC")`처럼 쓴 경우만 셌다. 상수나 변수로 넘긴 경우는 빠졌으므로 실제 비율은 이보다 높다.
- 라이선스: EC·ECDH 표본의 저장소 32개 중 허용 라이선스(MIT, Apache-2.0, BSD)는 11개(약 34%), 라이선스 없음 13개, GPL 계열 5개였다.
- 추정: 검색어 하나당 최대 1,000개 × 실제 사용 비율 × 34%로 계산하면 RSA 약 300, EC 약 140, DSA 약 200, ECDH 약 150, **합계 약 800개 파일**이다. 표본이 작아 대략값이다.

## 4. 수집 방법

1. **저장소 찾기**: `gh search repos --language=java --license=mit --license=apache-2.0 --stars=">100" <검색어>`
   - 검색어는 **하나씩** 넣는다. `"jwt OR signature"`처럼 묶으면 `gh`가 따옴표로 감싸 문장으로 검색하므로 결과가 0개다.
   - 라이선스를 여러 개 넣으면 "둘 중 하나"로 동작한다(MIT 4,369개 + Apache 1,213개 = 둘 다 5,582개, 검색어 `jwt`).
2. **코드로 찾기**: `gh api -X GET search/code -f q='<검색어> language:java'`
   - 1분에 10번, 검색어 하나당 최대 1,000개까지 받을 수 있다.
   - `. ( ) "` 같은 기호는 무시되어 상관없는 결과가 섞인다. 기본 브랜치만 검색되고 384KB를 넘는 파일은 빠진다.
   - 결과에 라이선스 정보가 없으므로 저장소마다 따로 확인한다. 허용 라이선스(MIT, Apache-2.0, BSD)만 쓴다.
3. **받기**: 커밋을 고정해서 받는다(`git fetch --depth 1 origin <커밋>`). 언제 받아도 같은 코드가 나온다.
4. **걸러내기**: 우리 Semgrep 규칙으로 다시 돌려 암호 API를 실제로 쓰는 메서드만 뽑는다.
5. **라벨링**: 규칙 결과를 1차 라벨로 쓰고 사람이 검토해서 고친다. 규칙과 다르게 고친 샘플은 이유를 남긴다.

## 5. 저장 원칙

- 저장소에는 **라벨 파일(CSV)과 다운로드 스크립트만** 둔다([GIT_WORKFLOW.md](../../../GIT_WORKFLOW.md) 9절).
- 라벨 파일에는 `저장소 주소, 커밋, 파일 경로, 호출 줄, 라벨, 라이선스`만 적고, 코드는 스크립트가 받아 온다.
- 받은 코드는 `tools/diagnosis/data/raw/`에만 둔다. 이 폴더는 이미 `.gitignore`에 들어 있어 Git에 올라가지 않는다.
- 원래 저장소가 삭제되면 다시 받을 수 없으므로 라벨 파일에 저장소 정보를 남긴다.

## 6. 학습·시험 분리

- 숨긴 시험 세트는 고유 샘플 50건 이상으로 한다. 규칙 작성과 모델 학습에 쓰지 않고, W4 판정과 W11 최종 평가 때만 연다.
- CryptoAPI-Bench는 숨긴 시험 세트로만 쓰고 학습에 넣지 않는다. 중복을 빼면 탐지된 코드가 7개뿐이라 이것만으로는 50건이 안 되므로 GitHub 수집분과 합친다.
- GitHub 수집분도 일부(20% 안팎)를 저장소 단위로 떼어 시험 세트로 둔다. 같은 저장소의 코드가 학습과 시험에 나뉘어 들어가지 않게 한다.
- 포크와 복사본이 많으므로, 공백을 정리한 메서드 내용이 같으면 하나만 남기고, 시험 세트와 같은 코드는 학습에서 뺀다.

## 7. 일정

| 주차 | 할 일 |
| --- | --- |
| W1 | 출처 조사(이 문서) |
| W2 | 수집 스크립트, 1차 수집 |
| W3 | 라벨링(고유 500건 이상, 20%는 두 사람이 교차 확인), 학습·시험 분리 |
| W4 | CodeBERT 1차 학습, 숨긴 시험 세트에서 규칙과 비교 → AI 판정 관문 |

## 8. 결과 재현

```bash
# 후보 받기 (저장소 밖 임시 폴더에서)
git clone --depth 1 https://github.com/CryptoAPI-Bench/CryptoAPI-Bench.git
git clone --depth 1 https://github.com/CROSSINGTUD/CamBench.git
git clone --depth 1 https://github.com/OWASP-Benchmark/BenchmarkJava.git

# 우리 규칙으로 탐지 수 세기
semgrep --config tools/diagnosis/rules/ --metrics=off --json <받은 폴더>

# OWASP 암호 시험 수 (취약/정상)
awk -F, '$2=="crypto"{c[$3]++} END{for(k in c) print k, c[k]}' BenchmarkJava/expectedresults-1.2.csv

# 코드 검색 결과 수
gh api -X GET search/code -f q='KeyAgreement getInstance ECDH language:java' --jq .total_count
```

검색 결과 수는 GitHub 색인이 바뀌면 달라진다.

## 참고

- [CryptoAPI-Bench 논문 (IEEE SecDev 2019)](https://yaogroup.cs.vt.edu/papers/IEEE_SecDev19_CryptoAPI-Bench.pdf)
- [CamBench (arXiv 2204.06447)](https://arxiv.org/pdf/2204.06447)
- [OWASP Benchmark](https://owasp.org/index.php/Benchmark)
- [GitHub REST API – Search](https://docs.github.com/en/rest/search/search)
- [Searching code (legacy)](https://docs.github.com/en/search-github/searching-on-github/searching-code)
