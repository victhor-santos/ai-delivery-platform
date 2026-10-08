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

# PowerShell 7 parses ISO timestamps from JSON into DateTime; query strings need the ISO-8601 form back.
function Format-Instant($Value) {
    if ($Value -is [datetime]) { return $Value.ToUniversalTime().ToString('o') }
    return [string]$Value
}

function Invoke-Api([string]$Method, [string]$Path, $Body = $null, [int]$Status = 200, [switch]$Raw, [string]$AccessToken,
    [string]$IdempotencyKey) {
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method), "$baseUrl$Path")
    $response = $null
    try {
        if ($AccessToken) {
            $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $AccessToken)
        }
        if ($IdempotencyKey) { $request.Headers.Add('Idempotency-Key', $IdempotencyKey) }
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

    $services = if ($CatalogOnly) { @('catalog') } elseif ($OrderOnly) { @('catalog', 'orders', 'payments') }
        elseif ($UsersOnly) { @('users') }
        else { @('users', 'catalog', 'orders', 'payments', 'deliveries') }
    foreach ($service in $services) {
        Invoke-Api 'GET' "/api/$service/ping" | Out-Null
    }
    if (-not $CatalogOnly) {
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
        $accessToken = $authentication.accessToken
        $otherEmail = "other-$([guid]::NewGuid().ToString('N'))@example.test"
        $otherUser = Invoke-Api 'POST' '/api/users/auth/register' @{
            name = 'Outro Cliente'; email = $otherEmail; password = $demoPassword
        } 201
        $otherToken = (Invoke-Api 'POST' '/api/users/auth/login' @{ email = $otherEmail; password = $demoPassword }).accessToken
        Write-Output "Authentication passed: user=$($user.id)."
    }
    if (-not $CatalogOnly -and -not $OrderOnly) {
        Invoke-Api 'POST' '/api/users/auth/register' @{
            name = 'Duplicado'; email = $demoEmail; password = 'another-password-123'
        } 409 | Out-Null
        $userPath = "/api/users/$($user.id)"
        Invoke-Api 'GET' $userPath -Status 401 | Out-Null
        Invoke-Api 'GET' $userPath -AccessToken $otherToken -Status 404 | Out-Null
        Invoke-Api 'PUT' "$userPath/profile" @{ name = 'Invasor' } 404 -AccessToken $otherToken | Out-Null
        Invoke-Api 'POST' '/api/users' @{ name = 'Sem senha'; email = $otherEmail } 404 -AccessToken $accessToken -Raw | Out-Null
        $updatedUser = Invoke-Api 'PUT' "$userPath/profile" @{ name = 'Cliente Atualizado' } -AccessToken $accessToken
        Assert-Condition ($updatedUser.id -eq $user.id -and $updatedUser.email -eq $user.email) 'Profile update changed identity or email.'
        Assert-Condition ($updatedUser.name -eq 'Cliente Atualizado') 'Profile name was not updated.'
        $addressPath = "$userPath/addresses"
        $address = Invoke-Api 'POST' $addressPath @{
            label = 'Casa'; address = 'Destino sintetico C'; latitude = -23.561; longitude = -46.656
        } 201 -AccessToken $accessToken
        Assert-Condition ($address.userId -eq $user.id) 'Address belongs to another user.'
        $savedAddressPath = "$addressPath/$($address.id)"
        $updatedAddress = Invoke-Api 'PUT' $savedAddressPath @{
            label = 'Entrega'; address = 'Destino sintetico atualizado'; latitude = -23.562; longitude = -46.657
        } -AccessToken $accessToken
        Assert-Condition ($updatedAddress.id -eq $address.id -and $updatedAddress.userId -eq $user.id) 'Address update changed identity or owner.'
        Assert-Condition ($updatedAddress.label -eq 'Entrega' -and $updatedAddress.address -eq 'Destino sintetico atualizado' -and
            $updatedAddress.latitude -eq -23.562 -and $updatedAddress.longitude -eq -46.657) 'Address details were not updated.'
        $addressPage = Invoke-Api 'GET' "${addressPath}?page=0&size=1" -AccessToken $accessToken
        Assert-Condition ($addressPage.totalElements -eq 1 -and $addressPage.items[0].id -eq $address.id) 'Address pagination lost the saved address.'
        $otherAddressPath = "/api/users/$($otherUser.id)/addresses/$($address.id)"
        Invoke-Api 'GET' $otherAddressPath -AccessToken $otherToken -Status 404 | Out-Null
        Invoke-Api 'PUT' $otherAddressPath @{
            label = 'Alterado'; address = 'Outro destino'; latitude = 0; longitude = 0
        } 404 -AccessToken $otherToken | Out-Null
        Invoke-Api 'GET' $savedAddressPath -AccessToken $otherToken -Status 404 | Out-Null
        Invoke-Api 'PUT' $savedAddressPath @{
            label = 'Alterado'; address = 'Outro destino'; latitude = 0; longitude = 0
        } 404 -AccessToken $otherToken | Out-Null
        $storedUser = Invoke-Api 'GET' $userPath -AccessToken $accessToken
        $storedAddress = Invoke-Api 'GET' $savedAddressPath -AccessToken $accessToken
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
    $orderRequest = @{
        restaurantId = $restaurant.id
        items = @(@{ menuItemId = $menuItem.id; quantity = 2 })
        destination = @{ address = 'Destino sintetico C'; latitude = -23.561; longitude = -46.656 }
    }
    Invoke-Api 'POST' '/api/orders' $orderRequest 401 | Out-Null
    $order = Invoke-Api 'POST' '/api/orders' $orderRequest 201 -AccessToken $accessToken
    Assert-Condition ($order.customerId -eq $user.id) 'Order was not linked to the authenticated customer.'
    Assert-Condition ($order.currency -eq 'BRL' -and $order.total -eq [decimal]59.80) 'Order total was not calculated from the menu.'
    Assert-Condition ($order.items.Count -eq 1 -and $order.items[0].unitPrice -eq [decimal]29.90) 'Order item snapshot is invalid.'
    $updatedItem = Invoke-Api 'PUT' $itemPath @{
        name = 'Prato com novo preco'; price = [decimal]39.90; available = $false
    }
    $orderPath = "/api/orders/$($order.id)"
    Invoke-Api 'GET' $orderPath -Status 401 | Out-Null
    foreach ($foreignCall in @(@('GET', ''), @('POST', '/cancel'), @('POST', '/delivery'))) {
        Invoke-Api $foreignCall[0] "$orderPath$($foreignCall[1])" -AccessToken $otherToken -Status 404 | Out-Null
    }
    $storedOrder = Invoke-Api 'GET' $orderPath -AccessToken $accessToken
    Assert-Condition ($storedOrder.status -eq 'CREATED') 'Another customer changed the order.'
    Assert-Condition ($storedOrder.total -eq $order.total -and $storedOrder.currency -eq $order.currency) 'Menu changes altered the stored order total or currency.'
    Assert-Condition (($storedOrder.items | ConvertTo-Json -Compress) -eq ($order.items | ConvertTo-Json -Compress)) 'Menu changes altered the stored item snapshots.'
    Assert-Condition ($null -eq $storedOrder.paymentRequestedAt -and $null -eq $storedOrder.paymentId) 'A new order already has a payment.'
    Invoke-Api 'POST' "$orderPath/confirm" -AccessToken $accessToken -Status 404 | Out-Null
    Invoke-Api 'POST' "$orderPath/delivery" -AccessToken $accessToken -Status 409 | Out-Null
    Write-Output "Orders passed: order=$($order.id), total=$($order.total) BRL."

    $paymentPath = "$orderPath/payment"
    $declinedKey = "smoke-$([guid]::NewGuid().ToString('N'))"
    $declinedRequest = @{ method = 'sim-card-insufficient-funds'; amount = [decimal]0.01 }
    Invoke-Api 'POST' $paymentPath $declinedRequest 401 -IdempotencyKey $declinedKey | Out-Null
    Invoke-Api 'POST' $paymentPath $declinedRequest 404 -AccessToken $otherToken -IdempotencyKey $declinedKey | Out-Null
    $declined = Invoke-Api 'POST' $paymentPath $declinedRequest -AccessToken $accessToken -IdempotencyKey $declinedKey
    Assert-Condition ($declined.status -eq 'DECLINED' -and $declined.declineReason -eq 'INSUFFICIENT_FUNDS') 'Simulated decline was not recorded.'
    Assert-Condition ($declined.amount -eq $order.total) 'The payment amount did not come from the order total.'
    $replayedDecline = Invoke-Api 'POST' $paymentPath $declinedRequest -AccessToken $accessToken -IdempotencyKey $declinedKey
    Assert-Condition ($replayedDecline.paymentId -eq $declined.paymentId) 'Payment retry with the same key created another attempt.'
    Invoke-Api 'POST' $paymentPath @{ method = 'sim-card-approved' } 422 -AccessToken $accessToken -IdempotencyKey $declinedKey | Out-Null
    Assert-Condition ($null -eq (Invoke-Api 'GET' $orderPath -AccessToken $accessToken).paymentRequestedAt) 'A decline left the order awaiting payment.'
    $directCharge = @{ orderId = $order.id; amount = $order.total; method = 'sim-card-approved' }
    Invoke-Api 'POST' '/api/payments' $directCharge 409 -AccessToken $accessToken -IdempotencyKey "smoke-$([guid]::NewGuid().ToString('N'))" | Out-Null
    $approvedKey = "smoke-$([guid]::NewGuid().ToString('N'))"
    $approvedRequest = @{ method = 'sim-card-approved' }
    $payment = Invoke-Api 'POST' $paymentPath $approvedRequest -AccessToken $accessToken -IdempotencyKey $approvedKey
    Assert-Condition ($payment.status -eq 'APPROVED' -and $payment.amount -eq $order.total -and $payment.currency -eq 'BRL') 'Simulated approval is invalid.'
    $confirmed = Invoke-Api 'GET' $orderPath -AccessToken $accessToken
    Assert-Condition ($confirmed.status -eq 'CONFIRMED' -and $confirmed.paymentId -eq $payment.paymentId) 'The approval did not confirm the order.'
    Assert-Condition ($confirmed.total -eq $order.total) 'Menu changes repriced the order.'
    Assert-Condition (($confirmed.items | ConvertTo-Json -Compress) -eq ($order.items | ConvertTo-Json -Compress)) 'Payment changed the item snapshots.'
    Invoke-Api 'POST' $paymentPath $approvedRequest 409 -AccessToken $accessToken -IdempotencyKey "smoke-$([guid]::NewGuid().ToString('N'))" | Out-Null
    Invoke-Api 'POST' "$orderPath/cancel" -AccessToken $accessToken -Status 409 | Out-Null
    $attemptPath = "/api/payments/$($payment.paymentId)"
    $attempt = Invoke-Api 'GET' $attemptPath -AccessToken $accessToken
    Assert-Condition ($attempt.status -eq 'APPROVED' -and $attempt.orderId -eq $order.id -and $attempt.simulated) 'Payment Service lost the approved attempt.'
    Invoke-Api 'GET' $attemptPath -AccessToken $otherToken -Status 404 | Out-Null
    $orderPayments = Invoke-Api 'GET' "/api/payments?orderId=$($order.id)" -AccessToken $accessToken
    Assert-Condition ($orderPayments.totalElements -eq 2 -and $orderPayments.items[1].id -eq $payment.paymentId) 'Payment history for the order is invalid.'
    Write-Output "Payments passed: declined=$($declined.paymentId), approved=$($payment.paymentId), order confirmed."
    if ($OrderOnly) { return }

    $receipt = Invoke-Api 'POST' "$orderPath/delivery" -AccessToken $accessToken
    $repeated = Invoke-Api 'POST' "$orderPath/delivery" -AccessToken $accessToken
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
    $cutoff = [uri]::EscapeDataString((Format-Instant $exit.labelAvailableAt))
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
        $persistedIdentity = Invoke-Api 'GET' '/api/users/auth/me' -AccessToken $authentication.accessToken
        Assert-Condition ($persistedIdentity.id -eq $user.id) 'The existing token stopped resolving after restart.'
        $newAuthentication = Invoke-Api 'POST' '/api/users/auth/login' @{ email = $demoEmail; password = $demoPassword }
        $newIdentity = Invoke-Api 'GET' '/api/users/auth/me' -AccessToken $newAuthentication.accessToken
        Assert-Condition ($newIdentity.id -eq $user.id) 'Persisted credentials did not allow login after restart.'
        $accessToken = $newAuthentication.accessToken
        $persistedUser = Invoke-Api 'GET' $userPath -AccessToken $accessToken
        $persistedAddress = Invoke-Api 'GET' $savedAddressPath -AccessToken $accessToken
        Assert-Condition (($persistedUser | ConvertTo-Json -Compress) -eq ($updatedUser | ConvertTo-Json -Compress)) 'User profile changed after restart.'
        Assert-Condition (($persistedAddress | ConvertTo-Json -Compress) -eq ($updatedAddress | ConvertTo-Json -Compress)) 'User address changed after restart.'
        $persistedRestaurant = Invoke-Api 'GET' "/api/catalog/restaurants/$($restaurant.id)"
        $persistedMenuItem = Invoke-Api 'GET' $itemPath
        $persistedOrder = Invoke-Api 'GET' $orderPath -AccessToken $accessToken
        $persistedAttempt = Invoke-Api 'GET' $attemptPath -AccessToken $accessToken
        Assert-Condition (($persistedAttempt | ConvertTo-Json -Compress) -eq ($attempt | ConvertTo-Json -Compress)) 'Payment changed after restart.'
        $replayedPayment = Invoke-Api 'POST' $paymentPath $approvedRequest -AccessToken $accessToken -IdempotencyKey $approvedKey
        Assert-Condition (($replayedPayment | ConvertTo-Json -Compress) -eq ($payment | ConvertTo-Json -Compress)) 'Order payment changed after restart.'
        $persistedDelivery = Invoke-Api 'GET' $deliveryPath
        $persistedPlan = Invoke-Api 'GET' "$deliveryPath/route"
        Assert-Condition ($persistedRestaurant.id -eq $restaurant.id) 'Restaurant was lost after restart.'
        Assert-Condition (($persistedMenuItem | ConvertTo-Json -Compress) -eq ($updatedItem | ConvertTo-Json -Compress)) 'Menu item changed after restart.'
        Assert-Condition ($persistedOrder.status -eq 'CONFIRMED' -and $persistedOrder.paymentId -eq $payment.paymentId) 'Order was lost after restart.'
        Assert-Condition ($persistedOrder.total -eq $order.total) 'Order total changed after restart.'
        Assert-Condition ($persistedOrder.currency -eq $order.currency) 'Order currency changed after restart.'
        Assert-Condition (($persistedOrder.items | ConvertTo-Json -Compress) -eq ($order.items | ConvertTo-Json -Compress)) 'Item snapshots changed after restart.'
        Assert-Condition (($persistedDelivery | ConvertTo-Json -Depth 10 -Compress) -eq ($delivery | ConvertTo-Json -Depth 10 -Compress)) 'Delivery changed after restart.'
        Assert-Condition (($persistedPlan | ConvertTo-Json -Depth 10 -Compress) -eq ($plan | ConvertTo-Json -Depth 10 -Compress)) 'Saved route changed after restart.'
        $persistedObservations = @(Invoke-Api 'GET' "$deliveryPath/segments")
        Assert-Condition (($persistedObservations | ConvertTo-Json -Depth 10 -Compress) -eq ($observations | ConvertTo-Json -Depth 10 -Compress)) 'Observation snapshots changed after restart.'
        Assert-Condition ((Invoke-Api 'GET' $exportPath -Raw) -eq $csv) 'CSV changed after restart for the same cutoff.'
        $recoveredReceipt = Invoke-Api 'POST' "$orderPath/delivery" -AccessToken $accessToken
        Assert-Condition ($recoveredReceipt.deliveryId -eq $receipt.deliveryId) 'Restart caused a duplicate delivery.'
        Write-Output 'Persistence passed after recreating containers; no volumes were removed.'
    }
} finally {
    $http.Dispose()
    Pop-Location
}
