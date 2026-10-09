[CmdletBinding()]
param(
    [uri]$GatewayUrl = 'http://localhost:8080',
    [string]$CatalogFile = (Join-Path $PSScriptRoot 'demo-catalog.json'),
    [string]$EnvFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env')
)

# Creates the demo restaurants and menus through the Gateway. Records are matched by name, so running it again
# only adds what is missing; existing restaurants and items are left as they are, including edited prices.
$ErrorActionPreference = 'Stop'
$baseUrl = $GatewayUrl.AbsoluteUri.TrimEnd('/')

# Only the operator changes the catalog. Credentials come from the environment or the local .env, never arguments.
function Get-Setting([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if (-not $value -and (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
        $line = Get-Content -LiteralPath $EnvFile -Encoding UTF8 | Where-Object { $_ -like "$Name=*" } | Select-Object -First 1
        if ($line) { $value = $line.Substring($Name.Length + 1).Trim() }
    }
    if (-not $value) { throw "$Name is not configured. Run scripts/initialize-auth-secret.ps1 and restart the demo." }
    return $value
}

$login = @{ email = (Get-Setting 'USER_OPERATOR_EMAIL'); password = (Get-Setting 'USER_OPERATOR_PASSWORD') } | ConvertTo-Json
$token = (Invoke-RestMethod -Method Post -Uri "$baseUrl/api/users/auth/login" -ContentType 'application/json' -Body $login).accessToken
$headers = @{ Authorization = "Bearer $token" }

# Requests and responses are decoded as UTF-8 explicitly: Windows PowerShell would otherwise assume ISO-8859-1
# for JSON without a charset and duplicate names with accents on every run.
function Invoke-Catalog([string]$Method, [string]$Path, $Body = $null) {
    $request = @{ Method = $Method; Uri = "$baseUrl$Path"; UseBasicParsing = $true; Headers = $headers }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 5 -Compress))
    }
    $response = Invoke-WebRequest @request
    $text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    if ($text) { return $text | ConvertFrom-Json }
}

function Get-AllPages([string]$Path) {
    $page = 0
    do {
        $result = Invoke-Catalog 'GET' "${Path}?page=$page&size=100"
        $result.content
        $page++
    } while ($page -lt $result.totalPages)
}

$catalog = Get-Content -LiteralPath $CatalogFile -Raw -Encoding UTF8 | ConvertFrom-Json
$existingRestaurants = @(Get-AllPages '/api/catalog/restaurants')
foreach ($definition in $catalog.restaurants) {
    $restaurant = $existingRestaurants | Where-Object { $_.name -ceq $definition.name } | Select-Object -First 1
    if ($restaurant) {
        Write-Output "Restaurant '$($definition.name)' already exists."
    } else {
        $restaurant = Invoke-Catalog 'POST' '/api/catalog/restaurants' @{
            name = $definition.name
            pickupLocation = $definition.pickupLocation
        }
        Write-Output "Restaurant '$($definition.name)' created."
    }

    $menuPath = "/api/catalog/restaurants/$($restaurant.id)/menu-items"
    $existingItems = @(Get-AllPages $menuPath)
    foreach ($item in $definition.menu) {
        if ($existingItems | Where-Object { $_.name -ceq $item.name }) { continue }
        $body = @{ name = $item.name; price = $item.price }
        if ($item.description) { $body.description = $item.description }
        $created = Invoke-Catalog 'POST' $menuPath $body
        if ($item.available -eq $false) {
            $body.available = $false
            Invoke-Catalog 'PUT' "$menuPath/$($created.id)" $body | Out-Null
        }
        Write-Output "  Item '$($item.name)' created."
    }
}
Write-Output 'Demo catalog ready.'
