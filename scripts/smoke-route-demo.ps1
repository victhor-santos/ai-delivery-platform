[CmdletBinding()]
param(
    [uri]$GatewayUrl = 'http://localhost:8080',
    [switch]$CheckRecovery,
    [switch]$CheckPersistence,
    [string]$ComposeProject,
    [string]$EnvFile
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$baseUrl = $GatewayUrl.AbsoluteUri.TrimEnd('/')
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(15)
$composeArguments = @('compose', '--profile', 'demo')
if ($ComposeProject) { $composeArguments += @('--project-name', $ComposeProject) }
if ($EnvFile) { $composeArguments += @('--env-file', [System.IO.Path]::GetFullPath($EnvFile)) }

function Invoke-Compose([string[]]$CommandArguments) {
    & docker @composeArguments @CommandArguments
    if ($LASTEXITCODE -ne 0) { throw "Compose failed with exit code $LASTEXITCODE." }
}

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Invoke-Api([string]$Method, [string]$Path, $Body = $null, [int]$Status = 200, [switch]$Raw) {
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method), "$baseUrl$Path")
    $response = $null
    try {
        if ($null -ne $Body) {
            $request.Content = [System.Net.Http.StringContent]::new(
                ($Body | ConvertTo-Json -Depth 10 -Compress), [System.Text.Encoding]::UTF8, 'application/json')
        }
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        Assert-Condition ([int]$response.StatusCode -eq $Status) "$Method $Path returned $([int]$response.StatusCode): $content"
        if ($Raw) { return $content }
        $parsed = $content | ConvertFrom-Json
        return $parsed
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
    }
}

Push-Location $repositoryRoot
try {
    if ($CheckRecovery -or $CheckPersistence) {
        $gatewayBinding = @(& docker @composeArguments port api-gateway 8080)
        if ($LASTEXITCODE -ne 0 -or $gatewayBinding.Count -ne 1) {
            throw 'Cannot locate the Gateway in the selected Compose project.'
        }
        $publishedPort = [int]($gatewayBinding[0].Split(':')[-1])
        if (-not $GatewayUrl.IsLoopback -or $GatewayUrl.Port -ne $publishedPort) {
            throw 'GatewayUrl does not match the local Compose project. Check ComposeProject and EnvFile before recovery checks.'
        }
    }

    foreach ($service in @('users', 'catalog', 'orders', 'payments', 'deliveries')) {
        Invoke-Api 'GET' "/api/$service/ping" | Out-Null
    }
    $restaurant = Invoke-Api 'POST' '/api/catalog/restaurants' @{
        name = "Compose Demo $([guid]::NewGuid().ToString('N').Substring(0, 8))"
        pickupLocation = @{ latitude = -23.5505; longitude = -46.6333 }
    } 201
    $order = Invoke-Api 'POST' '/api/orders' @{
        restaurantId = $restaurant.id
        destination = @{ address = 'Destino sintetico C'; latitude = -23.561; longitude = -46.656 }
    } 201
    $orderPath = "/api/orders/$($order.id)"
    $confirmed = Invoke-Api 'POST' "$orderPath/confirm"
    Assert-Condition ($confirmed.status -eq 'CONFIRMED') 'Order was not confirmed.'
    $receipt = Invoke-Api 'POST' "$orderPath/delivery"
    $repeated = Invoke-Api 'POST' "$orderPath/delivery"
    Assert-Condition ($receipt.deliveryId -eq $repeated.deliveryId) 'Delivery creation was not idempotent.'

    $deliveryPath = "/api/deliveries/$($receipt.deliveryId)"
    $delivery = Invoke-Api 'GET' $deliveryPath
    $departure = @{ departureAt = [DateTimeOffset]::UtcNow.ToString('o') }
    $plan = Invoke-Api 'POST' "$deliveryPath/route" $departure
    Assert-Condition ($plan.deliveryId -eq $receipt.deliveryId) 'Route belongs to another delivery.'
    Assert-Condition ($plan.dataOrigin -eq 'synthetic') 'Route must identify synthetic data.'
    Assert-Condition ($plan.route.Count -eq $plan.segments.Count + 1) 'Route and segments disagree.'
    Assert-Condition ($plan.distanceKm -gt 0 -and $plan.predictedTravelTimeMinutes -gt 0) 'Route totals must be positive.'
    $saved = Invoke-Api 'GET' "$deliveryPath/route"
    Assert-Condition (($saved | ConvertTo-Json -Depth 10 -Compress) -eq ($plan | ConvertTo-Json -Depth 10 -Compress)) 'Saved route differs from the planned route.'
    Write-Output "Flow passed: restaurant=$($restaurant.id), order=$($order.id), delivery=$($receipt.deliveryId), model=$($plan.modelVersion)."

    if ($CheckRecovery) {
        try {
            Invoke-Compose -CommandArguments @('stop', 'route-intelligence-service')
            $problem = Invoke-Api 'POST' "$deliveryPath/route" $departure 503
            Assert-Condition ($problem.code -eq 'ROUTE_SERVICE_UNAVAILABLE') 'Unexpected Python downtime response.'
            $preserved = Invoke-Api 'GET' "$deliveryPath/route"
            Assert-Condition (($preserved | ConvertTo-Json -Depth 10 -Compress) -eq ($plan | ConvertTo-Json -Depth 10 -Compress)) 'Python downtime changed the saved plan.'
            $unchanged = Invoke-Api 'GET' $deliveryPath
            Assert-Condition (($unchanged | ConvertTo-Json -Depth 10 -Compress) -eq ($delivery | ConvertTo-Json -Depth 10 -Compress)) 'Python downtime changed delivery state.'
        } finally {
            Invoke-Compose -CommandArguments @('up', '-d', '--no-build', '--wait', '--wait-timeout', '120', 'route-intelligence-service')
        }
        $newPlan = Invoke-Api 'POST' "$deliveryPath/route" $departure
        Assert-Condition ($newPlan.id -ne $plan.id) 'Replanning did not produce a new plan.'
        $plan = $newPlan
        Write-Output 'Python downtime and recovery passed; the previous plan and delivery were preserved.'
    }

    $courier = Invoke-Api 'POST' '/api/deliveries/couriers' -Status 201
    Invoke-Api 'POST' "$deliveryPath/assign" @{ courierId = $courier.id } | Out-Null
    Invoke-Api 'POST' "$deliveryPath/pick-up" | Out-Null
    $transit = Invoke-Api 'POST' "$deliveryPath/start-transit"
    $enteredAt = $transit.departedAt
    for ($sequence = 0; $sequence -lt $plan.segments.Count; $sequence++) {
        $segmentPath = "$deliveryPath/segments/$sequence"
        $entryEvent = @{ routePlanId = $plan.id; occurredAt = $enteredAt; dataOrigin = 'simulated' }
        $entry = Invoke-Api 'PUT' "$segmentPath/entry" $entryEvent
        Assert-Condition ($null -eq $entry.actualTravelTimeMinutes) 'An incomplete traversal has a label.'
        $duplicate = Invoke-Api 'PUT' "$segmentPath/entry" $entryEvent
        Assert-Condition (($duplicate | ConvertTo-Json -Depth 10 -Compress) -eq ($entry | ConvertTo-Json -Depth 10 -Compress)) 'Entry retry changed the observation.'
        # Use the server recording time to simulate a completed segment without client clock skew.
        $exitEvent = @{ routePlanId = $plan.id; occurredAt = $entry.entryRecordedAt; dataOrigin = 'simulated' }
        $exit = Invoke-Api 'PUT' "$segmentPath/exit" $exitEvent
        Assert-Condition ($exit.actualTravelTimeMinutes -gt 0) 'A completed traversal has no positive duration.'
        Assert-Condition ($exit.prediction.segment.segmentId -eq $plan.segments[$sequence].segmentId) 'Observation refers to another segment.'
        Assert-Condition ($exit.prediction.modelVersion -eq $plan.modelVersion) 'Prediction model snapshot changed.'
        Assert-Condition ($exit.prediction.segment.predictionContext.featureSchemaVersion -eq 'segment-features-v1') 'Feature snapshot is missing.'
        $enteredAt = $exit.exitedAt
    }
    $observations = @(Invoke-Api 'GET' "$deliveryPath/segments")
    Assert-Condition ($observations.Count -eq $plan.segments.Count) 'Observation count does not match the route.'
    $cutoff = [uri]::EscapeDataString($exit.labelAvailableAt)
    $exportPath = "$deliveryPath/segments/export?availableAtCutoff=$cutoff"
    $csv = Invoke-Api 'GET' $exportPath -Raw
    $samples = @($csv | ConvertFrom-Csv)
    Assert-Condition ($samples.Count -eq $plan.segments.Count) 'CSV export lost completed observations.'
    Assert-Condition (@($samples | Where-Object { $_.data_origin -ne 'simulated' }).Count -eq 0) 'CSV must identify simulated events.'
    Invoke-Api 'POST' "$deliveryPath/arrive" | Out-Null
    $delivery = Invoke-Api 'POST' "$deliveryPath/complete"
    Assert-Condition ($delivery.status -eq 'DELIVERED') 'Delivery lifecycle was not completed.'
    Write-Output "Segment observations passed: $($samples.Count) simulated traversals, prediction snapshots and CSV export."

    if ($CheckPersistence) {
        Invoke-Compose -CommandArguments @('up', '-d', '--no-build', '--force-recreate',
            '--wait', '--wait-timeout', '240')
        $persistedRestaurant = Invoke-Api 'GET' "/api/catalog/restaurants/$($restaurant.id)"
        $persistedOrder = Invoke-Api 'GET' $orderPath
        $persistedDelivery = Invoke-Api 'GET' $deliveryPath
        $persistedPlan = Invoke-Api 'GET' "$deliveryPath/route"
        Assert-Condition ($persistedRestaurant.id -eq $restaurant.id) 'Restaurant was lost after restart.'
        Assert-Condition ($persistedOrder.status -eq 'CONFIRMED') 'Order was lost after restart.'
        Assert-Condition (($persistedDelivery | ConvertTo-Json -Depth 10 -Compress) -eq ($delivery | ConvertTo-Json -Depth 10 -Compress)) 'Delivery changed after restart.'
        Assert-Condition (($persistedPlan | ConvertTo-Json -Depth 10 -Compress) -eq ($plan | ConvertTo-Json -Depth 10 -Compress)) 'Saved route changed after restart.'
        $persistedObservations = @(Invoke-Api 'GET' "$deliveryPath/segments")
        Assert-Condition (($persistedObservations | ConvertTo-Json -Depth 10 -Compress) -eq ($observations | ConvertTo-Json -Depth 10 -Compress)) 'Observation snapshots changed after restart.'
        Assert-Condition ((Invoke-Api 'GET' $exportPath -Raw) -eq $csv) 'CSV changed after restart for the same cutoff.'
        $recoveredReceipt = Invoke-Api 'POST' "$orderPath/delivery"
        Assert-Condition ($recoveredReceipt.deliveryId -eq $receipt.deliveryId) 'Restart caused a duplicate delivery.'
        Write-Output 'Persistence passed after recreating containers; no volumes were removed.'
    }
} finally {
    $http.Dispose()
    Pop-Location
}
