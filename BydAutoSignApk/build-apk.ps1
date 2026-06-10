$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $projectDir

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "未检测到 Java。请先安装 Android Studio，或安装 JDK 17。"
}

$gradlew = Join-Path $projectDir "gradlew.bat"
if (Test-Path -LiteralPath $gradlew) {
    & $gradlew ":app:assembleDebug"
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle ":app:assembleDebug"
} else {
    throw "未检测到 Gradle。请用 Android Studio 打开本项目完成同步，或安装 Gradle。"
}

$apk = Join-Path $projectDir "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path -LiteralPath $apk) {
    Write-Host "APK 已生成：$apk"
} else {
    throw "构建命令已结束，但未找到 APK：$apk"
}
