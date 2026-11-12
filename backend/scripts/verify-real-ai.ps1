<#
  Verifies the REAL Anthropic integration (needs a valid AI_API_KEY). Safe by design:
    * refuses to run without a key and NEVER prints it;
    * makes two billable requests (a connectivity prompt, one explanation of the fictitious Rahul sample)
      plus two free negative checks (invalid key, invalid model);
    * uses an embedded test PostgreSQL, never your development database;
    * does not read or write any .env file.

  Usage (PowerShell, from the backend folder):
      $env:AI_API_KEY = "<your key>"        # set it in this terminal only; do not put it in a file in the repo
      .\scripts\verify-real-ai.ps1
  Optional: $env:AI_MODEL = "claude-sonnet-5-5"   (the default)
#>
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:AI_API_KEY)) {
    Write-Host "AI_API_KEY is not set in this terminal, so NO real call was made and the integration is NOT verified." -ForegroundColor Yellow
    Write-Host 'Set it for this session only:   $env:AI_API_KEY = "<your key>"   then run this script again.'
    exit 2
}

$tools = Join-Path $env:USERPROFILE '.loanlens-tools'
if ((Test-Path "$tools\jdk-21") -and -not $env:JAVA_HOME) { $env:JAVA_HOME = "$tools\jdk-21" }
$mvn = if (Test-Path "$tools\maven\bin\mvn.cmd") { "$tools\maven\bin\mvn.cmd" } else { 'mvn' }

Push-Location (Join-Path $PSScriptRoot '..')
try {
    Write-Host "Running the live Anthropic verification (key present, not shown; model: $(if ($env:AI_MODEL) { $env:AI_MODEL } else { 'claude-sonnet-5-5 (default)' }))..."
    & $mvn test '-Dtest=RealAnthropicLiveIT' '-Dsurefire.failIfNoSpecifiedTests=false' 2>&1 |
        Where-Object { $_ -match '\[LIVE\]|Tests run:|BUILD|FAIL|ERROR\]\s+\S' }
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($code -eq 0) {
    Write-Host "`nDone. Check the output above: 'Tests run: 5, Failures: 0, Skipped: 0' means the real integration passed." -ForegroundColor Green
} else {
    Write-Host "`nThe live verification FAILED. Read the [LIVE] and failure lines above (they never contain the key)." -ForegroundColor Red
}
exit $code
