Add-Type -AssemblyName System.IO.Compression.FileSystem

$cachePath = "C:\Users\Veldrine\.gradle\caches"
$files = Get-ChildItem -Path $cachePath -Recurse -File -Include "*.jar","*.aar"

foreach ($file in $files) {
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($file.FullName)
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -like "*/Message.class") {
                Write-Output "Found in $($file.FullName): $($entry.FullName)"
            }
        }
        $zip.Dispose()
    } catch {
        # ignore read errors
    }
}
