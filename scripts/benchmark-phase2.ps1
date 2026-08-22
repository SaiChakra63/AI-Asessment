param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ShortCode = "phase2bench",
    [int]$WarmupRequests = 10,
    [int]$Iterations = 50
)

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(10)

try {
    $payload = [System.Net.Http.StringContent]::new(
        ('{"originalUrl":"https://example.com/phase2-benchmark","customCode":"' + $ShortCode + '"}'),
        [System.Text.Encoding]::UTF8,
        "application/json"
    )
    $createResponse = $client.PostAsync("$BaseUrl/api/v1/urls/shorten", $payload).GetAwaiter().GetResult()
    if ([int]$createResponse.StatusCode -notin @(201, 400)) {
        throw "Unable to prepare benchmark URL. HTTP $([int]$createResponse.StatusCode)."
    }

    $redirectUrl = "$BaseUrl/api/v1/urls/$ShortCode"
    for ($requestIndex = 0; $requestIndex -lt $WarmupRequests; $requestIndex++) {
        $warmupResponse = $client.GetAsync($redirectUrl).GetAwaiter().GetResult()
        if ([int]$warmupResponse.StatusCode -ne 302) {
            $statusCode = [int]$warmupResponse.StatusCode
            $warmupResponse.Dispose()
            throw "Warm-up returned HTTP $statusCode. Check service health and ensure the configured rate limit exceeds warm-up plus measured requests."
        }
        $warmupResponse.Dispose()
    }

    $latencies = [System.Collections.Generic.List[double]]::new()
    $errors = 0
    $totalTimer = [System.Diagnostics.Stopwatch]::StartNew()

    for ($requestIndex = 0; $requestIndex -lt $Iterations; $requestIndex++) {
        $requestTimer = [System.Diagnostics.Stopwatch]::StartNew()
        $response = $client.GetAsync($redirectUrl).GetAwaiter().GetResult()
        $requestTimer.Stop()
        $latencies.Add($requestTimer.Elapsed.TotalMilliseconds)
        if ([int]$response.StatusCode -ne 302) {
            $errors++
        }
        $response.Dispose()
    }
    $totalTimer.Stop()

    $sorted = $latencies.ToArray() | Sort-Object
    function Get-Percentile([double[]]$Values, [double]$Percentile) {
        $index = [Math]::Ceiling(($Percentile / 100.0) * $Values.Length) - 1
        return $Values[[Math]::Max(0, [Math]::Min($index, $Values.Length - 1))]
    }

    [ordered]@{
        timestamp_utc = [DateTime]::UtcNow.ToString("o")
        base_url = $BaseUrl
        short_code = $ShortCode
        warmup_requests = $WarmupRequests
        iterations = $Iterations
        p50_ms = [Math]::Round((Get-Percentile $sorted 50), 3)
        p95_ms = [Math]::Round((Get-Percentile $sorted 95), 3)
        p99_ms = [Math]::Round((Get-Percentile $sorted 99), 3)
        average_ms = [Math]::Round((($latencies | Measure-Object -Average).Average), 3)
        throughput_requests_per_second = [Math]::Round(($Iterations / $totalTimer.Elapsed.TotalSeconds), 2)
        non_302_responses = $errors
        note = "Sequential local smoke benchmark; retain environment details and use controlled load testing for capacity claims."
    } | ConvertTo-Json
}
finally {
    $client.Dispose()
    $handler.Dispose()
}
