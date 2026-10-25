# ============================================================================
#  纽启新生 · 本地一键启动
# ============================================================================
#  用法：
#      scripts\start-local.ps1
#      scripts\start-local.ps1 -SkipBuild        # 跳过 Maven 打包，直接启动
#      scripts\start-local.ps1 -Profile prod     # 用生产配置启动
#
#  前置条件（脚本会逐项检查并给出明确的修复提示）：
#      1. JDK 17（不是 18+）
#      2. MySQL 8 已启动，且已导入 sql/nqx-full-import.sql
#         （或者允许 Flyway 自动建表 —— 脚本会在库不存在时提示）
#      3. Redis 已启动
#
#  ⚠️ 编码提醒：本文件必须保存为「带 BOM 的 UTF-8」。
#     Windows PowerShell 5.1 对 .ps1 文件默认按 ANSI(GBK) 解析，
#     无 BOM 的中文注释会被解码坏，可能直接冲垮语法。
#     详见 docs/06-踩坑记录.md 第 19 条。
# ============================================================================

param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$JdkHome = 'E:\springboot\tools\jdk-17',
    [string]$MavenHome = 'E:\springboot\tools\apache-maven-3.9.16',
    [string]$Profile = 'dev',
    [int]$Port = 8080,
    [switch]$SkipBuild
)

# ---------------------------------------------------------------------------
# 工具链自动探测（2026-10-25 增补）
#
# 原先默认值写的是作者本机的绝对路径（E:\springboot\tools\...），换一台机器
# 必然在第一步就报「找不到 JDK」。这里改成：默认路径不存在时，按
#   JAVA_HOME → 常见安装目录 → PATH
# 的顺序找一个可用的 JDK 17；Maven 同理（PATH → IntelliJ 自带）。
# 显式传 -JdkHome / -MavenHome 且路径有效时，仍然优先使用传入值。
# ---------------------------------------------------------------------------
function Resolve-JdkHome([string]$current) {
    if ($current -and (Test-Path (Join-Path $current 'bin\java.exe'))) { return $current }
    $cand = @()
    if ($env:JAVA_HOME) { $cand += $env:JAVA_HOME }
    foreach ($base in @('C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft', 'D:\software')) {
        if (-not (Test-Path $base)) { continue }
        Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'jdk' } |
            ForEach-Object { $cand += $_.FullName }
    }
    foreach ($c in $cand) {
        if ($c -and (Test-Path (Join-Path $c 'bin\java.exe'))) { return $c }
    }
    return $current
}

function Resolve-MavenHome([string]$current) {
    if ($current -and (Test-Path (Join-Path $current 'bin\mvn.cmd'))) { return $current }
    $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($onPath) { return (Split-Path -Parent (Split-Path -Parent $onPath.Source)) }
    foreach ($base in @('D:\software', 'C:\Program Files', 'C:\Program Files\JetBrains')) {
        if (-not (Test-Path $base)) { continue }
        $hit = Get-ChildItem $base -Directory -Filter 'IntelliJ*' -ErrorAction SilentlyContinue |
               ForEach-Object { Get-ChildItem $_.FullName -Recurse -Filter 'mvn.cmd' -ErrorAction SilentlyContinue -Depth 7 | Select-Object -First 1 } |
               Select-Object -First 1
        if ($hit) { return (Split-Path -Parent (Split-Path -Parent $hit.FullName)) }
    }
    return $current
}

$JdkHome   = Resolve-JdkHome   $JdkHome
$MavenHome = Resolve-MavenHome $MavenHome
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

# ---------------------------------------------------------------------------
# 加载 config\local.env（微信 AppID/AppSecret 等）
#
# 用 dot-source（点 + 空格 + 路径）+ 前导点，在**当前作用域**执行，
# 这样 load-env.ps1 里设置的 $env:WX_APP_SECRET 才对下面的 java 进程有效。
# 若写成 `& load-env.ps1`（独立作用域），环境变量设完就没了，
# 表现是"明明配了 AppSecret，日志还是说模拟登录"。
# ---------------------------------------------------------------------------
$envLoader = Join-Path $PSScriptRoot 'load-env.ps1'
if (Test-Path $envLoader) {
    . $envLoader
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
$logFile = Join-Path $ProjectRoot 'app.log'
$errFile = Join-Path $ProjectRoot 'app-err.log'

function Write-Step($text) { Write-Output "`n[$(Get-Date -Format 'HH:mm:ss')] $text" }
function Write-Ok($text)   { Write-Output "  [OK]   $text" }
function Write-Bad($text)  { Write-Output "  [FAIL] $text" }
function Write-Info($text) { Write-Output "         $text" }

Write-Output '================================================================'
Write-Output '  纽启新生 · 本地启动'
Write-Output '================================================================'

# ---------------------------------------------------------------------------
# 1. 检查 JDK
#
# 明确要求 17：JDK 18 是非 LTS 且早已 EOL，JDK 21+ 与本项目的
# SpringBoot 3.5.x + MyBatis-Plus 组合未做验证。用错版本会出现
# 各种难以定位的问题（如 Lombok/MapStruct 注解处理器不兼容）。
# ---------------------------------------------------------------------------
Write-Step '检查 JDK'
$javaExe = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    Write-Bad "找不到 JDK：$javaExe"
    Write-Info '用 -JdkHome 参数指定你的 JDK 17 路径'
    exit 1
}
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"
$javaVersion = (& $javaExe -version 2>&1 | Select-Object -First 1)
Write-Ok $javaVersion
if ($javaVersion -notmatch '"17') {
    Write-Bad '本项目要求 JDK 17'
    exit 1
}

# ---------------------------------------------------------------------------
# 2. 检查 MySQL / Redis 是否可连
#
# 提前检查的价值：应用的启动失败信息通常是「Connection refused」，
# 而真正的原因可能是"MySQL 服务没启动"。在这里直接告诉用户，
# 能省掉一次翻日志。
# ---------------------------------------------------------------------------
Write-Step '检查 MySQL 与 Redis'
$mysqlOk = $false
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect('127.0.0.1', 3306)
    $mysqlOk = $tcp.Connected
    $tcp.Close()
} catch { }
if ($mysqlOk) { Write-Ok 'MySQL 3306 可连接' } else { Write-Bad 'MySQL 3306 连不上，请先启动 MySQL 服务' }

$redisOk = $false
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect('127.0.0.1', 6379)
    $redisOk = $tcp.Connected
    $tcp.Close()
} catch { }

# Redis 没起来时，先尝试拉起本机已有的 nqx-redis 容器。
# 目的：重启电脑后直接双击启动脚本也能工作，不必记得先手动 docker start。
if (-not $redisOk) {
    $dockerCmd = Get-Command docker.exe -ErrorAction SilentlyContinue
    if ($dockerCmd) {
        $hasContainer = (& $dockerCmd.Source ps -a --filter 'name=^nqx-redis$' --format '{{.Names}}' 2>$null)
        if ($hasContainer) {
            Write-Info 'Redis 未运行，尝试启动容器 nqx-redis ...'
            & $dockerCmd.Source start nqx-redis 2>&1 | Out-Null
            for ($i = 0; $i -lt 12; $i++) {
                Start-Sleep -Milliseconds 800
                try {
                    $t = New-Object System.Net.Sockets.TcpClient
                    $t.Connect('127.0.0.1', 6379)
                    if ($t.Connected) { $redisOk = $true; $t.Close(); break }
                    $t.Close()
                } catch { }
            }
        }
    }
}

if ($redisOk) { Write-Ok 'Redis 6379 可连接' } else { Write-Bad 'Redis 6379 连不上，请先启动 Redis 服务' }

if (-not $mysqlOk -or -not $redisOk) {
    Write-Output ''
    Write-Bad '依赖服务未就绪，已中止。修好上面两项后重新运行本脚本。'
    exit 1
}

# ---------------------------------------------------------------------------
# 3. 停掉已在运行的实例
#
# 两个原因：
#   1) 端口被占用时新实例会启动失败；
#   2) **Windows 上运行中的 jar 会被文件锁住，导致 mvn package 卡死** ——
#      这个坑真实踩过，表现为"Maven 构建卡住没有任何输出"。
# ---------------------------------------------------------------------------
Write-Step '清理旧实例'
$listeners = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($listeners) {
    foreach ($l in $listeners) {
        Write-Info "发现端口 $Port 被 PID=$($l.OwningProcess) 占用，正在停止"
        Stop-Process -Id $l.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 3
    Write-Ok '旧实例已停止'
} else {
    Write-Ok "端口 $Port 空闲"
}

# ---------------------------------------------------------------------------
# 4. Maven 打包
# ---------------------------------------------------------------------------
$jarPath = Join-Path $ProjectRoot 'nqx-web\target\nqx.jar'
if ($SkipBuild) {
    Write-Step '跳过打包（-SkipBuild）'
    if (-not (Test-Path $jarPath)) {
        Write-Bad "jar 不存在：$jarPath"
        Write-Info '请去掉 -SkipBuild 参数先构建一次'
        exit 1
    }
    Write-Ok '使用已有 jar'
} else {
    # 先确认 Maven 真的可用。找不到时，如果已经有打好的 jar 就直接用它启动，
    # 否则会抛一个跟"启动"毫无关系的 Maven 错误，让人以为是项目坏了。
    $mvn = $null
    if ($MavenHome) {
        $candidate = Join-Path $MavenHome 'bin\mvn.cmd'
        if (Test-Path $candidate) { $mvn = $candidate }
    }
    if (-not $mvn) {
        $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($onPath) { $mvn = $onPath.Source }
    }
    if (-not $mvn) {
        if (Test-Path $jarPath) {
            Write-Step '未找到 Maven：跳过打包，直接使用已有 jar'
            Write-Info '（改过后端代码的话，需要先装 Maven 或重新 package，否则跑的还是旧 jar）'
        } else {
            Write-Bad '未找到 Maven，且 nqx-web\target\nqx.jar 不存在'
            Write-Info '装好 Maven 后重试，或用 -MavenHome 指定安装目录'
            exit 1
        }
    }
    if ($mvn) {
        Write-Step 'Maven 打包（首次会下载依赖，可能较慢）'
        $buildOutput = & $mvn -B -ntp -f (Join-Path $ProjectRoot 'pom.xml') package -DskipTests 2>&1
        if ($buildOutput -match 'BUILD SUCCESS') {
            Write-Ok 'BUILD SUCCESS'
        } else {
            Write-Bad '构建失败，最后 20 行输出：'
            $buildOutput | Select-Object -Last 20 | ForEach-Object { Write-Info $_ }
            exit 1
        }
    }
}

# ---------------------------------------------------------------------------
# 5. 启动应用并等待就绪
# ---------------------------------------------------------------------------
Write-Step "启动应用（profile=$Profile）"
Remove-Item $logFile, $errFile -Force -ErrorAction SilentlyContinue

# ---------------------------------------------------------------------------
# 用 -WindowStyle Hidden 而不是 -NoNewWindow。
#
# 两者都能让后端"在后台跑"，但句柄行为不同：
#   · -NoNewWindow 让 java 共用当前控制台，会**继承调用方的 stdout 句柄**。
#     于是只要 java 还活着，调用方（尤其是一条管道）就永远等不到输出结束 ——
#     表现是"脚本把所有日志都打印完了，终端却不返回"。
#   · -WindowStyle Hidden + -RedirectStandardOutput 传下去的是**文件**句柄，
#     与调用方彻底解耦：脚本退出即可正常返回，而服务继续在后台跑。
#
# 这个坑在 start-all.ps1 里踩过一次，根因相同，所以两处一起修。
# ---------------------------------------------------------------------------
$proc = Start-Process -FilePath $javaExe `
    -ArgumentList '-Dfile.encoding=UTF-8', '-jar', $jarPath, "--spring.profiles.active=$Profile" `
    -WorkingDirectory $ProjectRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $logFile -RedirectStandardError $errFile `
    -PassThru

Write-Info "进程 PID = $($proc.Id)，日志：$logFile"

$ready = $false
for ($i = 1; $i -le 45; $i++) {
    Start-Sleep -Seconds 2
    if ($proc.HasExited) { break }
    if ((Get-Content $logFile -Raw -ErrorAction SilentlyContinue) -match 'Started NqxApplication') {
        $ready = $true
        break
    }
}

if (-not $ready) {
    Write-Bad '应用未能在 90 秒内启动'
    if ($proc.HasExited) { Write-Info "进程已退出，退出码 $($proc.ExitCode)" }
    Write-Info '日志中的错误：'
    Get-Content $logFile -ErrorAction SilentlyContinue |
        Select-String -Pattern 'Caused by|ERROR|Migration' |
        Select-Object -First 10 | ForEach-Object { Write-Info $_.Line }
    exit 1
}

$seconds = $i * 2
Write-Ok "应用已就绪（耗时约 $seconds 秒）"

# ---------------------------------------------------------------------------
# 6. 汇总
# ---------------------------------------------------------------------------
Write-Output ''
Write-Output '================================================================'
Write-Output '  启动完成'
Write-Output '================================================================'
Write-Output "  接口文档    http://localhost:$Port/swagger-ui.html"
Write-Output "  健康检查    http://localhost:$Port/actuator/health"
Write-Output ''
Write-Output '  管理员账号  admin / Admin@123       （首次登录请修改）'
# 按**实际**是否配了 AppSecret 来报，而不是按 profile 猜。
# 原来这里写的是"dev 就是 mock"—— 配了真实凭证之后那句话就成了误导，
# 使用者会以为还在模拟模式，从而不去验证真实登录。
$wxSecretSet = $false
if ($env:WX_APP_SECRET) {
    $s = $env:WX_APP_SECRET.Trim()
    if ($s -and ($s -notmatch '^x+$') -and $s.Length -ge 16) { $wxSecretSet = $true }
}
if ($wxSecretSet) {
    Write-Output '  小程序登录  真实微信登录（每个微信号对应一个用户）'
} else {
    Write-Output '  小程序登录  模拟登录 —— 所有登录都会是同一个用户'
    Write-Output '              要改成真实登录：填 config\local.env 里的 WX_APP_SECRET'
}
Write-Output ''
Write-Output "  停止应用    Stop-Process -Id $($proc.Id)"
Write-Output "  查看日志    Get-Content '$logFile' -Wait -Encoding UTF8"
Write-Output ''
$script:AppPid = $proc.Id
