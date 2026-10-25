param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

# ============================================================================
#  生成「云端一次性初始化」SQL
# ============================================================================
#  为什么需要这个脚本：
#
#  微信云托管的 MySQL 算力只有 0.25 CCU 起步。应用启动时 Flyway 要跑 16 个
#  迁移脚本，在那样的算力下要一两分钟，超过平台健康检查的容忍时间，
#  容器还没监听 8080 就被判失败重启 —— 表现为「日志停在 HikariPool」。
#
#  所以把建表从启动流程里挪出来，手工执行一次。
#  之后 Flyway 用 SPRING_FLYWAY_ENABLED=false 关掉，启动回到 11 秒。
#
#  【必须加 SET NAMES utf8mb4】
#
#  踩过的坑：mysql 客户端默认字符集是 latin1，会把 UTF-8 的中文注释按 latin1
#  解释。中文的 3 字节序列里有字节恰好落在 latin1 的引号/反引号区间，
#  于是「注释提前结束」，后面的说明文字被当成 SQL 解析，报：
#      ERROR 1064 ... near 'qx but no schema history table
#  加上 SET NAMES utf8mb4 之后，服务端按 utf8mb4 解释后续字节，问题消失。
# ============================================================================

$mig = Join-Path $ProjectRoot 'nqx-web\src\main\resources\db\migration'
$out = Join-Path $ProjectRoot 'nqx-init-cloud.sql'

$sb = New-Object System.Text.StringBuilder

function Add-Line([string]$text) { [void]$sb.AppendLine($text) }

# --- 头部：整段用 /* */ 包起来，避免逐行注释解析的边界问题 ---
Add-Line '/*'
Add-Line '============================================================================'
Add-Line ' 纽启新生 (NQX) — 云端数据库一次性初始化脚本'
Add-Line '----------------------------------------------------------------------------'
Add-Line ' 【用途】'
Add-Line '   微信云托管的 MySQL 算力只有 0.25 CCU 起步，应用启动时 Flyway 要跑 16 个'
Add-Line '   迁移脚本，耗时超过平台健康检查的容忍时间，容器还没监听 8080 就被判失败。'
Add-Line '   所以把建表这一步从启动流程里挪出来，在这里手工执行一次。'
Add-Line ''
Add-Line ' 【执行完成后必须做的事】'
Add-Line '   在云托管服务的环境变量里加上：'
Add-Line '       SPRING_FLYWAY_ENABLED = false'
Add-Line '   否则 Flyway 会发现「表存在但没有迁移历史表」而报错：'
Add-Line '       Found non-empty schema(s) but no schema history table'
Add-Line ''
Add-Line ' 【重要】'
Add-Line '   本脚本假定 nqx 库是空的。如果库里已有部分表，会报 table already exists。'
Add-Line '   遇到这种情况，请先在 Navicat 里清空 nqx 库再执行本脚本：'
Add-Line '       DROP DATABASE IF EXISTS nqx;'
Add-Line '       CREATE DATABASE nqx DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'
Add-Line ''
Add-Line ' 【内容来源】'
Add-Line '   按版本号顺序拼接 V1 ~ V16，内容与 Flyway 启动时执行的完全一致。'
Add-Line '============================================================================'
Add-Line '*/'
Add-Line ''
Add-Line '-- 声明字符集：不写这一行，中文注释会被按 latin1 解析而报语法错误'
Add-Line 'SET NAMES utf8mb4;'
Add-Line ''
Add-Line '-- 建表期间关闭外键检查，避免脚本顺序造成的临时性约束冲突'
Add-Line 'SET FOREIGN_KEY_CHECKS = 0;'
Add-Line ''
Add-Line 'USE `nqx`;'
Add-Line ''

$files = Get-ChildItem $mig -Filter '*.sql' |
    Sort-Object { [int]($_.Name -replace '^V(\d+)__.*$', '$1') }

foreach ($f in $files) {
    Add-Line '/* ------------------------------------------------------------------------'
    Add-Line "   $($f.Name)"
    Add-Line '   ------------------------------------------------------------------------ */'
    Add-Line ''
    Add-Line (Get-Content $f.FullName -Raw -Encoding UTF8)
    Add-Line ''
}

Add-Line 'SET FOREIGN_KEY_CHECKS = 1;'
Add-Line ''
Add-Line '/*'
Add-Line '----------------------------------------------------------------------------'
Add-Line ' 执行完成后，用下面这句确认表数量（期望 27 张业务表）：'
Add-Line ''
Add-Line '   SELECT COUNT(*) FROM information_schema.tables'
Add-Line '   WHERE table_schema = ''nqx'';'
Add-Line '----------------------------------------------------------------------------'
Add-Line '*/'

[System.IO.File]::WriteAllText($out, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

Write-Output ("已生成: " + $out)
Write-Output ("大小: " + [math]::Round((Get-Item $out).Length / 1KB, 1) + " KB")
Write-Output ("拼接脚本数: " + $files.Count)
Write-Output ("包含 SET NAMES utf8mb4: " + $(if ((Get-Content $out -Raw) -match 'SET NAMES utf8mb4') { '✅' } else { '❌' }))
