# ============================================================================
#  纽启新生 · 本地端到端检查
# ============================================================================
#  用法（要先启动服务）：
#      .\scripts\verify-local.ps1
#      或者双击项目根目录的「检查本地.bat」
#
#  【为什么需要这个脚本】
#
#  改完代码后，"能编译"和"能跑"是两件事。真实踩过的坑：
#    · vite.config 改了 /files 代理，但 dev server 没重启 → 图片全裂
#    · 加了新依赖，后端起来了但某个 Bean 初始化失败 → 接口 500
#    · 菜单图标名写错 → 编译通过、页面不报错，只是图标位置空白
#    · 上传改成 COS 后配置没配齐 → 启动直接失败
#
#  这些都**不会**在编译阶段暴露。所以每次改完代码，都该跑一遍这个脚本，
#  用真实 HTTP 请求把关键链路走一遍，而不是只看"BUILD SUCCESS"。
#
#  【它检查什么】
#      1. 后端 8080 在监听，且 /actuator/health = UP
#      2. 管理后台 5173 首页返回 HTML
#      3. 经 5173 代理能登录拿到 token（这条同时验证了 vite proxy）
#      4. 菜单数与图标数（图标名不存在会静默变空白，只能靠数量对不上发现）
#      5. /files 代理能取到图片（验相对路径图片的显示链路）
#      6. 上传接口返回的形态与当前存储模式一致（local → /files/，cos → https）
#      7. 小程序全部 JS 语法（node --check）
#
#  退出码：0 = 全部通过；1 = 有失败项。
# ============================================================================

param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [string]$AdminUser = 'admin',
    [string]$AdminPassword = 'Admin@123'
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$pass = 0
$fail = 0
$warn = 0

function Step($text) { Write-Output "`n[$(Get-Date -Format 'HH:mm:ss')] $text" }
function Ok($text)   { $script:pass++; Write-Output "  [PASS] $text" }
function Bad($text)  { $script:fail++; Write-Output "  [FAIL] $text" }
function Warn($text) { $script:warn++; Write-Output "  [WARN] $text" }
function Info($text) { Write-Output "         $text" }

function Test-Port($port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}

# 用 curl.exe 而不是 Invoke-RestMethod：后者在 PowerShell 5.1 里对 UTF-8 与
# 响应头的处理不一致，中文会乱码；curl 的行为稳定可预测
function CurlToFile($url, $outFile, $method = 'GET', $token = $null, $bodyFile = $null) {
    $args = @('-s', '-o', $outFile, '-w', '%{http_code}', '-X', $method, $url)
    if ($token) { $args += @('-H', "Authorization: Bearer $token") }
    if ($bodyFile) {
        $args += @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', "@$bodyFile")
    }
    return (& curl.exe @args 2>$null)
}

function ReadJson($path) {
    if (-not (Test-Path $path)) { return $null }
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

$tmp = Join-Path $env:TEMP ("nqx-verify-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

Write-Output '================================================================'
Write-Output '  纽启新生 · 本地端到端检查'
Write-Output '================================================================'
Write-Output "  后端  http://127.0.0.1:$BackendPort"
Write-Output "  后台  http://127.0.0.1:$FrontendPort"

# ---------------------------------------------------------------------------
# 1. 后端健康
# ---------------------------------------------------------------------------
Step '后端'
if (-not (Test-Port $BackendPort)) {
    Bad "端口 $BackendPort 无人监听 —— 后端没启动"
    Info '先跑「启动全部.bat」或「启动后端.bat」'
} else {
    $code = CurlToFile "http://127.0.0.1:$BackendPort/actuator/health" "$tmp\h.json"
    $body = ReadJson "$tmp\h.json"
    if ($code -eq '200' -and $body -match '"UP"') {
        Ok "健康检查 /actuator/health = UP"
    } else {
        Bad "健康检查异常：HTTP $code  $body"
    }

    # 存储模式：local 还是 cos，直接影响上传返回值的形态
    $logFile = Join-Path $ProjectRoot 'app.log'
    if (Test-Path $logFile) {
        $modeLine = Get-Content $logFile -Encoding UTF8 -ErrorAction SilentlyContinue |
            Select-String -Pattern '文件存储：' | Select-Object -Last 1
        if ($modeLine) {
            Info ("存储模式 → " + $modeLine.Line.Trim().Split(':')[-1].Trim())
        }
    }
}

# ---------------------------------------------------------------------------
# 2. 管理后台首页
# ---------------------------------------------------------------------------
Step '管理后台'
if (-not (Test-Port $FrontendPort)) {
    Bad "端口 $FrontendPort 无人监听 —— 管理后台没启动"
    Info '先跑「启动管理后台.bat」或「启动全部.bat」'
} else {
    $code = CurlToFile "http://127.0.0.1:$FrontendPort/" "$tmp\index.html"
    $html = ReadJson "$tmp\index.html"
    if ($code -eq '200' -and $html -match 'id="app"') {
        Ok '首页返回 HTML（含 #app 挂载点）'
    } else {
        Bad "首页异常：HTTP $code"
    }
}

# ---------------------------------------------------------------------------
# 3. 经代理登录（同时验证 vite proxy）
# ---------------------------------------------------------------------------
$token = $null
Step '登录（经 5173 代理，同时验证 proxy）'
if ((Test-Port $FrontendPort) -and (Test-Port $BackendPort)) {
    $bodyFile = Join-Path $tmp 'login.json'
    $loginBody = '{"username":"' + $AdminUser + '","password":"' + $AdminPassword + '"}'
    [System.IO.File]::WriteAllText($bodyFile, $loginBody, (New-Object System.Text.UTF8Encoding($false)))

    $code = CurlToFile "http://127.0.0.1:$FrontendPort/admin/auth/login" "$tmp\login-resp.json" 'POST' $null $bodyFile
    $resp = ReadJson "$tmp\login-resp.json"
    $token = ([regex]'"token":"([^"]+)"').Match($resp).Groups[1].Value

    if ($code -eq '200' -and $token) {
        Ok '登录成功，vite proxy 正常'
    } else {
        Bad "登录失败：HTTP $code  $resp"
        Info '检查：后端是否启动、账号密码是否正确、vite.config 的 /admin 代理'
    }
} else {
    Warn '后端或前端未启动，跳过登录检查'
}

# ---------------------------------------------------------------------------
# 4. 菜单与图标
#
# 这一项专门防"图标名不存在"：Vue 对不存在的组件名不报错，只渲染成空白，
# 所以只能靠「菜单数 == 有图标的数」来发现。
# ---------------------------------------------------------------------------
Step '菜单与图标'
if ($token) {
    $code = CurlToFile "http://127.0.0.1:$FrontendPort/admin/auth/menus" "$tmp\menus.json" 'GET' $token
    $menus = ReadJson "$tmp\menus.json"
    $names = [regex]::Matches($menus, '"name":"([^"]+)"')
    $icons = [regex]::Matches($menus, '"icon":"([^"]+)"')
    if ($names.Count -gt 0) {
        Ok "菜单 $($names.Count) 项，其中有图标的 $($icons.Count) 项"
        if ($icons.Count -lt $names.Count) {
            Warn "有 $($names.Count - $icons.Count) 个菜单没配图标（不算错，但风格不统一）"
            Info '跑 node scripts/audit-menu-icons.js 逐个核对图标名是否真实存在'
        }
    } else {
        Bad "菜单接口没返回数据：$menus"
    }
} else {
    Warn '没有 token，跳过菜单检查'
}

# ---------------------------------------------------------------------------
# 5. 图片链路（相对路径 → vite proxy → 后端 /files）
# ---------------------------------------------------------------------------
Step '图片链路'
if ((Test-Port $FrontendPort) -and (Test-Port $BackendPort)) {
    $imgCode = & curl.exe -s -o "$tmp\p.png" -w '%{http_code}|%{content_type}' "http://127.0.0.1:$FrontendPort/files/product/1.png" 2>$null
    if ($imgCode -match '^200\|image/') {
        Ok "经 5173 取 /files/product/1.png → $imgCode"
    } elseif ($imgCode -match '^200\|text/html') {
        Bad "取图片拿到的是 HTML（vite 的 SPA fallback）→ vite.config 的 /files 代理没生效或 dev server 没重启"
    } else {
        Bad "取图片失败：$imgCode"
        Info '检查 uploads/product/1.png 是否存在'
    }
} else {
    Warn '服务未全部启动，跳过图片链路检查'
}

# ---------------------------------------------------------------------------
# 6. 上传接口（按存储模式判断返回值形态）
# ---------------------------------------------------------------------------
Step '上传接口'
$sample = Join-Path $ProjectRoot 'uploads\product\1.png'
if (-not (Test-Path $sample)) {
    Warn "缺少样例文件 $sample，跳过上传检查"
} elseif (-not $token) {
    Warn '没有 token，跳过上传检查'
} else {
    $code = & curl.exe -s -o "$tmp\up.json" -w '%{http_code}' -X POST `
        -F "file=@$sample" -H "Authorization: Bearer $token" `
        "http://127.0.0.1:$BackendPort/admin/file/upload?bizType=product" 2>$null
    $up = ReadJson "$tmp\up.json"
    $returned = ([regex]'"data":"([^"]+)"').Match($up).Groups[1].Value

    if ($code -eq '200' -and $returned) {
        if ($returned -match '^/files/') {
            Ok "上传成功，返回相对路径 $returned（当前是 local 模式）"
        } elseif ($returned -match '^https?://') {
            Ok "上传成功，返回绝对地址 $returned（当前是 cos 模式）"
            # cos 模式必须验证这个地址真的能打开，否则"传上去了但打不开"
            $head = & curl.exe -s -o $null -w '%{http_code}' --max-time 10 $returned 2>$null
            if ($head -eq '200') {
                Ok 'COS 地址可访问'
            } else {
                Bad "COS 地址打不开：HTTP $head —— 检查桶权限/域名/是否配了防盗链"
            }
        } else {
            Warn "返回值形态未知：$returned"
        }
    } else {
        Bad "上传失败：HTTP $code  $up"
    }
}

# ---------------------------------------------------------------------------
# 7. 小程序 JS 语法
# ---------------------------------------------------------------------------
Step '小程序 JS 语法'
$mpDir = Join-Path $ProjectRoot 'nxq-miniprogram'
if (-not (Test-Path $mpDir)) { $mpDir = Join-Path $ProjectRoot 'nqx-miniprogram' }
if (Test-Path $mpDir) {
    $badFiles = @()
    $jsFiles = Get-ChildItem $mpDir -Recurse -Filter '*.js' -ErrorAction SilentlyContinue
    foreach ($f in $jsFiles) {
        & node --check $f.FullName 2>$null
        if ($LASTEXITCODE -ne 0) { $badFiles += $f.FullName.Replace($mpDir, '').TrimStart('\') }
    }
    if ($badFiles.Count -eq 0) {
        Ok "$($jsFiles.Count) 个 JS 文件语法全部正确"
    } else {
        Bad "$($badFiles.Count) 个文件语法错误：$($badFiles -join ', ')"
    }
} else {
    Warn '找不到小程序目录，跳过'
}

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

Write-Output ''
Write-Output '================================================================'
Write-Output '  结果'
Write-Output '================================================================'
Write-Output "  通过 $pass 项 / 失败 $fail 项 / 警告 $warn 项"
Write-Output ''

if ($fail -eq 0) {
    # 注意：`Write-Output 'a' + $b` 会被 PowerShell 当成**多个参数**，
    # 结果是分行输出成 "a" / "+" / "值" / "+" / "b" —— 一个很难一眼看出的排版 bug。
    # 用双引号插值最直观，也不用担心运算优先级。
    Write-Output "  ✅ 本地链路全部打通，可以打开 http://127.0.0.1:$FrontendPort 使用"
    Write-Output ''
    exit 0
} else {
    Write-Output '  ❌ 有失败项，按上面的 [FAIL] 提示逐条排查'
    Write-Output ''
    exit 1
}
