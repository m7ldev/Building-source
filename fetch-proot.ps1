# Building source — puts Termux's proot (patched for Android) inside the app as "native libraries".
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jni  = Join-Path $root 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force -Path $jni | Out-Null
if ((Test-Path "$jni\libproot.so") -and (Test-Path "$jni\libproot-loader.so") -and (Test-Path "$jni\libtalloc.so") -and (Test-Path "$jni\libandroid-shmem.so")) {
    Write-Output "proot already present"; exit 0
}
$repo = 'https://packages.termux.dev/apt/termux-main'
$work = Join-Path $env:TEMP ('bs-proot-' + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $work | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Write-Output "Reading the Termux package list..."
$pk = Join-Path $work 'Packages'
Invoke-WebRequest -UseBasicParsing "$repo/dists/stable/main/binary-aarch64/Packages" -OutFile $pk
$idx = [System.IO.File]::ReadAllText($pk)
function Get-Deb($name) {
    $m = [regex]::Match($idx, "(?ms)^Package: $([regex]::Escape($name))\r?\n.*?^Filename: (\S+)")
    if (-not $m.Success) { throw "package $name not found" }
    $url = "$repo/" + $m.Groups[1].Value
    $dir = Join-Path $work $name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $deb = Join-Path $dir "$name.deb"
    Write-Host "Downloading $url"
    Invoke-WebRequest -UseBasicParsing $url -OutFile $deb
    Push-Location $dir
    tar -xf "$name.deb" | Out-Null
    $data = Get-ChildItem -Filter 'data.tar.*' | Select-Object -First 1
    tar -xf $data.Name 2>$null | Out-Null
    Pop-Location
    return [string]$dir
}
$p = @(Get-Deb 'proot')[-1]
$t = @(Get-Deb 'libtalloc')[-1]
$s = @(Get-Deb 'libandroid-shmem')[-1]
$proot  = Get-ChildItem -Recurse -Path $p | Where-Object { -not $_.PSIsContainer -and $_.Name -eq 'proot' -and $_.Length -gt 10000 } | Select-Object -First 1
$loader = Get-ChildItem -Recurse -Path $p | Where-Object { -not $_.PSIsContainer -and $_.Name -eq 'loader' } | Select-Object -First 1
$l32    = Get-ChildItem -Recurse -Path $p | Where-Object { -not $_.PSIsContainer -and $_.Name -eq 'loader32' } | Select-Object -First 1
$talloc = Get-ChildItem -Recurse -Path $t | Where-Object { -not $_.PSIsContainer -and $_.Name -like 'libtalloc.so*' -and $_.Length -gt 10000 } | Select-Object -First 1
if (-not $proot -or -not $loader -or -not $talloc) { throw "proot files not found in the packages" }
Copy-Item $proot.FullName  "$jni\libproot.so" -Force
Copy-Item $loader.FullName "$jni\libproot-loader.so" -Force
if ($l32) { Copy-Item $l32.FullName "$jni\libproot-loader32.so" -Force }
Copy-Item $talloc.FullName "$jni\libtalloc.so" -Force
$shm = Get-ChildItem -Recurse -Path $s | Where-Object { -not $_.PSIsContainer -and $_.Name -like 'libandroid-shmem.so*' -and $_.Length -gt 1000 } | Select-Object -First 1
if (-not $shm) { throw "libandroid-shmem.so not found" }
Copy-Item $shm.FullName "$jni\libandroid-shmem.so" -Force
Remove-Item -Recurse -Force $work
Write-Output "proot ready"
