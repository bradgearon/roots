[CmdletBinding()]
param()
$ErrorActionPreference='Stop'

function Test-OcrDirectory([string]$directory) {
    if ([string]::IsNullOrWhiteSpace($directory) -or -not [IO.Path]::IsPathRooted($directory)) { return $false }
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { return $false }
    $dll=Get-ChildItem -LiteralPath $directory -File -Filter '*tesseract*.dll' -ErrorAction SilentlyContinue | Select-Object -First 1
    return ($null -ne $dll -and (Test-Path -LiteralPath (Join-Path $directory 'tessdata\eng.traineddata') -PathType Leaf))
}
try {
    if ($env:ROOTS_TESSERACT_DIR) {
        if (Test-OcrDirectory $env:ROOTS_TESSERACT_DIR) {
            Write-Host 'Roots OCR: using configured local Tesseract.'
            exit 0
        }
        Write-Warning 'ROOTS_TESSERACT_DIR is not a complete installation. Correct it or unset it; not overriding a custom installation.'
        exit 1
    }
    $directory=Join-Path $env:ProgramFiles 'Tesseract-OCR'
    if (Test-OcrDirectory $directory) {
        Write-Host 'Roots OCR: using installed Tesseract.'
        exit 0
    }
    $winget=(Get-Command winget.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if (-not $winget) { throw 'Windows Package Manager (winget) is unavailable.' }
    Write-Host 'Roots OCR: installing Tesseract 5 through Windows Package Manager. Windows may request installation approval.'
    & $winget install --id UB-Mannheim.TesseractOCR --exact --source winget --version 5.4.0.20240606 --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
    $result=$LASTEXITCODE
    if (Test-OcrDirectory $directory) {
        Write-Host 'Roots OCR: local engine is installed.'
        exit 0
    }
    throw "Tesseract installation is not complete (winget exit $result)."
} catch {
    Write-Warning ("Roots OCR: "+$_.Exception.Message)
    exit 1
}
