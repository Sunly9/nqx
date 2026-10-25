<#
.SYNOPSIS
    备份 nqx 数据库（含结构 + 数据），并在完成前校验产物非空。

.DESCRIPTION
    【为什么要有这个脚本】

    项目里有一个「清空全部业务数据」的重置脚本，它会删除用户账号与其全部历史记录。
    曾经因为直接执行它而没有备份，导致用户的真实使用记录永久丢失。

    所以现在的规矩是：**任何"清空/重置"操作之前，先跑这个脚本。**

    备份不等于安全，**验证过能恢复的备份才算**。本脚本会：
      1. 用 mysqldump 导出（--single-transaction，不锁表、不影响线上）
      2. 校验产物大小与结尾的 "Dump completed" 标记
      3. 打印恢复命令

.PARAMETER OutDir
    备份输出目录，默认 <项目根>\backup

.PARAMETER KeepDays
    自动删除多少天前的旧备份，默认 30 天；传 0 表示不清理

.EXAMPLE
    .\scripts\backup-database.ps1
    .\scripts\backup-database.ps1 -KeepDays 90

.NOTES
    ⚠️ 本文件含中文，必须保存为「带 BOM 的 UTF-8」，
    否则 Windows PowerShell 5.1 会按 ANSI 解析导致语法错误。
    详见 docs/06-踩坑记录.md 第 19 条。
#>

param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$DbUser = 'root',
    [string]$DbPassword = '123456',
    [string]$DbName = 'nqx',
    [string]$MySqlDump = 'D:\mysql\install\bin\mysqldump.exe',
    [string]$OutDir,
    [int]$KeepDays = 30
)

$ErrorActionPreference = 'Stop'

if (-not $OutDir) {
    $OutDir = Join-Path $ProjectRoot 'backup'
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if (-not (Test-Path $MySqlDump)) {
    Write-Host "  ❌ 找不到 mysqldump：$MySqlDump" -ForegroundColor Red
    Write-Host "     请用 -MySqlDump 指定实际路径" -ForegroundColor Yellow
    exit 1
}

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$file = Join-Path $OutDir "$DbName`_$stamp.sql"

Write-Host ''
Write-Host '================================================================'
Write-Host '  备份数据库'
Write-Host '================================================================'
Write-Host "  数据库 : $DbName"
Write-Host "  输出   : $file"
Write-Host ''

Write-Host '  正在导出...' -NoNewline
# --single-transaction：InnoDB 下用一致性快照，不锁表，线上可跑
# --routines/--events：把存储过程与事件一起带走，否则恢复后可能缺东西
$args = @(
    "-u$DbUser", "-p$DbPassword",
    '--default-character-set=utf8mb4',
    '--single-transaction',
    '--quick',
    '--routines',
    '--events',
    '--triggers',
    '--set-gtid-purged=OFF',
    $DbName
)
$errFile = Join-Path $env:TEMP "nqx_dump_err_$(Get-Random).txt"

<#
  ⚠️ 调用外部原生命令时必须**临时把 $ErrorActionPreference 降成 Continue**。

  PowerShell 5.1 的规则：原生命令往 stderr 写东西时，只要
  $ErrorActionPreference = 'Stop'，就会被包装成 NativeCommandError 并**终止脚本**
  —— 哪怕那只是一句提示。

  mysqldump 每次都会往 stderr 写
  「Using a password on the command line interface can be insecure」，
  于是备份明明成功了，脚本却在这里崩掉。

  这个坑踩了两次：第一次用 `2>&1`；第二次以为改成 `2>` 重定向就没事 ——
  其实一样会终止，因为**问题的根源不是重定向符，而是 ErrorActionPreference**。

  正确写法：临时降级 → 执行 → 恢复 → **只看退出码判断成败**。
#>
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$output = & $MySqlDump @args 2> $errFile | Out-String
$dumpExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap

$errText = if (Test-Path $errFile) { Get-Content $errFile -Raw -ErrorAction SilentlyContinue } else { '' }
Remove-Item $errFile -Force -ErrorAction SilentlyContinue

if ($dumpExit -ne 0) {
    Write-Host ' 失败'
    Write-Host ''
    Write-Host ("  ❌ mysqldump 退出码 " + $dumpExit) -ForegroundColor Red
    if ($errText) { Write-Host ("     " + $errText.Trim()) -ForegroundColor Red }
    exit 1
}
[System.IO.File]::WriteAllText($file, $output, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ' 完成'

# ---------------------------------------------------------------------------
# 校验：备份"跑完了"和"能用"是两件事
# ---------------------------------------------------------------------------
$info = Get-Item $file
Write-Host ''
Write-Host '  校验：'

$ok = $true

if ($info.Length -lt 10KB) {
    Write-Host ("    ❌ 文件只有 " + $info.Length + " 字节，明显不完整") -ForegroundColor Red
    $ok = $false
} else {
    Write-Host ("    ✅ 大小 " + [math]::Round($info.Length / 1KB, 1) + " KB")
}

$tail = ($output -split "`n" | Select-Object -Last 5) -join "`n"
if ($tail -match 'Dump completed') {
    Write-Host '    ✅ 结尾有 "Dump completed" 标记（导出完整结束）'
} else {
    Write-Host '    ⚠️ 没看到 "Dump completed" 标记，可能导出中断，请人工确认' -ForegroundColor Yellow
}

# 关键表是否出现在导出里 —— 防止"导出了一个空库还以为成功了"
foreach ($t in @('app_user', 'biz_order', 'product', 'sys_user')) {
    if ($output -match "CREATE TABLE ``$t``") {
        Write-Host "    ✅ 含表 $t"
    } else {
        Write-Host "    ⚠️ 未找到表 $t 的建表语句" -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------
# 清理旧备份
# ---------------------------------------------------------------------------
if ($KeepDays -gt 0) {
    $cutoff = (Get-Date).AddDays(-$KeepDays)
    $old = Get-ChildItem $OutDir -Filter "$DbName`_*.sql" | Where-Object { $_.LastWriteTime -lt $cutoff }
    if ($old) {
        $old | ForEach-Object { Remove-Item $_.FullName -Force }
        Write-Host ("    已清理 " + $old.Count + " 个超过 " + $KeepDays + " 天的旧备份")
    }
}

Write-Host ''
Write-Host '================================================================'
if ($ok) {
    Write-Host '  ✅ 备份完成' -ForegroundColor Green
} else {
    Write-Host '  ❌ 备份可能不完整，请勿依赖它' -ForegroundColor Red
}
Write-Host '================================================================'
Write-Host ''
Write-Host '  恢复命令（⚠️ 会覆盖当前库，先确认再执行）：'
Write-Host ''
$mysqlExe = $MySqlDump -replace 'mysqldump\.exe$', 'mysql.exe'
Write-Host "    & '$mysqlExe' -u$DbUser -p$DbPassword --default-character-set=utf8mb4 $DbName < `"$file`""
Write-Host ''
Write-Host '  ⚠️ 备份不等于安全：**只有验证过能恢复的备份才算备份**。'
Write-Host '     建议每月实际恢复一次到临时库，确认能起来。'
Write-Host ''

if (-not $ok) { exit 1 }
