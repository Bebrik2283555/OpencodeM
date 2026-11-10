param([string]$Gradle = $env:GRADLE)
$ErrorActionPreference = 'Stop'
if (!$env:JAVA_HOME) {
    $env:JAVA_HOME = (Get-ChildItem "$HOME\.jdks\17" -Directory | Select-Object -First 1).FullName
}
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (!$Gradle) {
    $Gradle = (Get-ChildItem "$HOME\.gradle\wrapper\dists\gradle-8*-bin" -Filter gradle.bat -Recurse |
        Select-Object -First 1).FullName
}
if (!$Gradle -or !(Test-Path "$env:JAVA_HOME\bin\java.exe")) { throw 'JDK 17 and Gradle 8 are required (JAVA_HOME, GRADLE).' }
$tools = Join-Path $env:ANDROID_HOME 'build-tools\35.0.0'
Push-Location $PSScriptRoot
try {
    if (!(Test-Path 'keys/margycode.jks') -or !(Test-Path 'keys/margycode.pass')) { throw 'Signing key missing from keys/.' }
    & $Gradle :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle checks/build failed.' }
    $version = [regex]::Match((Get-Content app/build.gradle.kts -Raw), 'versionName = "([^"]+)"').Groups[1].Value
    New-Item -ItemType Directory -Force out | Out-Null
    $apk = "out/OpencodeM-$version.apk"
    & "$tools\zipalign.exe" -f -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk out/aligned.apk
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
    $env:MARGY_BUILD_PASS = (Get-Content keys/margycode.pass -Raw).Trim()
    & "$tools\apksigner.bat" sign --ks keys/margycode.jks --ks-key-alias margycode `
        --ks-pass env:MARGY_BUILD_PASS --key-pass env:MARGY_BUILD_PASS --out $apk out/aligned.apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & "$tools\apksigner.bat" verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    Remove-Item out/aligned.apk
    Copy-Item $apk (Join-Path $PSScriptRoot "..") -Force
    Get-FileHash $apk -Algorithm SHA256
    Write-Output (Join-Path $PSScriptRoot $apk)
} finally {
    Remove-Item Env:\MARGY_BUILD_PASS -ErrorAction SilentlyContinue
    Pop-Location
}
