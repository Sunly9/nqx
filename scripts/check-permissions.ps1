# ============================================================================
#  权限码一致性检查
# ============================================================================
#  背景：后端的 @SaCheckPermission("xxx") 是字符串字面量，数据库里的权限码
#        也是字符串。两处对不上时，编译不会报错、启动也不会报错 ——
#        只有在真正调用那个接口时才会返回 403。
#        而 403 又很容易被误认为"登录态过期"或"角色没配好"，
#        因此这类问题往往要排查很久。
#
#  本脚本把所有 Controller 里用到的权限码与数据库里的权限码做交叉比对，
#  几秒内就能定位不一致。开发期建议在提交前跑一次。
#
#  用法：
#    pwsh -File scripts/check-permissions.ps1
#    pwsh -File scripts/check-permissions.ps1 -MySqlExe 'D:\mysql\install\bin\mysql.exe'
# ============================================================================

param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$MySqlExe = 'D:\mysql\install\bin\mysql.exe',
    [string]$DbUser = 'root',
    [string]$DbPassword = '123456',
    [string]$DbName = 'nqx'
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$controllerDir = Join-Path $ProjectRoot 'nqx-web\src\main\java\com\nqx\controller'
if (-not (Test-Path $controllerDir)) {
    Write-Output "找不到 Controller 目录：$controllerDir"
    exit 1
}

# ---------- 1. 收集代码里用到的权限码 ----------
$codePerms = @()
Get-ChildItem $controllerDir -Recurse -Filter *.java | ForEach-Object {
    $file = $_
    Select-String -Path $file.FullName -Pattern 'SaCheckPermission\s*\(\s*value\s*=\s*"([^"]+)"' -AllMatches |
        ForEach-Object {
            foreach ($m in $_.Matches) {
                $codePerms += [PSCustomObject]@{
                    Perm = $m.Groups[1].Value
                    File = $file.Name
                }
            }
        }
}

Write-Output "代码中使用权限码：$($codePerms.Count) 处，去重后 $(($codePerms.Perm | Sort-Object -Unique).Count) 个"
Write-Output ''

# ---------- 2. 读取数据库权限码 ----------
$dbPerms = @()
if (Test-Path $MySqlExe) {
    $dbPerms = (& $MySqlExe "-u$DbUser" "-p$DbPassword" --batch --skip-column-names `
        --default-character-set=utf8mb4 `
        -e "SELECT perm_code FROM $DbName.sys_permission WHERE perm_code IS NOT NULL AND perm_code <> '';" 2>$null) |
        ForEach-Object { $_.Trim() } | Where-Object { $_ }
    Write-Output "数据库中的权限码：$($dbPerms.Count) 个"
} else {
    Write-Output "⚠️ 找不到 mysql 客户端（$MySqlExe），跳过数据库比对"
    exit 0
}
Write-Output ''

# ---------- 3. 比对 ----------
$unique = $codePerms.Perm | Sort-Object -Unique
$missing = $unique | Where-Object { $dbPerms -notcontains $_ }

Write-Output '================================================================'
if ($missing) {
    Write-Output "❌ 发现 $($missing.Count) 个权限码在数据库中不存在（调用将返回 403）："
    Write-Output '================================================================'
    foreach ($p in $missing) {
        Write-Output "  $p"
        $codePerms | Where-Object { $_.Perm -eq $p } | ForEach-Object { Write-Output "      ← $($_.File)" }
    }
    exit 1
} else {
    Write-Output '✅ 所有权限码都能在数据库中找到对应记录'
    Write-Output '================================================================'
}

# ---------- 4. 数据库里预留但代码未使用的（正常，属预告或未实现功能）----------
$unused = $dbPerms | Where-Object { $unique -notcontains $_ }
Write-Output ''
Write-Output "数据库中有但代码尚未使用的权限码：$($unused.Count) 个（属正常，多为预留/管理菜单权限）"
