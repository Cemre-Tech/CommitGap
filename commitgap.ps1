<#
.SYNOPSIS
  CommitGap launcher for PowerShell (Windows PowerShell 5.1 and PowerShell 7+).
.DESCRIPTION
  Runs the same CLI as the Bash launcher. Needs Java 21+ and a running Docker engine; installs nothing.
  Example: .\commitgap.ps1 doctor
#>
$ErrorActionPreference = 'Stop'
$CommitGapHome = $PSScriptRoot

function Fail([string]$Message) {
    [Console]::Error.WriteLine("commitgap: $Message")
    exit 2
}

# Locate Java: JAVA_HOME first, then PATH.
$Java = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $Java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java'))) {
    $Java = Join-Path $env:JAVA_HOME 'bin/java'
} else {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $Java = $cmd.Source }
}
if (-not $Java) { Fail 'Java 21 or newer is required and was not found (set JAVA_HOME or put java on PATH).' }

# 'java -version' writes to stderr; read it through a process so PowerShell 5.1 does not treat it as an error.
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $Java
$psi.Arguments = '-version'
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$proc = [System.Diagnostics.Process]::Start($psi)
$versionText = $proc.StandardError.ReadToEnd()
$proc.WaitForExit()
if ($versionText -notmatch 'version "(\d+)') { Fail "could not determine the Java version of $Java" }
$JavaMajor = [int]$Matches[1]
if ($JavaMajor -lt 21) { Fail "Java 21 or newer is required; $Java is Java $JavaMajor." }

$ReleaseCli = Join-Path $CommitGapHome 'lib\commitgap-cli.jar'
if (Test-Path $ReleaseCli) {
    $CliJar = $ReleaseCli
    $DemoJar = Join-Path $CommitGapHome 'lib\commitgap-demo.jar'
} else {
    # Source checkout: build with the Maven Wrapper when the jars are missing or older than the sources.
    $CliJar = Join-Path $CommitGapHome 'commitgap-cli\target\commitgap-cli-exec.jar'
    $DemoJar = Join-Path $CommitGapHome 'commitgap-demo\target\commitgap-demo-exec.jar'
    $needsBuild = -not ((Test-Path $CliJar) -and (Test-Path $DemoJar))
    if (-not $needsBuild) {
        $built = (Get-Item $CliJar).LastWriteTime
        $newer = Get-ChildItem -Path $CommitGapHome -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { ($_.FullName -match '[\\/]src[\\/]main[\\/]' -or $_.Name -eq 'pom.xml') -and
                           $_.FullName -notmatch '[\\/](target|\.git)[\\/]' -and $_.LastWriteTime -gt $built } |
            Select-Object -First 1
        $needsBuild = [bool]$newer
    }
    if ($needsBuild) {
        [Console]::Error.WriteLine('commitgap: building with the Maven Wrapper (first run downloads Maven and dependencies)...')
        if (-not $env:JAVA_HOME) {
            # Ask the JVM where it lives; the java on PATH can be a shim (for example Oracle's javapath).
            $psi.Arguments = '-XshowSettings:properties -version'
            $p2 = [System.Diagnostics.Process]::Start($psi)
            $settings = $p2.StandardError.ReadToEnd()
            $p2.WaitForExit()
            if ($settings -match 'java\.home = (.+)') { $env:JAVA_HOME = $Matches[1].Trim() }
        }
        $mvnw = if ($IsLinux -or $IsMacOS) { Join-Path $CommitGapHome 'mvnw' } else { Join-Path $CommitGapHome 'mvnw.cmd' }
        Push-Location $CommitGapHome
        try {
            & $mvnw -q -B -DskipTests package
            if ($LASTEXITCODE -ne 0) { Fail 'build failed; run the Maven Wrapper with -DskipTests package to see the details.' }
        } finally {
            Pop-Location
        }
    }
}

# CommitGap removes each run's resources itself (by label), and must be able to keep them on request,
# so the Testcontainers reaper is disabled unless the user configured it explicitly.
if (-not $env:TESTCONTAINERS_RYUK_DISABLED) { $env:TESTCONTAINERS_RYUK_DISABLED = 'true' }

& $Java --enable-native-access=ALL-UNNAMED "-Dcommitgap.home=$CommitGapHome" "-Dcommitgap.demoJar=$DemoJar" -jar $CliJar @args
exit $LASTEXITCODE
