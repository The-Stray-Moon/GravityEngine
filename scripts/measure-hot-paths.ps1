param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$output = Join-Path $repo 'build/hot-path-measurements'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac.exe' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java.exe' }
$baseline = '186f70fa765e1565c428768a2a5d5af178b0d8fc'
git -C $repo archive --format=zip --output="$output/baseline.zip" $baseline common/src/main/java
if ($LASTEXITCODE -ne 0) { throw 'baseline export failed' }
Expand-Archive -LiteralPath "$output/baseline.zip" -DestinationPath "$output/baseline" -Force
$harness = Join-Path $repo 'verification/measurements/HotPathMeasurements.java'
foreach ($version in @('baseline', 'current')) {
    $source = if ($version -eq 'baseline') { "$output/baseline/common/src/main/java" } else { "$repo/common/src/main/java" }
    $classes = "$output/$version-classes"
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $sources = @(Get-ChildItem -LiteralPath $source -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
    $sources += '"' + $harness.Replace('\','/') + '"'
    $arguments = "$output/$version-sources.txt"
    [IO.File]::WriteAllLines($arguments, $sources, [Text.UTF8Encoding]::new($false))
    & $javac --release 17 -encoding UTF-8 -d $classes "@$arguments"
    if ($LASTEXITCODE -ne 0) { throw "$version compilation failed" }
    # Finish compilation within warmup rather than allowing compiler scheduling
    # to contaminate a short sample window. These measure steady state, not startup.
    & $java -Xbatch -Xms256m -Xmx512m -cp $classes cc.sighs.gravityengine.HotPathMeasurements | Tee-Object -FilePath "$output/$version.csv"
    if ($LASTEXITCODE -ne 0) { throw "$version measurement failed" }
}
git -C $repo status --short | Set-Content "$output/worktree.txt"
Get-CimInstance Win32_Processor | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors | Format-List | Out-File "$output/environment.txt"
Get-CimInstance Win32_OperatingSystem | Select-Object Caption, Version, TotalVisibleMemorySize | Format-List | Out-File "$output/environment.txt" -Append
