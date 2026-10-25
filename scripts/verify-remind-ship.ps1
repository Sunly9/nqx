# ============================================================================
#  验证「提醒发货」整条链路
# ============================================================================
#  为什么要单独写成脚本文件：
#  这些命令内联在 pwsh -Command 里时，PowerShell 对 < > " ' 的解析规则
#  和 cmd.exe 的引号规则会互相干扰，出现"< 运算符是为将来使用而保留的"之类
#  莫名其妙的解析错误。写成文件（带 BOM）就没有这个问题。
#
#  直接用 & mysql.exe 调用，不套 cmd /c，参数按数组传递，引号问题彻底消失。
# ============================================================================

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$java = 'E:\springboot\tools\jdk-17\bin\java.exe'
$jar = Join-Path $root 'nqx-web\target\nqx.jar'
$log = Join-Path $root 'app.log'
$errLog = Join-Path $root 'app-err.log'

$mysqlArgs = @('-h127.0.0.1', '-P3306', '-uroot', '-p123456', 'nqx')

function Invoke-Sql([string]$sql) {
    & mysql @mysqlArgs -e $sql 2>$null
}
function Query-Sql([string]$sql) {
    (& mysql @mysqlArgs --raw -N -e $sql 2>$null) | Out-String
}

# ---- 启动应用 ----
$existing = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
if ($existing) { Stop-Process -Id $existing.OwningProcess -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 2 }
Remove-Item $log, $errLog -Force -ErrorAction SilentlyContinue

$env:JAVA_HOME = 'E:\springboot\tools\jdk-17'
$proc = Start-Process -FilePath $java `
    -ArgumentList '-Dfile.encoding=UTF-8', '-jar', $jar, '--spring.profiles.active=dev' `
    -WorkingDirectory $root -RedirectStandardOutput $log -RedirectStandardError $errLog `
    -PassThru -WindowStyle Hidden

$started = $false
for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep -Seconds 2
    if ($proc.HasExited) { break }
    if ((Get-Content $log -Raw -ErrorAction SilentlyContinue) -match 'Started NqxApplication') { $started = $true; break }
}
Write-Output ("应用启动: " + $(if ($started) { 'OK' } else { 'FAIL' }))
if (-not $started) {
    Get-Content $log -ErrorAction SilentlyContinue | Select-String -Pattern 'ERROR|Caused by' | Select-Object -First 5 | ForEach-Object { "  " + $_.Line }
    exit 1
}

try {
    # ---- 1. 造一条临时采购单 ----
    $uid = (Query-Sql 'SELECT id FROM app_user WHERE deleted=0 ORDER BY id LIMIT 1;').Trim()
    Write-Output ''
    Write-Output "========== 1. 造临时采购单（app_user id=$uid）=========="

    $insert = "INSERT INTO biz_order (order_no,user_id,order_type,status,total_quantity,total_amount,total_points,pay_time,contact_name,contact_phone,address_detail,create_time,update_time,deleted,version) " +
              "VALUES ('TEST_REMIND_001',$uid,2,41,1,480,0,NOW(),'test','13800000000','addr',NOW(),NOW(),0,0);"
    Invoke-Sql $insert | Out-Null

    $tid = (Query-Sql "SELECT id FROM biz_order WHERE order_no='TEST_REMIND_001';").Trim()
    Write-Output "  临时订单 id=$tid"
    if (-not $tid) { Write-Output '  ❌ 插入失败'; return }

    # ---- 2. 管理员登录 ----
    Write-Output ''
    Write-Output '========== 2. 管理员登录 =========='
    $resp = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/admin/auth/login' -Method Post `
        -ContentType 'application/json' -Body '{"username":"admin","password":"Admin@123"}'
    $token = $resp.data.token
    Write-Output ("  登录成功 token=" + $token.Substring(0, [Math]::Min(16, $token.Length)) + '...')
    $headers = @{ Authorization = "Bearer $token" }

    # ---- 3. 提醒前 ----
    Write-Output ''
    Write-Output '========== 3. 提醒前查后台（应为空）=========='
    $page1 = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/admin/order/page?pageNum=1&pageSize=10&orderType=2' -Headers $headers
    $row1 = $page1.data.records | Where-Object { $_.orderNo -eq 'TEST_REMIND_001' }
    Write-Output ("  订单查到: " + $(if ($row1) { 'OK' } else { 'FAIL' }))
    Write-Output ("  remindShipText: " + $(if ($row1.remindShipText) { '异常-> ' + $row1.remindShipText } else { '空（正确）' }))

    # ---- 4. 模拟买家提醒两次 ----
    Write-Output ''
    Write-Output '========== 4. 模拟买家提醒 2 次 =========='
    Invoke-Sql "UPDATE biz_order SET remind_ship_at=NOW(), remind_ship_count=2 WHERE id=$tid;" | Out-Null

    # ---- 5. 提醒后 ----
    Write-Output ''
    Write-Output '========== 5. 提醒后再查后台 =========='
    $page2 = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/admin/order/page?pageNum=1&pageSize=10&orderType=2' -Headers $headers
    $row2 = $page2.data.records | Where-Object { $_.orderNo -eq 'TEST_REMIND_001' }
    if ($row2 -and $row2.remindShipText) {
        Write-Output ("  remindShipText  = """ + $row2.remindShipText + """")
        Write-Output ("  remindShipCount = " + $row2.remindShipCount)
        Write-Output ("  remindShipAt    = " + $row2.remindShipAt)
        Write-Output '  ✅ 后台能看到买家催发货'
    } else {
        Write-Output '  ❌ 字段没出来'
    }

    # ---- 6. 验证只催 1 次的文案 ----
    Write-Output ''
    Write-Output '========== 6. 只催 1 次时的文案 =========='
    Invoke-Sql "UPDATE biz_order SET remind_ship_count=1 WHERE id=$tid;" | Out-Null
    $page3 = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/admin/order/page?pageNum=1&pageSize=10&orderType=2' -Headers $headers
    $row3 = $page3.data.records | Where-Object { $_.orderNo -eq 'TEST_REMIND_001' }
    Write-Output ("  " + $row3.remindShipText)
}
finally {
    # ---- 清理 ----
    Write-Output ''
    Write-Output '========== 清理临时数据 =========='
    Invoke-Sql "DELETE FROM biz_order WHERE order_no='TEST_REMIND_001';" | Out-Null
    $left = (Query-Sql "SELECT COUNT(*) FROM biz_order WHERE order_no='TEST_REMIND_001';").Trim()
    $total = (Query-Sql 'SELECT COUNT(*) FROM biz_order;').Trim()
    Write-Output "  临时订单残留: $left 条"
    Write-Output "  订单总数: $total （恢复原状）"
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
}
