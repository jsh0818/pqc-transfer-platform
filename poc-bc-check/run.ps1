# Bouncy Castle ML-KEM / ML-DSA 지원 확인 스크립트
# 사용법: PowerShell에서 이 폴더로 이동한 뒤  .\run.ps1
$ErrorActionPreference = "Stop"
$ver = "1.87"            # 다운로드가 실패하면 1.84 로 바꿔서 다시 실행
$jar = "bcprov-jdk18on-$ver.jar"

if (-not (Test-Path $jar)) {
    Write-Host "BC $ver 다운로드 중..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/$ver/$jar" -OutFile $jar
}
chcp 65001 | Out-Null    # 한글 출력 깨짐 방지
java "-Dstdout.encoding=UTF-8" -cp $jar BcPqcCheck.java
