# ============================================================================
#  加载 config\local.env 到进程环境变量
# ============================================================================
#  被 start-local.ps1 和 start-all.ps1 用 **dot-source** 方式引用：
#      . (Join-Path $PSScriptRoot 'load-env.ps1')
#
#  dot-source 的关键点：它在**调用者的作用域**里执行，
#  所以下面设置的 $env:XXX 对调用者可见。
#  如果用 `& load-env.ps1`（独立作用域），环境变量设完就丢了 ——
#  这是 PowerShell 里一个很容易踩的细节。
#
#  解析规则（刻意保持简单）：
#    · 空行、以 # 开头的行 → 跳过
#    · KEY=VALUE，第一个 = 之后的内容全是值（值里可以有 =）
#    · 值两端的成对引号会被去掉（允许 KEY="a b" 这种带空格的写法）
#    · 不做变量展开、不做转义 —— AppSecret 是纯十六进制串，用不上这些；
#      而且"少做一件事"意味着"少一处出错的可能"
#
#  加载完成后设置变量 $EnvLoadedCount，供调用者打印。
# ============================================================================

$EnvLoadedCount = 0

$envFilePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'config\local.env'
if (-not (Test-Path $envFilePath)) {
    # 让调用者自己决定怎么提示 —— 有的场景（如只起后端调试）缺这个文件完全正常
    return
}

Get-Content $envFilePath -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line) { return }
    if ($line.StartsWith('#')) { return }

    $idx = $line.IndexOf('=')
    if ($idx -lt 1) { return }

    $key = $line.Substring(0, $idx).Trim()
    $val = $line.Substring($idx + 1).Trim()

    # 去掉成对的包裹引号
    if ($val.Length -ge 2) {
        $first = $val.Substring(0, 1)
        $last = $val.Substring($val.Length - 1, 1)
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            $val = $val.Substring(1, $val.Length - 2)
        }
    }

    if (-not $key) { return }
    Set-Item -Path ("env:" + $key) -Value $val -ErrorAction SilentlyContinue
    $EnvLoadedCount = $EnvLoadedCount + 1
}
