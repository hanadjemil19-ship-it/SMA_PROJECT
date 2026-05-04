$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$srcRoot  = Join-Path $repoRoot "src\main\java"
$outFile  = Join-Path $repoRoot "ALL_SOURCE_CODE_MAIN.txt"

if (-not (Test-Path $srcRoot)) {
  throw "Missing folder: $srcRoot"
}

$files = Get-ChildItem -Path $srcRoot -Recurse -Filter *.java | Sort-Object FullName

$nl = "`r`n"
$sb = New-Object System.Text.StringBuilder

[void]$sb.Append("SRUU - All Production Source Code (src/main/java)$nl")
[void]$sb.Append("Generated: $(Get-Date -Format s)$nl")
[void]$sb.Append($nl)

foreach ($f in $files) {
  $relative = $f.FullName.Substring($repoRoot.Length + 1) -replace '\\','/'
  [void]$sb.Append("================================================================================$nl")
  [void]$sb.Append("FILE: $relative$nl")
  [void]$sb.Append("================================================================================$nl")
  [void]$sb.Append((Get-Content -LiteralPath $f.FullName -Raw))
  if (-not ($sb.ToString().EndsWith($nl))) { [void]$sb.Append($nl) }
  [void]$sb.Append($nl)
}

Set-Content -LiteralPath $outFile -Value $sb.ToString() -Encoding UTF8
Write-Host "Wrote $($files.Count) files to $outFile"

