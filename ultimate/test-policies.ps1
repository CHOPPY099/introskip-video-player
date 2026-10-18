param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { $JavaHome = 'C:\Program Files\Android\Android Studio\jbr' }
$root = Split-Path $PSScriptRoot -Parent
$classes = Join-Path $PSScriptRoot 'build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$tests = Join-Path $PSScriptRoot 'build\intermediates\javac\debugUnitTest\compileDebugUnitTestJavaWithJavac\classes'
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$junit = Get-ChildItem (Join-Path $cache 'junit\junit\4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1
$hamcrest = Get-ChildItem (Join-Path $cache 'org.hamcrest\hamcrest-core\1.3') -Recurse -Filter '*.jar' | Select-Object -First 1
if (-not (Test-Path $tests)) { throw 'Run :ultimate:compileDebugUnitTestJavaWithJavac first.' }
# Direct classpath avoids Gradle worker argfile quoting problems in Windows user paths containing apostrophes.
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$tests;$($junit.FullName);$($hamcrest.FullName)" org.junit.runner.JUnitCore `
    com.choppy.ultimateplayer.EpisodeNameParserTest `
    com.choppy.ultimateplayer.PlaybackLedgerTest `
    com.choppy.ultimateplayer.BrowserUrlPolicyTest
exit $LASTEXITCODE
