param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SamplePath = Join-Path $ProjectRoot "samples\sample-batch.json"
$ExpectedPath = Join-Path $ProjectRoot "samples\expected-summary.json"
$RunId = [Guid]::NewGuid().ToString("N").Substring(0, 12)

function Assert-Equal {
    param(
        [string]$Name,
        [object]$Expected,
        [object]$Actual
    )

    if ($Expected -ne $Actual) {
        throw "$Name failed: expected '$Expected', got '$Actual'."
    }

    Write-Output "PASS $Name"
}

if (-not (Test-Path $SamplePath)) {
    throw "Sample input not found: $SamplePath"
}
if (-not (Test-Path $ExpectedPath)) {
    throw "Expected summary not found: $ExpectedPath"
}

$sample = Get-Content $SamplePath -Raw | ConvertFrom-Json
$expected = Get-Content $ExpectedPath -Raw | ConvertFrom-Json

foreach ($transaction in $sample) {
    $transaction.transactionId = "$($transaction.transactionId)-$RunId"
    $transaction.requestId = "$($transaction.requestId)-$RunId"
    $transaction.accountId = "$($transaction.accountId)-$RunId"
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
}
catch {
    throw "Cannot reach the application health endpoint at $BaseUrl/actuator/health. Start the app first with 'mvn spring-boot:run'. Details: $($_.Exception.Message)"
}
Assert-Equal "Application health" "UP" $health.status

$submission = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/transactions/batch" `
    -ContentType "application/json" `
    -Body ($sample | ConvertTo-Json -Depth 10) `
    -TimeoutSec 10

$accepted = @($submission | Where-Object { $_.accepted })
$rejected = @($submission | Where-Object { -not $_.accepted })
Assert-Equal "Batch record count" $sample.Count $submission.Count
Assert-Equal "Accepted record count" $expected.totalRequests $accepted.Count
Assert-Equal "Isolated invalid record count" 1 $rejected.Count

$deadline = (Get-Date).AddSeconds(30)
$runRecords = @()
do {
    $allRecords = Invoke-RestMethod -Uri "$BaseUrl/api/transactions" -TimeoutSec 10
    $runRecords = @($allRecords | Where-Object {
        $_.request.requestId.EndsWith("-$RunId", [StringComparison]::Ordinal)
    })
    $isSettled = $runRecords.Count -eq $expected.totalRequests
    if ($isSettled) {
        $isSettled = @($runRecords | Where-Object {
            $_.status -eq "RECEIVED" -or $_.status -eq "PROCESSING" -or $_.status -eq "RETRY_PENDING"
        }).Count -eq 0
    }
    if (-not $isSettled) {
        Start-Sleep -Milliseconds 250
    }
} while (-not $isSettled -and (Get-Date) -lt $deadline)

Assert-Equal "Accepted requests became visible" $expected.totalRequests $runRecords.Count

$processedCount = @($runRecords | Where-Object { $_.status -eq "PROCESSED" }).Count
$duplicateCount = @($runRecords | Where-Object { $_.status -eq "DUPLICATE" }).Count
Assert-Equal "Processed count" $expected.countsByStatus.PROCESSED $processedCount
Assert-Equal "Duplicate count" $expected.countsByStatus.DUPLICATE $duplicateCount
Assert-Equal "No failed transactions" $expected.countsByStatus.FAILED `
    (@($runRecords | Where-Object { $_.status -eq "FAILED" }).Count)

$retryRequestId = "REQ-5-$RunId"
$retriedRecord = $runRecords | Where-Object { $_.request.requestId -eq $retryRequestId }
Assert-Equal "Transient failure retried once" 2 $retriedRecord.attempts

$account201 = "ACC-201-$RunId"
$balance201 = Invoke-RestMethod -Uri "$BaseUrl/api/accounts/$account201/balance?currency=USD" -TimeoutSec 10
Assert-Equal "ACC-201 USD balance" $expected.accountBalances.'ACC-201'.USD $balance201.balance

$account203 = "ACC-203-$RunId"
$balance203 = Invoke-RestMethod -Uri "$BaseUrl/api/accounts/$account203/balance?currency=USD" -TimeoutSec 10
Assert-Equal "ACC-203 USD balance" $expected.accountBalances.'ACC-203'.USD $balance203.balance

$account202 = "ACC-202-$RunId"
$invalidBalance = Invoke-RestMethod -Uri "$BaseUrl/api/accounts/$account202/balance?currency=USD" -TimeoutSec 10
Assert-Equal "Invalid transaction has no balance effect" 0 $invalidBalance.balance

Write-Output ""
Write-Output "Sample smoke test passed. Run ID: $RunId"
