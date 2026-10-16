param(
  [Parameter(Mandatory = $true)][string]$BundleDirectory,
  [Parameter(Mandatory = $true)][string]$OutputDirectory,
  [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
  [string]$SourceSha = '',
  [string]$VisualStudioRoot = 'C:/Program Files/Microsoft Visual Studio/18/Enterprise'
)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'Portable Windows packaging requires Windows PowerShell 7' }
$bundle = (Resolve-Path $BundleDirectory).Path
if (Test-Path $OutputDirectory) { throw 'Portable output directory must be new' }
$evidence = (New-Item -ItemType Directory -Force $EvidenceDirectory).FullName

# Only the vendor-designated redistributable tree is an allowed CRT source.
# Do not copy from System32, a compiler bin directory, a JDK or an arbitrary PATH.
$versionFile = Join-Path $VisualStudioRoot 'VC/Auxiliary/Build/Microsoft.VCRedistVersion.default.txt'
$version = (Get-Content $versionFile -Raw).Trim()
if ($version -notmatch '^14\.\d+\.\d+(\.\d+)?$') { throw 'Expected an explicit MSVC redistributable version' }
$redistRoot = Join-Path $VisualStudioRoot "VC/Redist/MSVC/$version/x64"
$families = @(Get-ChildItem $redistRoot -Directory -Filter 'Microsoft.VC*.CRT')
if ($families.Count -ne 1) { throw 'Expected exactly one vendor x64 CRT redistributable directory' }
$crtNames = @('msvcp140.dll', 'vcruntime140.dll', 'vcruntime140_1.dll')
$crt = @()
foreach ($name in $crtNames) {
  $source = Join-Path $families[0].FullName $name
  if (-not (Test-Path $source -PathType Leaf)) { throw "Vendor redistributable is missing $name" }
  $signature = Get-AuthenticodeSignature $source
  if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') {
    throw "CRT redistributable lacks a valid Microsoft signature: $name"
  }
  $item = Get-Item $source
  $fileVersion = [Version]::new($item.VersionInfo.FileMajorPart, $item.VersionInfo.FileMinorPart,
                              $item.VersionInfo.FileBuildPart, $item.VersionInfo.FilePrivatePart)
  if ($fileVersion -lt [Version]$version) { throw "CRT DLL is older than the declared redistributable: $name" }
  $crt += @{
    name = $name; source = $item.FullName; size = $item.Length;
    sha256 = (Get-FileHash $source -Algorithm SHA256).Hash.ToLower();
    version = $fileVersion.ToString(); microsoftAuthenticodeValid = $true;
    signer = $signature.SignerCertificate.Subject
  }
}
Copy-Item $bundle $OutputDirectory -Recurse
$portable = (Resolve-Path $OutputDirectory).Path
foreach ($item in $crt) {
  $destination = Join-Path $portable $item.name
  if (Test-Path $destination) { throw "Input bundle already contains an unaudited CRT copy: $($item.name)" }
  Copy-Item $item.source $destination
  if ((Get-FileHash $destination -Algorithm SHA256).Hash.ToLower() -ne $item.sha256) {
    throw 'CRT bytes changed while packaging'
  }
}
if (@(Get-ChildItem $portable -Recurse -File -Filter jvm.dll).Count -ne 0) {
  throw 'The portable bundle must not silently ship a JVM'
}
$origin = @{
  schemaVersion = 1; sourceSha = $SourceSha; architecture = 'x64';
  distribution = 'app-local Microsoft Visual C++ release redistributables';
  redistributableVersion = $version; versionFileSha256 = (Get-FileHash $versionFile -Algorithm SHA256).Hash.ToLower();
  files = $crt;
  documentation = @(
    'https://docs.flutter.dev/platform-integration/windows/building#building-your-own-zip-file-for-windows',
    'https://learn.microsoft.com/en-us/cpp/windows/redistributing-visual-cpp-files'
  )
}
$origin | ConvertTo-Json -Depth 8 | Set-Content "$portable/windows-runtime-provenance.json" -Encoding utf8
$origin | ConvertTo-Json -Depth 8 | Set-Content "$evidence/windows-runtime-provenance.json" -Encoding utf8

# This environment is scoped to newly created child processes. The runner and
# its installed SDK/JDK are unchanged; neither their PATH nor credentials leak.
function New-IsolatedStartInfo([string]$program) {
  $start = [System.Diagnostics.ProcessStartInfo]::new()
  $start.FileName = $program
  $start.WorkingDirectory = $portable
  $start.UseShellExecute = $false
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  $start.Environment.Clear()
  foreach ($key in @('SystemRoot', 'WINDIR', 'SystemDrive', 'TEMP', 'TMP', 'USERPROFILE',
                    'LOCALAPPDATA', 'APPDATA', 'HOMEDRIVE', 'HOMEPATH')) {
    $value = [Environment]::GetEnvironmentVariable($key)
    if ($null -ne $value) { $start.Environment[$key] = $value }
  }
  $start.Environment['PATH'] = "$portable;$env:SystemRoot/System32;$env:SystemRoot"
  return $start
}
# Verify that ordinary executable/DLL discovery in this child's search scope
# cannot find Java or a JVM. Do not delete or move a globally installed JDK.
foreach ($name in @('java.exe', 'jvm.dll')) {
  $lookup = New-IsolatedStartInfo "$env:SystemRoot/System32/where.exe"
  $lookup.ArgumentList.Add($name)
  $probe = [System.Diagnostics.Process]::Start($lookup)
  $text = $probe.StandardOutput.ReadToEnd()
  $errorText = $probe.StandardError.ReadToEnd()
  if (-not $probe.WaitForExit(10000)) { $probe.Kill(); throw 'Dependency discovery probe timed out' }
  if ($probe.ExitCode -ne 1) { throw "Isolated child can discover an external Java dependency: $name" }
  $probe.Dispose()
}
$start = New-IsolatedStartInfo (Join-Path $portable 'legado_app.exe')
$process = [System.Diagnostics.Process]::Start($start)
$stdout = $process.StandardOutput.ReadToEndAsync()
$stderr = $process.StandardError.ReadToEndAsync()
try {
  Start-Sleep -Seconds 8
  $process.Refresh()
  if ($process.HasExited) { throw "Portable Release exited during startup: $($process.ExitCode)" }
  $modules = @($process.Modules | ForEach-Object { @{name = $_.ModuleName; path = $_.FileName} })
  if (@($modules | Where-Object { $_.name -ieq 'jvm.dll' -or $_.name -ieq 'dartjni.dll' }).Count -ne 0) {
    throw 'Unexpected JNI/JVM library loaded during the isolated Release startup'
  }
  foreach ($module in $modules) {
    if ($module.name -match '^(msvcp|vcruntime|concrt|vccorlib)140.*\.dll$' -and
        $module.name.ToLower() -notin $crtNames) {
      throw "Additional C++ runtime requires explicit redistribution review: $($module.name)"
    }
  }
  foreach ($item in $crt) {
    $loaded = @($modules | Where-Object { $_.name -ieq $item.name })
    if ($loaded.Count -ne 1 -or
        -not [string]::Equals([IO.Path]::GetFullPath($loaded[0].path),
          [IO.Path]::GetFullPath((Join-Path $portable $item.name)), [StringComparison]::OrdinalIgnoreCase)) {
      throw "CRT was not loaded from the app-local bundle: $($item.name)"
    }
  }
  if (-not $process.CloseMainWindow()) { throw 'Portable app window did not accept close request' }
  if (-not $process.WaitForExit(15000)) { throw 'Portable app did not shut down gracefully' }
  if ($process.ExitCode -ne 0) { throw "Portable app shutdown failed: $($process.ExitCode)" }
  @{
    sourceSha = $SourceSha; platform = 'windows-x64'; buildType = 'Release';
    startupProcessStayedAlive = $true; gracefulWindowClose = $true; exitCode = $process.ExitCode;
    environmentScope = 'child process only'; searchPath = @('bundle', 'Windows/System32', 'Windows');
    javaHomeAbsent = $true; javaCommandVisible = $false; jvmLibraryVisible = $false;
    jniModuleLoaded = $false; jvmModuleLoaded = $false; appLocalCrtLoaded = $true;
    executableSha256 = (Get-FileHash "$portable/legado_app.exe" -Algorithm SHA256).Hash.ToLower();
    nativeBridgeSha256 = (Get-FileHash "$portable/source_v8.dll" -Algorithm SHA256).Hash.ToLower();
    loadedModules = $modules
  } | ConvertTo-Json -Depth 8 | Set-Content "$evidence/portable-startup.json" -Encoding utf8
} finally {
  if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit(10000) | Out-Null }
  $stdout.GetAwaiter().GetResult() | Set-Content "$evidence/portable.stdout.log" -Encoding utf8
  $stderr.GetAwaiter().GetResult() | Set-Content "$evidence/portable.stderr.log" -Encoding utf8
  $process.Dispose()
}
$zip = Join-Path $evidence 'legado-windows-x64-portable-release.zip'
Compress-Archive -Path "$portable/*" -DestinationPath $zip
@{
  name = [IO.Path]::GetFileName($zip); size = (Get-Item $zip).Length;
  sha256 = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower(); sourceSha = $SourceSha
} | ConvertTo-Json | Set-Content "$evidence/portable-bundle.json" -Encoding utf8
