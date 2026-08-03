param(
  [Parameter(Mandatory = $true)]
  [ValidateNotNullOrEmpty()]
  [string]$Serial
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Apk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$Adb = if ($env:ADB) {
  $env:ADB
} elseif ($env:ANDROID_HOME -and (Test-Path (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))) {
  Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
} elseif ($env:ANDROID_SDK_ROOT -and (Test-Path (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'))) {
  Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
} else {
  (Get-Command adb -ErrorAction Stop).Source
}

if (!(Test-Path $Apk)) {
  & (Join-Path $ProjectRoot 'tools\build.ps1')
  if ($LASTEXITCODE -ne 0) {
    throw "Build failed with exit code $LASTEXITCODE"
  }
}

& $Adb -s $Serial get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw "ADB target '$Serial' is unavailable"
}

$QemuFlag = (& $Adb -s $Serial shell getprop ro.kernel.qemu).Trim()
if ($LASTEXITCODE -ne 0 -or $QemuFlag -ne '1') {
  throw "Refusing to install on '$Serial': only Android emulators are allowed"
}

$BuildFile = Get-Content (Join-Path $ProjectRoot 'app\build.gradle.kts') -Raw
$VersionMatch = [regex]::Match($BuildFile, 'versionCode\s*=\s*(\d+)')
if (!$VersionMatch.Success) {
  throw 'Could not determine expected versionCode from app/build.gradle.kts'
}
$ExpectedVersionCode = $VersionMatch.Groups[1].Value
$ApkSha256 = (Get-FileHash -Algorithm SHA256 $Apk).Hash.ToLowerInvariant()

& $Adb -s $Serial install -r $Apk
if ($LASTEXITCODE -ne 0) {
  throw "APK installation failed with exit code $LASTEXITCODE"
}

$PackageInfo = (& $Adb -s $Serial shell dumpsys package io.github.nongfsq.usbdebugguard) -join "`n"
if ($LASTEXITCODE -ne 0 -or $PackageInfo -notmatch "versionCode=$ExpectedVersionCode(?:\s|$)") {
  throw "Installed package does not match expected versionCode $ExpectedVersionCode"
}

Write-Host "Installed versionCode=$ExpectedVersionCode apkSha256=$ApkSha256 target=$Serial"
& $Adb -s $Serial shell monkey -p io.github.nongfsq.usbdebugguard 1
if ($LASTEXITCODE -ne 0) {
  throw "App launch failed with exit code $LASTEXITCODE"
}
