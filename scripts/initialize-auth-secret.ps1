[CmdletBinding()]
param([string]$EnvFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'))

$ErrorActionPreference = 'Stop'
$environmentPath = [System.IO.Path]::GetFullPath($EnvFile)
if (-not (Test-Path -LiteralPath $environmentPath -PathType Leaf)) {
    throw 'Create the environment file from .env.example before generating the authentication keys.'
}

function New-RsaPrivateKey {
    $rsa = [System.Security.Cryptography.RSA]::Create()
    if ($rsa.PSObject.Methods.Name -contains 'ExportPkcs8PrivateKey') {
        try { $rsa.KeySize = 2048; return [Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey()) } finally { $rsa.Dispose() }
    }
    $rsa.Dispose()
    # Windows PowerShell 5.1 (.NET Framework) exports PKCS#8 only through CNG.
    $parameters = [System.Security.Cryptography.CngKeyCreationParameters]::new()
    $parameters.ExportPolicy = [System.Security.Cryptography.CngExportPolicies]::AllowPlaintextExport
    $parameters.Parameters.Add([System.Security.Cryptography.CngProperty]::new('Length',
        [BitConverter]::GetBytes(2048), [System.Security.Cryptography.CngPropertyOptions]::None))
    $key = [System.Security.Cryptography.CngKey]::Create([System.Security.Cryptography.CngAlgorithm]::Rsa, $null, $parameters)
    try { return [Convert]::ToBase64String($key.Export([System.Security.Cryptography.CngKeyBlobFormat]::Pkcs8PrivateBlob)) }
    finally { $key.Dispose() }
}

function Set-MissingEntry([string]$Name, [scriptblock]$Generate) {
    $content = [System.IO.File]::ReadAllText($environmentPath)
    $pattern = "(?m)^$Name=([^\r\n]*)"
    $entries = [regex]::Matches($content, $pattern)
    if ($entries.Count -gt 1) { throw "Duplicate $Name entries. Resolve them before generating a value." }
    if ($entries.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace($entries[0].Groups[1].Value)) {
        Write-Output "$Name is already configured; the environment file was preserved."
        return
    }
    $entry = "$Name=" + (& $Generate)
    if ($entries.Count -eq 1) {
        $content = [regex]::Replace($content, $pattern, { param($match) $entry })
    } else {
        $content = $content.TrimEnd("`r", "`n") + "`r`n" + $entry + "`r`n"
    }
    [System.IO.File]::WriteAllText($environmentPath, $content, [System.Text.UTF8Encoding]::new($false))
    Write-Output "A random $Name was saved locally. Its value is not displayed."
}

Set-MissingEntry 'USER_AUTH_PRIVATE_KEY' { New-RsaPrivateKey }
if ([regex]::IsMatch([System.IO.File]::ReadAllText($environmentPath), '(?m)^USER_AUTH_SECRET=')) {
    Write-Output 'USER_AUTH_SECRET is no longer used: tokens are now signed with USER_AUTH_PRIVATE_KEY. You may remove it.'
}
