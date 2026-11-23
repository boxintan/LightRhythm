param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$DependencyCache,
    [Parameter(Mandatory = $true)][string]$AndroidJar,
    [string]$SampleImage,
    [string]$DatasetManifest,
    [string]$ReportDirectory
)

# Gradle 不可用时的离线计算测试；不生成 APK，也不替代 Android 设备测试。
$ErrorActionPreference = 'Stop'
if ([bool]$DatasetManifest -ne [bool]$ReportDirectory) {
    throw 'DatasetManifest and ReportDirectory must be supplied together'
}
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Find-DependencyJar([string]$relativePath) {
    $jarFile = Get-ChildItem -LiteralPath (Join-Path $DependencyCache $relativePath) -Recurse -File -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Select-Object -First 1
    if (-not $jarFile) { throw "Missing cached dependency: $relativePath" }
    return $jarFile.FullName
}
$compilerJar = Find-DependencyJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.2.10'
$stdlibJar = Find-DependencyJar 'org.jetbrains.kotlin\kotlin-stdlib\2.2.10'
$reflectJar = Find-DependencyJar 'org.jetbrains.kotlin\kotlin-reflect\1.6.10'
$coroutinesJar = Find-DependencyJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.8.0'
$annotationsJar = Find-DependencyJar 'org.jetbrains\annotations\13.0'
$junitJar = Find-DependencyJar 'junit\junit\4.13.2'
$hamcrestJar = Find-DependencyJar 'org.hamcrest\hamcrest-core\1.3'
$compilerClasspath = @($compilerJar, $stdlibJar, $reflectJar, $coroutinesJar, $annotationsJar) -join ';'
$testClasspath = @($stdlibJar, $annotationsJar, $junitJar, $hamcrestJar, $AndroidJar) -join ';'
$outputDirectory = Join-Path $repositoryRoot ('build\detection-tests\' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$javaExecutable = Join-Path $JavaHome 'bin\java.exe'
$mainPackage = Join-Path $repositoryRoot 'app\src\main\java\com\example\lightrhythm'
$testPackage = Join-Path $repositoryRoot 'app\src\test\java\com\example\lightrhythm\detection'
$sourceFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $mainPackage 'detection') -File -Filter '*.kt'
    Get-ChildItem -LiteralPath (Join-Path $mainPackage 'model') -File -Filter '*.kt'
    Get-ChildItem -LiteralPath $testPackage -File -Filter '*.kt'
) | Select-Object -ExpandProperty FullName
$sourceFiles += Join-Path $PSScriptRoot 'EvaluateDetection.kt'
$sourceFiles += Join-Path $PSScriptRoot 'InspectBroadColor.kt'
$toolTestPackage = Join-Path $PSScriptRoot 'tests'
$sourceFiles += Get-ChildItem -LiteralPath $toolTestPackage -File -Filter '*Test.kt' | Select-Object -ExpandProperty FullName
if ($SampleImage) {
    $sourceFiles += Join-Path $PSScriptRoot 'InspectDetectionImage.kt'
}
& $javaExecutable -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 11 -classpath $testClasspath -d $outputDirectory @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed' }
$testClasses = Get-ChildItem -LiteralPath $testPackage -File -Filter '*Test.kt' |
    ForEach-Object { 'com.example.lightrhythm.detection.' + $_.BaseName }
$testClasses += Get-ChildItem -LiteralPath $toolTestPackage -File -Filter '*Test.kt' |
    ForEach-Object { 'com.example.lightrhythm.detection.' + $_.BaseName }
& $javaExecutable -cp ($outputDirectory + ';' + $testClasspath) org.junit.runner.JUnitCore @testClasses
if ($LASTEXITCODE -ne 0) { throw 'JUnit tests failed' }
if ($SampleImage) {
    & $javaExecutable -cp ($outputDirectory + ';' + $testClasspath) com.example.lightrhythm.detection.InspectDetectionImageKt $SampleImage
    if ($LASTEXITCODE -ne 0) { throw 'Sample inspection failed' }
}
if ($DatasetManifest) {
    & $javaExecutable -cp ($outputDirectory + ';' + $testClasspath) com.example.lightrhythm.detection.EvaluateDetectionKt $DatasetManifest $ReportDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Dataset evaluation failed' }
}
Write-Output ('Test classes: ' + $outputDirectory)
