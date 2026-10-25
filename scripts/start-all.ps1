# ============================================================================
#  纽启新生 · 一键启动（后端 + 管理后台）
# ============================================================================
#  用法：
#      scripts\start-all.ps1                  # 全部启动，并自动打开浏览器
#      scripts\start-all.ps1 -SkipBuild       # 跳过 Maven 打包（jar 没变时用，快很多）
#      scripts\start-all.ps1 -SkipFrontend    # 只起后端（比如你只想调接口）
#      scripts\start-all.ps1 -NoBrowser       # 不自动开浏览器
#
#  停止：
#      scripts\stop-all.ps1
#
#  它做了什么：
#      1. 读取 config\local.env（如果存在）→ 设置微信 AppID/AppSecret 等环境变量
#      2. 检查 JDK 17 / MySQL / Redis
#      3. 调 start-local.ps1 打包并启动后端（8080）
#      4. 启动管理后台 dev server（5173，改代码即时生效）
#      5. 等两个端口都就绪，然后打开浏览器
#
#  ⚠️ 编码提醒：本文件必须保存为「带 BOM 的 UTF-8」。
#     Windows PowerShell 5.1 对 .ps1 默认按 ANSI(GBK) 解析，
#     无 BOM 的中文注释会被解码坏，甚至直接冲垮语法（见 docs/06 第 19 条）。
#
#  ℹ️ 关于「脚本跑完了但终端不返回」：
#     在窗口里直接敲 `.\scripts\start-all.ps1`，实测约 25 秒返回提示符，正常。
#
#     唯一会看起来"卡住"的用法是把它接到管道里，例如：
#         .\scripts\start-all.ps1 | Tee-Object out.txt
#     原因是 Windows 下子进程会继承父进程的管道句柄，只要后端/前端还活着，
#     那条管道就收不到 EOF。**功能不受影响** —— 服务早已正常启动，
#     直接浏览器打开 5173 即可。PowerShell 5.1 无法彻底避免
#     （.NET 创建进程时总会继承句柄），所以如实写在这里，而不是假装没问题。
# ============================================================================

param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$JdkHome = 'E:\springboot\tools\jdk-17',
    [string]$MavenHome = 'E:\springboot\tools\apache-maven-3.9.16',
    [string]$Profile = 'dev',
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [switch]$SkipBuild,
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

function Write-Step($text) { Write-Output "`n[$(Get-Date -Format 'HH:mm:ss')] $text" }
function Write-Ok($text)   { Write-Output "  [OK]   $text" }
function Write-Bad($text)  { Write-Output "  [FAIL] $text" }
function Write-Info($text) { Write-Output "         $text" }
function Write-Warn($text) { Write-Output "  [WARN] $text" }

Write-Output '================================================================'
Write-Output '  纽启新生 · 一键启动'
Write-Output '================================================================'

# ---------------------------------------------------------------------------
# 1. 读取本地环境变量文件
#
# 为什么用「文件」而不是直接写在脚本里：
#   微信 AppSecret 是敏感凭证，写进 .ps1 就等于写进了仓库。
#   放在 config\local.env 里，加上 .gitignore 排除，
#   才既方便又不至于哪天随手 push 出去。
#
# 为什么自己解析而不是 dot-source：
#   这些是 "KEY=VALUE" 形式的配置，不是 PowerShell 语法。
#   直接当 .ps1 执行会因为等号/引号处理差异出意外。
# ---------------------------------------------------------------------------
Write-Step '读取本地配置'
# 解析逻辑收敛在 load-env.ps1 里，这里用 dot-source 复用。
# 不做两份实现：start-local.ps1 也要用同一份（直接跑它时同样需要微信凭证），
# 两份实现迟早会不一致 —— 而那种不一致只会表现成"配了却不生效"。
$envLoader = Join-Path $PSScriptRoot 'load-env.ps1'
$envFile = Join-Path $ProjectRoot 'config\local.env'
if (Test-Path $envLoader) {
    . $envLoader
}
if (Test-Path $envFile) {
    Write-Ok "已加载 $EnvLoadedCount 个变量（$envFile）"
} else {
    Write-Warn '没有 config\local.env —— 使用默认配置'
    Write-Info '要启用「真实微信登录」，请：'
    Write-Info '    copy config\local.env.example config\local.env'
    Write-Info '    然后把 WX_APP_SECRET 填成你自己的'
}

# ---------------------------------------------------------------------------
# 2. 明确告知当前是「真实微信登录」还是「模拟登录」
#
# 这一步是刻意做的：这两种模式的**表现完全不同**，而界面上看不出来。
#   · 真实模式：每个微信号对应一个真实用户
#   · 模拟模式：所有登录都映射到同一个固定用户，看起来像"怎么老是这个人"
# 不提前说清楚，使用者会以为是数据错了。
# ---------------------------------------------------------------------------
Write-Step '小程序登录模式'
$wxId = $env:WX_APP_ID
$wxSecret = $env:WX_APP_SECRET
$secretLooksReal = $false
if ($wxSecret) {
    $wxSecret = $wxSecret.Trim()
    # 占位值（示例文件里那一串 x）不算已配置
    if ($wxSecret -and ($wxSecret -notmatch '^x+$') -and $wxSecret.Length -ge 16) {
        $secretLooksReal = $true
    }
}
if ($secretLooksReal) {
    Write-Ok '真实微信登录已启用'
    Write-Info "AppID = $wxId"
    Write-Info '每个微信号会对应一个真实用户（首次登录需要填昵称和头像）'
} else {
    Write-Warn '当前是【模拟登录】：所有登录都会是同一个用户'
    Write-Info '原因：WX_APP_SECRET 没配置或还是占位值 xxxx'
    Write-Info '表现：不管用哪个微信号登录，都会看到同一个账号（如「演示用户」）'
    Write-Info '要改成真实登录：编辑 config\local.env，填入真实的 AppSecret'
    Write-Info '获取方式：微信公众平台 → 开发 → 开发管理 → 开发设置 → AppSecret'
    Write-Info '（日志里会出现：[开发模式] 未配置微信参数，使用模拟 openid 登录）'
}

# ---------------------------------------------------------------------------
# 3. 启动后端
#
# 复用 start-local.ps1 而不是复制一遍它的逻辑：
# JDK 版本检查、MySQL/Redis 探测、旧实例清理、打包、等 Spring 就绪，
# 这些逻辑只该存在一份。复制出去的话，改了一处忘改另一处，
# 迟早出现"两个脚本行为不一致"的怪事。
# ---------------------------------------------------------------------------
$backendPid = $null
if ($SkipBackend) {
    Write-Step '跳过后端（-SkipBackend）'
} else {
    Write-Step '启动后端'
    $startLocal = Join-Path $PSScriptRoot 'start-local.ps1'
    if (-not (Test-Path $startLocal)) {
        Write-Bad "找不到 $startLocal"
        exit 1
    }

    # 用独立进程调用：start-local.ps1 内部会 exit 表达失败，
    # 直接 & 调用会把本脚本一起终止
    $psExe = if (Get-Command powershell -ErrorAction SilentlyContinue) { 'powershell.exe' } else { 'pwsh.exe' }
    $args = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $startLocal,
        '-ProjectRoot', $ProjectRoot,
        '-JdkHome', $JdkHome,
        '-MavenHome', $MavenHome,
        '-Profile', $Profile,
        '-Port', "$BackendPort"
    )
    if ($SkipBuild) { $args += '-SkipBuild' }

    & $psExe @args
    if ($LASTEXITCODE -ne 0) {
        Write-Bad "后端启动失败（退出码 $LASTEXITCODE），已中止"
        Write-Info '看 app.log 找原因，或先单独跑一次 scripts\start-local.ps1 看完整输出'
        exit 1
    }

    # 记录一下 PID，方便最后汇总时打印
    $listener = Get-NetTCPConnection -State Listen -LocalPort $BackendPort -ErrorAction SilentlyContinue
    if ($listener) { $backendPid = $listener[0].OwningProcess }
}

# ---------------------------------------------------------------------------
# 4. 启动管理后台 dev server
#
# 用 dev 而不是 build + 静态托管：
#   dev 模式改 .vue 文件保存即生效，调界面时不用反复构建。
#   要给验收/演示看"成品"时再 npm run build。
# ---------------------------------------------------------------------------
$frontendPid = $null
if ($SkipFrontend) {
    Write-Step '跳过管理后台（-SkipFrontend）'
} else {
    Write-Step '启动管理后台'
    $uiDir = Join-Path $ProjectRoot 'nqx-ui'
    if (-not (Test-Path $uiDir)) {
        Write-Bad "找不到目录 $uiDir"
        exit 1
    }
    if (-not (Test-Path (Join-Path $uiDir 'node_modules'))) {
        Write-Bad 'node_modules 不存在'
        Write-Info "先执行：cd $uiDir ; npm install"
        exit 1
    }

    $feLog = Join-Path $ProjectRoot 'admin-ui.log'
    $feErr = Join-Path $ProjectRoot 'admin-ui-err.log'
    Remove-Item $feLog, $feErr -Force -ErrorAction SilentlyContinue

    # 先清掉占用端口的旧进程，否则 vite 会自己换一个端口，
    # 而本脚本后面等的是固定端口 —— 结果是"明明起来了却连不上"
    $oldFe = Get-NetTCPConnection -State Listen -LocalPort $FrontendPort -ErrorAction SilentlyContinue
    if ($oldFe) {
        foreach ($l in $oldFe) {
            Stop-Process -Id $l.OwningProcess -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Seconds 2
        Write-Info "已停止占用端口 $FrontendPort 的旧进程"
    }

    $env:BROWSER = 'none'   # 别让 vite 自己再开一个浏览器

    # -----------------------------------------------------------------------
    # ⚠️ 为什么这里必须用 -RedirectStandardOutput / -RedirectStandardError
    #
    # 踩过两种写法，都会导致「脚本全部打印完了，但调用方收不到结束信号」：
    #
    #   写法一：-RedirectStandardOutput $log -NoNewWindow
    #           -NoNewWindow 让子进程共用当前控制台，句柄纠缠不清。
    #
    #   写法二：让 cmd 自己重定向（`/c npm run dev > log 2>&1`），不传 -Redirect*
    #           这样 cmd **仍然继承了 PowerShell 的 stdout 句柄**。
    #           虽然 `>` 把"输出"改写进了文件，但那个继承来的句柄
    #           一直开在 cmd 的句柄表里没关 —— 只要 npm/vite 还活着，
    #           上游管道就永远等不到 EOF。表现就是：脚本跑完了，终端不返回。
    #
    # 正确做法：-RedirectStandardOutput 到**文件**，且不复用控制台
    # （用 -WindowStyle Hidden 而不是 -NoNewWindow）。
    # 这样传下去的是文件句柄而不是管道：脚本一退出，上游立刻收到 EOF，
    # 而 vite 继续在后台安静地跑。
    # -----------------------------------------------------------------------
    $feProc = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList '/c', 'npm run dev' `
        -WorkingDirectory $uiDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $feLog -RedirectStandardError $feErr `
        -PassThru

    Write-Info "npm 进程 PID = $($feProc.Id)，日志：$feLog"

    # 等端口就绪（最多 60 秒）
    $feReady = $false
    for ($i = 1; $i -le 60; $i++) {
        Start-Sleep -Seconds 1
        $l = Get-NetTCPConnection -State Listen -LocalPort $FrontendPort -ErrorAction SilentlyContinue
        if ($l) {
            $feReady = $true
            $frontendPid = $l[0].OwningProcess
            break
        }
    }

    if ($feReady) {
        Write-Ok "管理后台已就绪（耗时约 $i 秒）"
    } else {
        Write-Bad '管理后台未能在 60 秒内就绪'
        Write-Info "查看日志：Get-Content '$feLog' -Encoding UTF8"
        Get-Content $feLog -ErrorAction SilentlyContinue | Select-Object -Last 15 | ForEach-Object { Write-Info $_ }
        Write-Info '（后端已经起来了，可以继续用；只有管理后台没起来）'
    }
}

# ---------------------------------------------------------------------------
# 5. 汇总
# ---------------------------------------------------------------------------
Write-Output ''
Write-Output '================================================================'
Write-Output '  启动完成'
Write-Output '================================================================'

if (-not $SkipFrontend) {
    Write-Output "  管理后台    http://127.0.0.1:$FrontendPort"
    Write-Output '  登录账号    admin / Admin@123       （首次登录请修改）'
}
if (-not $SkipBackend) {
    Write-Output ''
    Write-Output "  接口文档    http://127.0.0.1:$BackendPort/swagger-ui.html"
    Write-Output "  健康检查    http://127.0.0.1:$BackendPort/actuator/health"
}

Write-Output ''
if ($secretLooksReal) {
    Write-Output '  小程序登录  真实微信登录（每个微信号对应一个用户）'
} else {
    Write-Output '  小程序登录  模拟登录 —— 所有登录都是同一个用户'
}
Write-Output ''
Write-Output '  小程序调试  微信开发者工具 → 导入 nqx-miniprogram'
Write-Output '              详情 → 本地设置 → 勾选「不校验合法域名...」'
Write-Output ''
Write-Output '  停止全部    scripts\stop-all.ps1'
if (-not $SkipBackend)  { Write-Output "  后端日志    Get-Content '$(Join-Path $ProjectRoot 'app.log')' -Wait -Encoding UTF8" }
if (-not $SkipFrontend) { Write-Output "  前端日志    Get-Content '$(Join-Path $ProjectRoot 'admin-ui.log')' -Wait -Encoding UTF8" }
Write-Output ''

# 自动打开浏览器
if (-not $NoBrowser -and -not $SkipFrontend) {
    Start-Process "http://127.0.0.1:$FrontendPort/" | Out-Null
}
