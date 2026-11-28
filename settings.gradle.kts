rootProject.name = "pqc-transfer-platform"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// 공용 라이브러리
include("libs:crypto")

// 서비스
include("services:gateway")
