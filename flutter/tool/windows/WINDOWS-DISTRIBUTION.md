# Windows portable Release distribution

`flutter build windows --release` produces the application and engine files, but
a ZIP also needs the Microsoft C++ runtime used by the runner. The original CI
bundle's PE imports directly named `MSVCP140.dll`, `VCRUNTIME140.dll` and
`VCRUNTIME140_1.dll`; those three files were absent from that ZIP.

Flutter's [official Windows distribution instructions](https://docs.flutter.dev/platform-integration/windows/building#building-your-own-zip-file-for-windows)
explicitly identify these three app-local runtime DLLs. Microsoft's
[redistribution guidance](https://learn.microsoft.com/en-us/cpp/windows/redistributing-visual-cpp-files)
distinguishes distributable release runtime files from development/debug files.
The permission to redistribute Microsoft binaries is governed by the applicable
Visual Studio license and redistributable list; this repository's own license
does not relicense those vendor files.

`package-portable.ps1` copies exactly those three files from the installed Visual
Studio vendor **VC/Redist/MSVC** x64 CRT directory. It reads the actual
`Microsoft.VCRedistVersion.default.txt` version, requires a single release CRT
family, verifies Microsoft Authenticode signatures, records each file's version,
source, size and SHA-256, and copies into a **new** application staging directory.
It does not copy DLLs from System32, a compiler bin directory, a JDK, or an
arbitrary PATH. It does not modify installed software or the original bundle.
Vendor provenance is saved alongside the app and in the CI evidence.

The portable application's startup process has an explicitly constructed child
environment: application directory, Windows System32 and Windows on PATH, plus
only the user-profile/temp and Windows-directory variables required by an ordinary
GUI app. No Java environment variables or developer/toolchain paths are inherited.
`where.exe` in that same search scope must find neither `java.exe` nor `jvm.dll`.
No globally installed JDK is removed, moved or changed.

While the actual Release application is running, the test inspects its loaded
modules and requires all three CRT DLLs to come from the new application directory.
It also requires that neither `jvm.dll` nor `dartjni.dll` is loaded. After staying
alive for eight seconds, the app receives a normal native-window close request
and must exit with code zero. Only after successful validation is the portable
ZIP and its hash metadata generated.

The optional JNI library originates from the official `path_provider_android`
transitive package dependency, not Legado Java business code. It is retained in
the generated bundle. An unused DLL's import table is not evidence that the app
actually loads it. The isolated startup/module test determines the covered
runtime behavior without deleting generated plugins or downgrading dependencies.
It proves startup for the tested path, not the absence of Java requirements in
arbitrary future features or third-party plugins.

From a Windows runner after a successful Release build:

```powershell
./flutter/tool/windows/package-portable.ps1 `
  -BundleDirectory flutter/apps/legado/build/windows/x64/runner/Release `
  -OutputDirectory build/windows-portable-release `
  -EvidenceDirectory build/windows-app-acceptance `
  -SourceSha <exact-checked-out-commit>
```

The `.github/workflows/flutter-windows-app.yml` workflow runs the real application
integration suite before this packaging/startup gate. Its artifact contains the
portable ZIP, CRT vendor provenance, bundle hash, isolated startup outcome, loaded
module paths and stdout/stderr. Windows x64 V8 runtime, ARM64 cross-link, desktop
UI integration and portable packaging remain distinct evidence layers.
