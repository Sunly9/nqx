# ============================================================================
#  纽启新生 · 只启动管理后台（前端 dev server）
# ============================================================================
#  用法：
#      scripts\start-frontend.ps1
#      或者双击项目根目录的「启动管理后台.bat」
#
#  特点：npm run dev 跑在**前台**，所以：
#      · 这个窗口就是 vite 的控制台，能看到编译输出和热更新日志；
#      · Ctrl+C 或直接关窗口 = 停止管理后台（所见即所得，不会有"关不掉"的困扰）；
#      · 想让它常驻后台，请用 start-all.ps1。
#
#  ⚠️ 编码提醒：本文件必须保存为「带 BOM 的 UTF-8」（见 docs/06 第 19 条）。
# ============================================================================

param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [int]$FrontendPort = 5173,
    [int]$BackendPort = 8080,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

function Write-Ok($text)   { Write-Output "  [OK]   $text" }
function Write-Bad($text)  { Write-Output "  [FAIL] $text" }
function Write-Info($text) { Write-Output "         $text" }
function Write-Warn($text) { Write-Output "  [WARN] $text" }

Write-Output '================================================================'
Write-Output '  纽启新生 · 管理后台'
Write-Output '================================================================'
Write-Output ''

# 顺带加载 config\local.env：本脚本不动后端，但把变量放进环境里没有副作用，
# 而且万一将来前端需要读某个配置（如上报地址）也能直接用上。
$envLoader = Join-Path $PSScriptRoot 'load-env.ps1'
if (Test-Path $envLoader) { . $envLoader }

$uiDir = Join-Path $ProjectRoot 'nqx-ui'
if (-not (Test-Path $uiDir)) {
    Write-Bad "找不到目录 $uiDir"
    exit 1
}

# ---------------------------------------------------------------------------
# 1. 检查依赖是否装过
#
# 没装 node_modules 时 npm run dev 会报一堆 "vite 不是内部或外部命令"，
# 看起来像环境坏了，实际只是忘了 npm install。提前说清楚能省掉一次排查。
# ---------------------------------------------------------------------------
if (-not (Test-Path (Join-Path $uiDir 'node_modules'))) {
    Write-Bad 'node_modules 不存在，依赖还没装'
    Write-Info '先执行一次（只需一次）：'
    Write-Info "    cd $uiDir"
    Write-Info '    npm install'
    exit 1
}
Write-Ok '依赖已安装'

# ---------------------------------------------------------------------------
# 2. 检查后端是否在跑
#
# 少了它页面能打开但登录会失败（axios 打到 5173，vite 代理到 8080 没人接），
# 表现为"输了密码一直转圈"或"网络错误"。这不是前端的问题，提前提醒。
# ---------------------------------------------------------------------------
$backendUp = Get-NetTCPConnection -State Listen -LocalPort $BackendPort -ErrorAction SilentlyContinue
if ($backendUp) {
    Write-Ok "后端已在运行（$BackendPort）"
} else {
    Write-Warn "后端没在运行（$BackendPort 无人监听）"
    Write-Info '页面能打开，但**登录会失败**。请先另开一个窗口跑：'
    Write-Info '    scripts\start-local.ps1     （只起后端）'
    Write-Info '    或「启动全部.bat」          （后端 + 管理后台）'
}

# ---------------------------------------------------------------------------
# 3. 清掉占用端口的旧进程
#
# 不清的话 vite 发现 5173 被占会自己换到 5174，
# 而浏览器/文档里写的是 5173 —— 结果就是"明明起来了却打不开"。
# ---------------------------------------------------------------------------
$old = Get-NetTCPConnection -State Listen -LocalPort $FrontendPort -ErrorAction SilentlyContinue
if ($old) {
    foreach ($l in $old) {
        Stop-Process -Id $l.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
    Write-Ok "已停止占用 $FrontendPort 的旧实例"
}

# ---------------------------------------------------------------------------
# 4. 前台运行 npm run dev
# ---------------------------------------------------------------------------
Write-Output ''
Write-Output '================================================================'
Write-Output "  管理后台地址：http://127.0.0.1:$FrontendPort"
Write-Output '  登录账号：admin / Admin@123'
Write-Output ''
Write-Output '  改动代码会自动热更新。'
Write-Output '  【停止】按 Ctrl+C，或直接关闭这个窗口。'
Write-Output '================================================================'
Write-Output ''

$env:BROWSER = 'none'   # 由我们自己决定要不要开浏览器，避免 vite 抢着开

if (-not $NoBrowser) {
    # 等一会儿再开浏览器：vite 需要 1~2 秒才监听端口，
    # 太早打开会看到"无法访问此网站"，用户会以为启动失败了。
    Start-Job -ScriptBlock {
        param($port)
        Start-Sleep -Seconds 3
        Start-Process "http://127.0.0.1:$port/"
    } -ArgumentList $FrontendPort | Out-Null
}

Set-Location $uiDir
# 前台执行：这一行会一直阻塞，直到用户 Ctrl+C 或关窗口
npm run dev

Write-Output ''
Write-Output '  管理后台已停止。'
