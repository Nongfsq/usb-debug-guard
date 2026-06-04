$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

if (-not $env:ANDROID_HOME -and $env:ANDROID_SDK_ROOT) {
  $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
}

if (-not $env:ANDROID_HOME) {
  $LocalProperties = Join-Path $ProjectRoot 'local.properties'
  if (Test-Path $LocalProperties) {
    $SdkLine = Get-Content $LocalProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($SdkLine) {
      $env:ANDROID_HOME = ($SdkLine -replace '^sdk\.dir=', '').Replace('\\', '\')
    }
  }
}

if (-not $env:ANDROID_HOME) {
  throw 'ANDROID_HOME or ANDROID_SDK_ROOT must point to an Android SDK. You can also create an ignored local.properties file with sdk.dir=<path>.'
}

Push-Location $ProjectRoot
try {
  & .\gradlew.bat assembleDebug
  if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
  }
  $Apk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
  Write-Host "Built $Apk"
} finally {
  Pop-Location
}
