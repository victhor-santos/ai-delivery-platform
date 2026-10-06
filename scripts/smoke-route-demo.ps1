[CmdletBinding()]
param(
    [uri]$GatewayUrl = 'http://localhost:8080',
    [switch]$CatalogOnly,
    [switch]$OrderOnly,
    [switch]$UsersOnly,
    [switch]$CheckRecovery,
    [switch]$CheckPersistence,
    [string]$ComposeProject,
    [string]$EnvFile
)

$ErrorActionPreference = 'Stop'
$partialModeCount = [int]$CatalogOnly.IsPresent + [int]$OrderOnly.IsPresent + [int]$UsersOnly.IsPresent
if ($partialModeCount -gt 1) {
    throw 'Select only one of CatalogOnly, OrderOnly or UsersOnly.'
}
if ($partialModeCount -gt 0 -and ($CheckRecovery -or $CheckPersistence)) {
    throw 'Partial smoke modes cannot be combined with the full demo recovery or persistence checks.'
}
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

function Invoke-Api([string]$Method, [string]$Path, $Body = $null, [int]$Status = 200, [switch]$Raw, [string]$AccessToken) {
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method), "$baseUrl$Path")
    $response = $null
    try {
        if ($AccessToken) {
            $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $AccessToken)
        }
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

    $services = if ($CatalogOnly) { @('catalog') } elseif ($OrderOnly) { @('catalog', 'orders') }
        elseif ($UsersOnly) { @('users') }
        else { @('users', 'catalog', 'orders', 'payments', 'deliveries') }
    foreach ($service in $services) {
        Invoke-Api 'GET' "/api/$service/ping" | Out-Null
    }
    if (-not $CatalogOnly -and -not $OrderOnly) {
        $demoEmail = "demo-$([guid]::NewGuid().ToString('N'))@example.test"
        $demoPassword = 'demonstration-password-123'
        $user = Invoke-Api 'POST' '/api/users/auth/register' @{
            name = 'Cliente Demo'; email = " $($demoEmail.ToUpperInvariant()) "; password = $demoPassword
        } 201
        Assert-Condition ($user.email -eq $demoEmail) 'User email was not normalized.'
        Invoke-Api 'POST' '/api/users/auth/login' @{ email = $demoEmail; password = 'incorrect-password' } 401 | Out-Null
        Invoke-Api 'GET' '/api/users/auth/me' -Status 401 | Out-Null
        Invoke-Api 'GET' '/api/users/auth/me' -AccessToken 'not-a-token' -Status 401 | Out-Null
        $authentication = Invoke-Api 'POST' '/api/users/auth/login' @{ email = $demoEmail; password = $demoPassword }
        Assert-Condition ($authentication.tokenType -eq 'Bearer' -and $authentication.expiresIn -eq 900) 'Invalid access token metadata.'
        $identity = Invoke-Api 'GET' '/api/users/auth/me' -AccessToken $authentication.accessToken
        Assert-Condition ($identity.id -eq $user.id -and $identity.email -eq $user.email) 'Token resolved to another user.'
        Invoke-Api 'POST' '/api/users' @{ name = 'Duplicado'; email = $demoEmail } 409 | Out-Null
        $userPath = "/api/users/$($user.id)"
        $updatedUser = Invoke-Api 'PUT' "$userPath/profile" @{ name = 'Cliente Atualizado' }
        Assert-Condition ($updatedUser.id -eq $user.id -and $updatedUser.email -eq $user.email) 'Profile update changed identity or email.'
        Assert-Condition ($updatedUser.name -eq 'Cliente Atualizado') 'Profile name was not updated.'
        $addressPath = "$userPath/addresses"
        $address = Invoke-Api 'POST' $addressPath @{
            label = 'Casa'; address = 'Destino sintetico C'; latitude = -23.561; longitude = -46.656
        } 201
        Assert-Condition ($address.userId -eq $user.id) 'Address belongs to another user.'
        $savedAddressPath = "$addressPath/$($address.id)"
        $updatedAddress = Invoke-Api 'PUT' $savedAddressPath @{
            label = 'Entrega'; address = 'Destino sintetico atualizado'; latitude = -23.562; longitude = -46.657
        }
        Assert-Condition ($updatedAddress.id -eq $address.id -and $updatedAddress.userId -eq $user.id) 'Address update changed identity or owner.'
        Assert-Condition ($updatedAddress.label -eq 'Entrega' -and $updatedAddress.address -eq 'Destino sintetico atualizado' -and
            $updatedAddress.latitude -eq -23.562 -and $updatedAddress.longitude -eq -46.657) 'Address details were not updated.'
        $addressPage = Invoke-Api 'GET' "${addressPath}?page=0&size=1"
        Assert-Condition ($addressPage.totalElements -eq 1 -and $addressPage.items[0].id -eq $address.id) 'Address pagination lost the saved address.'
        $otherUser = Invoke-Api 'POST' '/api/users' @{
            name = 'Outro Cliente'; email = "other-$([guid]::NewGuid().ToString('N'))@example.test"
        } 201
        $otherAddressPath = "/api/users/$($otherUser.id)/addresses/$($address.id)"
        Invoke-Api 'GET' $otherAddressPath -Status 404 | Out-Null
        Invoke-Api 'PUT' $otherAddressPath @{
            label = 'Alterado'; address = 'Outro destino'; latitude = 0; longitude = 0
        } 404 | Out-Null
        $storedUser = Invoke-Api 'GET' $userPath
        $storedAddress = Invoke-Api 'GET' $savedAddressPath
        Assert-Condition (($storedUser | ConvertTo-Json -Compress) -eq ($updatedUser | ConvertTo-Json -Compress)) 'Stored profile differs from the update.'
        Assert-Condition (($storedAddress | ConvertTo-Json -Compress) -eq ($updatedAddress | ConvertTo-Json -Compress)) 'Another user changed the address.'
        Write-Output "Users passed: user=$($user.id), address=$($address.id)."
        if ($UsersOnly) { return }
    }
    $restaurant = Invoke-Api 'POST' '/api/catalog/restaurants' @{
        name = "Compose Demo $([guid]::NewGuid().ToString('N').Substring(0, 8))"
        pickupLocation = @{ latitude = -23.5505; longitude = -46.6333 }
    } 201
    $menuPath = "/api/catalog/restaurants/$($restaurant.id)/menu-items"
    $menuItem = Invoke-Api 'POST' $menuPath @{
        name = 'Prato do dia'; description = 'Cardapio de demonstracao'; price = [decimal]25.90
    } 201
    Assert-Condition ($menuItem.restaurantId -eq $restaurant.id -and $menuItem.available) 'Menu item has invalid ownership or availability.'
    Assert-Condition ($menuItem.currency -eq 'BRL' -and $menuItem.price -eq [decimal]25.90) 'Menu price or currency is invalid.'
    $itemPath = "$menuPath/$($menuItem.id)"
    $updatedItem = Invoke-Api 'PUT' $itemPath @{
        name = 'Prato especial'; description = $null; price = [decimal]29.90; available = $false
    }
    Assert-Condition (-not $updatedItem.available -and $updatedItem.price -eq [decimal]29.90) 'Menu update was not applied.'
    $savedItem = Invoke-Api 'GET' $itemPath
    Assert-Condition (($savedItem | ConvertTo-Json -Compress) -eq ($updatedItem | ConvertTo-Json -Compress)) 'Saved menu item differs from the update.'
    $menu = Invoke-Api 'GET' "${menuPath}?page=0&size=1"
    Assert-Condition ($menu.totalElements -eq 1 -and $menu.content[0].id -eq $menuItem.id) 'Menu pagination lost the item.'
    $otherRestaurant = Invoke-Api 'POST' '/api/catalog/restaurants' @{
        name = "Compose Demo ownership $([guid]::NewGuid().ToString('N').Substring(0, 8))"
    } 201
    $otherItemPath = "/api/catalog/restaurants/$($otherRestaurant.id)/menu-items/$($menuItem.id)"
    Invoke-Api 'GET' $otherItemPath -Status 404 | Out-Null
    Invoke-Api 'PUT' $otherItemPath @{
        name = 'Alterado'; price = [decimal]1.00; available = $true
    } 404 | Out-Null
    $preservedItem = Invoke-Api 'GET' $itemPath
    Assert-Condition (($preservedItem | ConvertTo-Json -Compress) -eq ($updatedItem | ConvertTo-Json -Compress)) 'Another restaurant changed the menu item.'
    Write-Output "Catalog passed: restaurant=$($restaurant.id), menuItem=$($menuItem.id)."
    if ($CatalogOnly) { return }

    Invoke-Api 'PUT' $itemPath @{
        name = 'Prato especial'; price = [decimal]29.90; available = $true
    } | Out-Null
    $order = Invoke-Api 'POST' '/api/orders' @{
        restaurantId = $restaurant.id
        items = @(@{ menuItemId = $menuItem.id; quantity = 2 })
        destination = @{ address = 'Destino sintetico C'; latitude = -23.561; longitude = -46.656 }
    } 201
    Assert-Condition ($order.currency -eq 'BRL' -and $order.total -eq [decimal]59.80) 'Order total was not calculated from the menu.'
    Assert-Condition ($order.items.Count -eq 1 -and $order.items[0].unitPrice -eq [decimal]29.90) 'Order item snapshot is invalid.'
    $updatedItem = Invoke-Api 'PUT' $itemPath @{
        name = 'Prato com novo preco'; price = [decimal]39.90; available = $false
    }
    $orderPath = "/api/orders/$($order.id)"
    $storedOrder = Invoke-Api 'GET' $orderPath
    Assert-Condition ($storedOrder.total -eq $order.total -and $storedOrder.currency -eq $order.currency) 'Menu changes altered the stored order total or currency.'
    Assert-Condition (($storedOrder.items | ConvertTo-Json -Compress) -eq ($order.items | ConvertTo-Json -Compress)) 'Menu changes altered the stored item snapshots.'
    $confirmed = Invoke-Api 'POST' "$orderPath/confirm"
    Assert-Condition ($confirmed.status -eq 'CONFIRMED') 'Order was not confirmed.'
    Assert-Condition ($confirmed.total -eq $order.total) 'Menu changes repriced the order.'
    Assert-Condition (($confirmed.items | ConvertTo-Json -Compress) -eq ($order.items | ConvertTo-Json -Compress)) 'Confirmation changed the item snapshots.'
    Write-Output "Orders passed: order=$($order.id), total=$($order.total) BRL."
    if ($OrderOnly) { return }
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
        $persistedUser = Invoke-Api 'GET' $userPath
        $persistedIdentity = Invoke-Api 'GET' '/api/users/auth/me' -AccessToken $authentication.accessToken
        Assert-Condition ($persistedIdentity.id -eq $user.id) 'The existing token stopped resolving after restart.'
        $newAuthentication = Invoke-Api 'POST' '/api/users/auth/login' @{ email = $demoEmail; password = $demoPassword }
        $newIdentity = Invoke-Api 'GET' '/api/users/auth/me' -AccessToken $newAuthentication.accessToken
        Assert-Condition ($newIdentity.id -eq $user.id) 'Persisted credentials did not allow login after restart.'
        $persistedAddress = Invoke-Api 'GET' $savedAddressPath
        Assert-Condition (($persistedUser | ConvertTo-Json -Compress) -eq ($updatedUser | ConvertTo-Json -Compress)) 'User profile changed after restart.'
        Assert-Condition (($persistedAddress | ConvertTo-Json -Compress) -eq ($updatedAddress | ConvertTo-Json -Compress)) 'User address changed after restart.'
        $persistedRestaurant = Invoke-Api 'GET' "/api/catalog/restaurants/$($restaurant.id)"
        $persistedMenuItem = Invoke-Api 'GET' $itemPath
        $persistedOrder = Invoke-Api 'GET' $orderPath
        $persistedDelivery = Invoke-Api 'GET' $deliveryPath
        $persistedPlan = Invoke-Api 'GET' "$deliveryPath/route"
        Assert-Condition ($persistedRestaurant.id -eq $restaurant.id) 'Restaurant was lost after restart.'
        Assert-Condition (($persistedMenuItem | ConvertTo-Json -Compress) -eq ($updatedItem | ConvertTo-Json -Compress)) 'Menu item changed after restart.'
        Assert-Condition ($persistedOrder.status -eq 'CONFIRMED') 'Order was lost after restart.'
        Assert-Condition ($persistedOrder.total -eq $order.total) 'Order total changed after restart.'
        Assert-Condition ($persistedOrder.currency -eq $order.currency) 'Order currency changed after restart.'
        Assert-Condition (($persistedOrder.items | ConvertTo-Json -Compress) -eq ($order.items | ConvertTo-Json -Compress)) 'Item snapshots changed after restart.'
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
