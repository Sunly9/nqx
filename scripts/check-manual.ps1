$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$mp = Join-Path $root 'nqx-miniprogram'
$docs = Join-Path $root 'docs\manual'

function Read-Text($p) { Get-Content -LiteralPath $p -Raw -Encoding UTF8 }

$appJson = Read-Text (Join-Path $mp 'app.json') | ConvertFrom-Json
$pages = @{}
foreach ($p in $appJson.pages) { $pages['/' + $p] = $true }

$out = New-Object System.Collections.Generic.List[string]
$out.Add('=== 1) pages referenced in manuals vs app.json ===')
foreach ($doc in Get-ChildItem $docs -Filter *.md) {
    $text = Read-Text $doc.FullName
    $found = [regex]::Matches($text, '/pages/[A-Za-z0-9_\-/]+')
    $seen = @{}
    foreach ($m in $found) {
        $u = $m.Value.TrimEnd('/')
        if ($seen.ContainsKey($u)) { continue }
        $seen[$u] = $true
        $ok = $pages.ContainsKey($u) -or $pages.ContainsKey($u + '/index')
        $tag = if ($ok) { 'OK  ' } else { 'MISS' }
        $out.Add("$tag $u  [$($doc.Name)]")
    }
}

$checks = Read-Text (Join-Path $root 'scripts\manual-checks.json') | ConvertFrom-Json
$cache = @{}
function Get-Src($rel) {
    if (-not $cache.ContainsKey($rel)) { $cache[$rel] = Read-Text (Join-Path $mp $rel) }
    return $cache[$rel]
}

$out.Add('')
$out.Add('=== 2) per-file literal checks ===')
$fail = 0
$total = 0
foreach ($group in @('recycle', 'procurement')) {
    $out.Add("--- $group ---")
    $obj = $checks.$group
    foreach ($file in $obj.PSObject.Properties.Name) {
        $src = Get-Src $file
        foreach ($s in $obj.$file) {
            $total++
            if ($src.Contains($s)) {
                $out.Add("OK   [$file] $s")
            } else {
                $out.Add("MISS [$file] $s")
                $fail++
            }
        }
    }
}

$out.Add('')
$out.Add('=== 3) quoted UI strings not found anywhere in mini program source ===')
$allSrc = ''
foreach ($f in Get-ChildItem $mp -Recurse -Include *.wxml,*.js) { $allSrc += (Read-Text $f.FullName) + "`n" }

$lq = [char]0x300C
$rq = [char]0x300D
$pattern = "$lq([^$rq]{2,40})$rq"
foreach ($doc in Get-ChildItem $docs -Filter *.md) {
    $text = Read-Text $doc.FullName
    $quotes = [regex]::Matches($text, $pattern)
    $missing = New-Object System.Collections.Generic.List[string]
    $seen = @{}
    foreach ($q in $quotes) {
        $s = $q.Groups[1].Value
        if ($seen.ContainsKey($s)) { continue }
        $seen[$s] = $true
        if ($s.Length -lt 2) { continue }
        if (-not $allSrc.Contains($s)) { $missing.Add($s) }
    }
    $out.Add("--- $($doc.Name): $($missing.Count) not literally found ---")
    foreach ($m in $missing) { $out.Add("   ? $m") }
}

$out.Add('')
$out.Add("=== totals: checked=$total fail=$fail ===")
$out -join "`n"
