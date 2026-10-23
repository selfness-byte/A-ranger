plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// 외장하드 M(exFAT)에서 macOS가 생성하는 `._*` 리소스 포크 파일로 인해 빌드 산출물이 깨지는 것을 방지하기 위해
// NRM_BUILD_ROOT 환경변수가 설정된 경우 APFS 볼륨(/Volumes/MDev)으로 build 디렉터리를 지정합니다.
providers.environmentVariable("NRM_BUILD_ROOT").orNull?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${rootProject.name}/${project.name}"))
    }
}
