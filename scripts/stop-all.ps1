# ============================================================================
#  纽启新生 · 一键停止
# ============================================================================
#  用法：
#      scripts\stop-all.ps1
#
#  它按「端口」找进程，而不是靠 PID 文件：
#    · 启动脚本里的进程 PID 会随重启变化，存下来的 PID 文件经常是过期的；
#    · 而「谁占着 8080 / 5173」永远是当下的事实。
#    按端口停是幂等的 —— 没起来时再执行一次也不会报错。
#
#  ⚠️ 编码提醒：本文件必须保存为「带 BOM 的 UTF-8」（见 docs/06 第 19 条）。
# ============================================================================

param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

function Write-Ok($text)   { Write-Output "  [OK]   $text" }
function Write-Info($text) { Write-Output "         $text" }

Write-Output '================================================================'
Write-Output '  纽启新生 · 停止本地服务'
Write-Output '================================================================'
Write-Output ''

function Stop-ByPort($port, $name) {
    $listeners = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if (-not $listeners) {
        Write-Ok "$name（端口 $port）本来就未运行"
        return
    }
    # 同一个端口可能有多个监听条目（IPv4/IPv6），去重后逐个停
    $pids = $listeners | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($p in $pids) {
        # PID 4 是 Windows 的 System 进程，占着 80/443 之类的系统端口。
        # 绝不能被脚本误杀 —— 那会直接让系统异常。
        if ($p -eq 4 -or $p -eq 0) {
            Write-Info "端口 $port 由系统进程（PID $p）占用，跳过"
            continue
        }
        try {
            $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
            $pname = if ($proc) { $proc.ProcessName } else { '未知' }
            Stop-Process -Id $p -Force -ErrorAction Stop
            Write-Ok "$name 已停止（PID $p / $pname）"
        } catch {
            Write-Info "停止 PID $p 失败：$($_.Exception.Message)"
        }
    }
    Start-Sleep -Seconds 2
}

# 先停前端：它只依赖后端，反过来则不必
Stop-ByPort $FrontendPort '管理后台'
Stop-ByPort $BackendPort  '后端应用'

# 顺手把残留的 vite / node dev server 清一下。
# 有些情况下 vite 主进程被杀了但 esbuild 的子进程还挂着端口，靠端口找不到。
$viteProcs = Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -match 'vite' }
if ($viteProcs) {
    foreach ($vp in $viteProcs) {
        Stop-Process -Id $vp.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Ok "清理残留 vite 进程 PID $($vp.ProcessId)"
    }
}

Write-Output ''
Write-Output '  已全部停止。重新启动：scripts\start-all.ps1'
Write-Output ''
