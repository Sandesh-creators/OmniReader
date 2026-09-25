param(
    [string]$Version = "1.0.0",
    [switch]$Msi,
    [switch]$Exe
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

function Fail($message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

if (-not $env:JAVA_HOME) {
    $candidates = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft\jdk-17*",
        "C:\Program Files\Android\Android Studio\jbr"
    )
    foreach ($candidate in $candidates) {
        $found = Get-ChildItem -Path $candidate -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName "bin\jpackage.exe") } |
            Sort-Object Name -Descending |
            Select-Object -First 1
        if ($found) {
            $env:JAVA_HOME = $found.FullName
            break
        }
    }
}

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) {
    Fail "A JDK 17+ with jpackage.exe is required. Install Temurin JDK 17 and set JAVA_HOME."
}

$javaMajor = (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Select-Object -First 1) -replace '.*"(\d+).*', '$1'
if ([int]$javaMajor -lt 17) {
    Fail "JDK 17 or newer is required (found $javaMajor)."
}

Write-Host "Using JDK at $env:JAVA_HOME" -ForegroundColor Cyan

$env:GRADLE_OPTS = "-Dorg.gradle.jvmargs=-Xmx4096m"

& "$ProjectRoot\gradlew.bat" :desktopApp:createDistributable --console=plain
if ($LASTEXITCODE -ne 0) { Fail "createDistributable failed." }

$outputDir = Join-Path $ProjectRoot "desktopApp\build\compose\binaries\main\app"
$appImageDir = Join-Path $outputDir "OmniReader"
if (-not (Test-Path $appImageDir)) {
    Fail "App image not found at $appImageDir"
}

$iconDir = Join-Path $ProjectRoot "desktopApp\build\windows-resources\omnireader"
New-Item -ItemType Directory -Force -Path $iconDir | Out-Null
$iconPath = Join-Path $iconDir "omnireader.ico"
$desktopPath = Join-Path $iconDir "omnireader.desktop"
$setupPath = Join-Path $iconDir "setup.exe"
$licensePath = Join-Path $iconDir "LICENSE.rtf"

Set-Content -Path $desktopPath -Encoding UTF8 -Value @"
[Desktop Entry]
Type=Application
Name=OmniReader
Comment=EPUB reader with library, author collections and text-to-speech
Exec=OmniReader
Icon=omnireader
Categories=Office;Reader;Education;
Terminal=false
StartupWMClass=OmniReader
Keywords=epub;reader;book;tts;
"@

Set-Content -Path $setupPath -Encoding ASCII -Value "OmniReader Setup Launcher placeholder."
Set-Content -Path $licensePath -Encoding ASCII -Value @"
{\rtf1\ansi OmniReader is distributed as-is. Use at your own risk.\par
}
"@

$iconGenerated = $false
try {
    Add-Type -AssemblyName System.Drawing
    $bitmap = New-Object System.Drawing.Bitmap 256, 256
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(67, 86, 232))
    $graphics.FillRectangle($brush, 0, 0, 256, 256)
    $textBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
    $font = New-Object System.Drawing.Font "Arial", 84, ([System.Drawing.FontStyle]::Bold)
    $format = New-Object System.Drawing.StringFormat
    $format.Alignment = [System.Drawing.StringAlignment]::Center
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
    $graphics.DrawString("OR", $font, $textBrush, (New-Object System.Drawing.RectangleF 0, 0, 256, 256), $format)
    $graphics.Dispose()
    $iconDirTmp = Join-Path $env:TEMP "omnireader-icon"
    New-Item -ItemType Directory -Force -Path $iconDirTmp | Out-Null
    $pngPath = Join-Path $iconDirTmp "omnireader.png"
    $bitmap.Save($pngPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $iconHandle = $bitmap.GetHicon()
    $iconObject = [System.Drawing.Icon]::FromHandle($iconHandle)
    $iconStream = [System.IO.File]::Create($iconPath)
    $iconObject.Save($iconStream)
    $iconStream.Close()
    $iconObject.Dispose()
    $bitmap.Dispose()
    $iconGenerated = $true
} catch {
    Write-Host "Could not rasterize icon: $($_.Exception.Message)" -ForegroundColor Yellow
}

$resourceArgs = @(
    "--add-modules", "java.instrument,java.management,jdk.unsupported,java.net.http,java.sql"
)
if (Test-Path $iconPath) { $resourceArgs = @("--icon", $iconPath) + $resourceArgs }
if (Test-Path $licensePath) { $resourceArgs += @("--license", $licensePath) }

$commonArgs = @(
    "--name", "OmniReader",
    "--app-version", $Version,
    "--vendor", "Sandesh",
    "--description", "EPUB reader with library, author collections and text-to-speech",
    "--copyright", "Copyright (c) 2026 Sandesh",
    "--dest", (Join-Path $ProjectRoot "desktopApp\build\compose\binaries\main\windows"),
    "--app-image", $appImageDir
) + $resourceArgs

if ($iconGenerated) {
    $commonArgs += @("--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-menu-group", "OmniReader")
}

if (-not $Msi -and -not $Exe) { $Msi = $true }

if ($Msi) {
    $msiArgs = $commonArgs + @(
        "--type", "msi",
        "--win-menu-entries",
        "--win-per-user-install"
    )
    Write-Host "Building MSI..." -ForegroundColor Cyan
    & "$env:JAVA_HOME\bin\jpackage.exe" @msiArgs
    if ($LASTEXITCODE -ne 0) { Fail "MSI build failed." }
}

if ($Exe) {
    $exeArgs = $commonArgs + @(
        "--type", "app-image",
        "--win-console", "omit"
    )
    Write-Host "Building Windows EXE app-image..." -ForegroundColor Cyan
    & "$env:JAVA_HOME\bin\jpackage.exe" @exeArgs
    if ($LASTEXITCODE -ne 0) { Fail "EXE build failed." }
}

$distDir = Join-Path $ProjectRoot "desktopApp\build\compose\binaries\main\windows"
if (Test-Path $distDir) {
    Get-ChildItem -Path $distDir | ForEach-Object {
        Write-Host ("Artifact: {0} ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB)) -ForegroundColor Green
    }
}
