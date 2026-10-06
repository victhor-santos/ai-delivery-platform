[CmdletBinding()]
param([string]$EnvFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'))

$ErrorActionPreference = 'Stop'
$environmentPath = [System.IO.Path]::GetFullPath($EnvFile)
if (-not (Test-Path -LiteralPath $environmentPath -PathType Leaf)) {
    throw 'Create the environment file from .env.example before generating the authentication secret.'
}
$content = [System.IO.File]::ReadAllText($environmentPath)
$pattern = '(?m)^USER_AUTH_SECRET=([^\r\n]*)'
$entries = [regex]::Matches($content, $pattern)
if ($entries.Count -gt 1) { throw 'Duplicate USER_AUTH_SECRET entries. Resolve them before generating a key.' }
if ($entries.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace($entries[0].Groups[1].Value)) {
    Write-Output 'USER_AUTH_SECRET is already configured; the environment file was preserved.'
    return
}
$randomBytes = New-Object byte[] 32
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
$entry = 'USER_AUTH_SECRET=' + [Convert]::ToBase64String($randomBytes)
if ($entries.Count -eq 1) {
    $content = [regex]::Replace($content, $pattern, $entry)
} else {
    $content = $content.TrimEnd("`r", "`n") + "`r`n" + $entry + "`r`n"
}
[System.IO.File]::WriteAllText($environmentPath, $content, [System.Text.UTF8Encoding]::new($false))
Write-Output 'A random authentication secret was saved locally. Its value is not displayed.'
