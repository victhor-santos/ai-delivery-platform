[CmdletBinding()]
param(
    [string]$DatasetDirectory = 'services/route-intelligence-service/data/synthetic/segment-dataset-v1',
    [string]$ArtifactDirectory = 'services/route-intelligence-service/artifacts/segment-model-v1'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$image = 'delivery-order-system/route-intelligence-service:local'

function Get-AbsoluteDirectory([string]$Directory) {
    if ([System.IO.Path]::IsPathRooted($Directory)) {
        return [System.IO.Path]::GetFullPath($Directory)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $Directory))
}

function Invoke-Docker([string[]]$DockerArguments) {
    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed with exit code $LASTEXITCODE. Existing outputs were preserved."
    }
}

$dataset = Get-AbsoluteDirectory $DatasetDirectory
$artifact = Get-AbsoluteDirectory $ArtifactDirectory

Push-Location $repositoryRoot
try {
    Invoke-Docker -DockerArguments @('build', '--file', 'services/route-intelligence-service/Dockerfile', '--tag', $image, '.')

    if (Test-Path -LiteralPath $artifact) {
        Invoke-Docker -DockerArguments @('run', '--rm', '--mount', "type=bind,source=$artifact,target=/models,readonly",
            '--entrypoint', 'python', $image, '-c',
            "from pathlib import Path; from app.ml.artifacts import load_model; print(load_model(Path('/models'))[1].model_version)")
        Write-Output 'Existing model is compatible with the runtime image; no training was performed.'
        return
    }

    if (-not (Test-Path -LiteralPath $dataset)) {
        $datasetParent = Split-Path -Parent $dataset
        $datasetName = Split-Path -Leaf $dataset
        New-Item -ItemType Directory -Path $datasetParent -Force | Out-Null
        Invoke-Docker -DockerArguments @('run', '--rm', '--mount', "type=bind,source=$datasetParent,target=/data",
            '--entrypoint', 'python', $image, '-m', 'training.generate_dataset',
            '--output', "/data/$datasetName")
    }

    $artifactParent = Split-Path -Parent $artifact
    $artifactName = Split-Path -Leaf $artifact
    New-Item -ItemType Directory -Path $artifactParent -Force | Out-Null
    Invoke-Docker -DockerArguments @('run', '--rm', '--mount', "type=bind,source=$dataset,target=/data,readonly",
        '--mount', "type=bind,source=$artifactParent,target=/artifacts", '--entrypoint', 'python',
        $image, '-m', 'training.train', '--dataset', '/data', '--output', "/artifacts/$artifactName")
    Invoke-Docker -DockerArguments @('run', '--rm', '--mount', "type=bind,source=$dataset,target=/data,readonly",
        '--mount', "type=bind,source=$artifact,target=/models", '--entrypoint', 'python',
        $image, '-m', 'training.evaluate', '--dataset', '/data', '--artifact', '/models',
        '--output', '/models/test-report.json')
    Write-Output "Model prepared at $artifact. Set ROUTE_INTELLIGENCE_MODEL_DIR to this directory for Compose."
} finally {
    Pop-Location
}
