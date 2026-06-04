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
}

& $Adb install -r $Apk
& $Adb shell monkey -p io.github.nongfsq.usbdebugguard 1
