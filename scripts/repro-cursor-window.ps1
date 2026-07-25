$ErrorActionPreference = "Stop"

$storePath = Join-Path $PSScriptRoot "..\app\src\main\java\com\netice\myapp\durumari\data\DurumariStore.kt"
$source = Get-Content -Raw $storePath
$getDocumentMatch = [regex]::Match(
    $source,
    '(?s)fun getDocument\(documentId: String, includeText: Boolean = true\).*?\n    \}'
)

if (-not $getDocumentMatch.Success) {
    throw "getDocument implementation was not found."
}

$implementation = $getDocumentMatch.Value
if ($implementation -match 'DOCUMENT_COLUMNS_WITH_TEXT') {
    Write-Error "FAIL: getDocument still requests the complete text column in one CursorWindow row."
}

if ($implementation -notmatch 'readDocumentTextInChunks') {
    Write-Error "FAIL: getDocument does not use bounded chunk reads for cached document text."
}

Write-Output "PASS: getDocument avoids a whole-text CursorWindow row and uses bounded chunk reads."
