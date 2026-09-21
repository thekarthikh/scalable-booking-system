[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8082',
    [string]$InventoryBaseUrl = 'http://localhost:8083',
    [Parameter(Mandatory = $true)] [string]$Token,
    [Parameter(Mandatory = $true)] [Guid]$ItemId,
    [int]$Requests = 20,
    [int]$Concurrency = 5,
    [int]$Quantity = 1
)

$ErrorActionPreference = 'Stop'
if ($Requests -lt 1 -or $Concurrency -lt 1 -or $Quantity -lt 1) {
    throw 'Requests, Concurrency, and Quantity must be positive.'
}

function Get-Percentile([double[]]$Values, [double]$Percent) {
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling(($Percent / 100) * $sorted.Count) - 1
    return $sorted[[Math]::Max(0, [int]$index)]
}

$jobScript = {
    param($Url, $BearerToken, $RequestedItemId, $RequestedQuantity)
    $key = [Guid]::NewGuid().ToString()
    $body = @{ itemId = $RequestedItemId; quantity = $RequestedQuantity; idempotencyKey = $key } | ConvertTo-Json
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-RestMethod -Uri "$Url/api/v1/bookings" -Method Post `
            -Headers @{ Authorization = "Bearer $BearerToken" } `
            -ContentType 'application/json' -Body $body -TimeoutSec 15
        $stopwatch.Stop()
        [pscustomobject]@{ Success = $true; Status = 201; BookingId = $response.id; LatencyMs = $stopwatch.Elapsed.TotalMilliseconds; Error = $null }
    } catch {
        $stopwatch.Stop()
        $status = $null
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        [pscustomobject]@{ Success = $false; Status = $status; BookingId = $null; LatencyMs = $stopwatch.Elapsed.TotalMilliseconds; Error = $_.Exception.Message }
    }
}

$results = [System.Collections.Generic.List[object]]::new()
$initialAvailable = $null
try {
    $initialAvailable = (Invoke-RestMethod -Uri "$InventoryBaseUrl/api/v1/inventory/$ItemId" -Method Get -TimeoutSec 5).available
} catch {
    # Inventory correctness is explicitly reported as unavailable rather than inferred.
}
$overall = [Diagnostics.Stopwatch]::StartNew()
for ($offset = 0; $offset -lt $Requests; $offset += $Concurrency) {
    $count = [Math]::Min($Concurrency, $Requests - $offset)
    $jobs = 1..$count | ForEach-Object {
        Start-ThreadJob -ScriptBlock $jobScript -ArgumentList $BaseUrl, $Token, $ItemId, $Quantity
    }
    Wait-Job -Job $jobs | Out-Null
    Receive-Job -Job $jobs | ForEach-Object { $results.Add($_) }
    Remove-Job -Job $jobs -Force
}
$overall.Stop()

$terminalStates = @{}
foreach ($result in @($results | Where-Object Success)) {
    $state = $null
    for ($attempt = 0; $attempt -lt 30 -and $null -eq $state; $attempt++) {
        try {
            $booking = Invoke-RestMethod -Uri "$BaseUrl/api/v1/bookings/$($result.BookingId)" `
                -Method Get -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 5
            if ($booking.status -in @('CONFIRMED', 'CANCELLED')) {
                $state = $booking.status
            } elseif ($attempt -lt 29) {
                Start-Sleep -Milliseconds 250
            }
        } catch {
            if ($attempt -lt 29) { Start-Sleep -Milliseconds 250 }
        }
    }
    $terminalStates[$result.BookingId] = if ($null -eq $state) { 'NON_TERMINAL' } else { $state }
}

$finalAvailable = $null
try {
    $finalAvailable = (Invoke-RestMethod -Uri "$InventoryBaseUrl/api/v1/inventory/$ItemId" -Method Get -TimeoutSec 5).available
} catch {
    # Inventory correctness is explicitly reported as unavailable rather than inferred.
}

$confirmed = @($terminalStates.Values | Where-Object { $_ -eq 'CONFIRMED' }).Count
$cancelled = @($terminalStates.Values | Where-Object { $_ -eq 'CANCELLED' }).Count
$nonTerminal = @($terminalStates.Values | Where-Object { $_ -eq 'NON_TERMINAL' }).Count
$uniqueBookingIds = @($results | Where-Object Success | Select-Object -ExpandProperty BookingId -Unique).Count
$inventoryExpected = if ($null -ne $initialAvailable) { $initialAvailable - ($confirmed * $Quantity) } else { $null }

$latencies = @($results | Where-Object Success | Select-Object -ExpandProperty LatencyMs)
$successful = @($results | Where-Object Success).Count
$failed = $results.Count - $successful
$duplicateBookingCount = $successful - $uniqueBookingIds
$durationSeconds = $overall.Elapsed.TotalSeconds

[pscustomobject]@{
    Requests = $results.Count
    ConfiguredConcurrency = $Concurrency
    Successful = $successful
    Failed = $failed
    ElapsedSeconds = [Math]::Round($durationSeconds, 3)
    ErrorRatePercent = if ($results.Count) { [Math]::Round(($failed / $results.Count) * 100, 2) } else { 0 }
    ThroughputRequestsPerSecond = if ($durationSeconds -gt 0) { [Math]::Round($successful / $durationSeconds, 2) } else { 0 }
    P50Ms = if ($latencies.Count) { [Math]::Round((Get-Percentile $latencies 50), 2) } else { $null }
    P95Ms = if ($latencies.Count) { [Math]::Round((Get-Percentile $latencies 95), 2) } else { $null }
    P99Ms = if ($latencies.Count) { [Math]::Round((Get-Percentile $latencies 99), 2) } else { $null }
    UniqueBookingIds = $uniqueBookingIds
    DuplicateBookingCount = $duplicateBookingCount
    ConfirmedBookings = $confirmed
    CancelledBookings = $cancelled
    NonTerminalBookings = $nonTerminal
    InventoryInitialAvailable = $initialAvailable
    InventoryFinalAvailable = $finalAvailable
    InventoryExpectedAvailable = $inventoryExpected
    InventoryConsistent = if ($null -ne $inventoryExpected -and $null -ne $finalAvailable) {
        $finalAvailable -eq $inventoryExpected
    } else { $null }
}

Write-Output 'This measures HTTP client behavior and final-state correctness for an otherwise idle test item. It is not evidence of production capacity.'
