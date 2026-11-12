# ============================================================
# flash_all.ps1 —— 一键「编译 + 烧固件 + 烧文件系统（字库）」
# ============================================================
# 为什么需要这个脚本：
#   PlatformIO 的 `pio run -t upload` 只烧 bootloader / 分区表 / 应用固件，
#   不会把 data/ 目录写进 SPIFFS。中文字库 JiangLingCJK16.vlw 属于文件系统，
#   必须由 `pio run -t uploadfs` 单独烧录；只烧固件时屏幕会显示 FONT ERROR。
#   本脚本把两步串起来，避免"以为一起烧进去了"。
#
# 用法（在本工程任意目录下执行均可）：
#   powershell -ExecutionPolicy Bypass -File scripts\flash_all.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\flash_all.ps1 -Port COM3
#   powershell -ExecutionPolicy Bypass -File scripts\flash_all.ps1 -SkipFs   # 只烧固件
#   powershell -ExecutionPolicy Bypass -File scripts\flash_all.ps1 -DryRun   # 只打印命令
#
# 注意：以下任一情况都必须重烧文件系统（脚本默认就会烧）：
#   - 改了 board_build.partitions / board_upload.flash_size / board_build.filesystem
#   - 改了 data/ 里的字库或新增文件
#   - 换板子、擦过整片 Flash、或之前从没执行过 uploadfs
# ============================================================
param(
  [string]$Port = "",
  [switch]$SkipFs,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# 工程根目录 = 本脚本所在目录的上一级。
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

# 定位 pio：优先用 PlatformIO 自带 venv，其次 PATH 里的 pio。
$Pio = Join-Path $env:USERPROFILE ".platformio\penv\Scripts\pio.exe"
if (-not (Test-Path $Pio)) {
  $cmd = Get-Command pio -ErrorAction SilentlyContinue
  if ($cmd) { $Pio = $cmd.Source } else { throw "找不到 pio，请先安装 PlatformIO Core。" }
}

# 字库必须存在，且是真实文件（这里只做存在性与大小检查，不校验 VLW 内容）。
$Font = Join-Path $Root "data\JiangLingCJK16.vlw"
if (-not (Test-Path $Font)) {
  throw "缺少字库：$Font（用 scripts\GenerateChineseFont.java 生成，见文件头示例）"
}
$FontSize = (Get-Item $Font).Length
if ($FontSize -lt 100000) {
  Write-Warning "字库只有 $FontSize B，明显偏小，可能是生成失败；请核对 GenerateChineseFont.java 的输出。"
}

# 分区表来自 platformio.ini 的 board_build.partitions（8 MB 单应用分区）。
Write-Host "[flash_all] 工程目录 : $Root"
Write-Host "[flash_all] pio      : $Pio"
Write-Host ("[flash_all] 字库     : {0}（{1:N0} B ≈ {2:N2} MB）" -f $Font, $FontSize, ($FontSize / 1MB))
Write-Host "[flash_all] 分区参考 : partitions_8MB.csv 的 spiffs 位于 0x500000、大小 0x2F0000（2.94 MB）"

# 组装公共参数：显式端口优先，否则用 platformio.ini 的 upload_port。
$CommonArgs = @("run")
if ($Port -ne "") { $CommonArgs += @("--upload-port", $Port) }

function Invoke-Pio {
  param([string[]]$PioArgs)
  $line = "$Pio " + ($PioArgs -join " ")
  if ($DryRun) {
    Write-Host "[dry-run] $line"
    return
  }
  Write-Host "[flash_all] $line"
  & $Pio @PioArgs
  if ($LASTEXITCODE -ne 0) { throw "命令失败（退出码 $LASTEXITCODE）：$line" }
}

# 1) 编译并烧应用固件（不含文件系统）。
Invoke-Pio -PioArgs ($CommonArgs + @("-t", "upload"))

# 2) 烧文件系统：字库就是靠这一步进板子的。
if ($SkipFs) {
  Write-Warning "已指定 -SkipFs：没有烧文件系统。若屏幕显示 FONT ERROR，请去掉该参数重跑。"
} else {
  Invoke-Pio -PioArgs ($CommonArgs + @("-t", "uploadfs"))
}

if ($DryRun) {
  Write-Host "[dry-run] 结束，未执行任何烧录。"
  exit 0
}

# 3) 烧完后的自检提示（肉眼 + 串口）。
Write-Host ""
Write-Host "烧录完成。请确认："
Write-Host "  1) 串口出现 'TFT: SPIFFS + font ready' 与 '[BOOT] 2/6 TFT init OK'"
Write-Host "  2) 屏幕显示首页标题（不再出现 FONT ERROR）"
Write-Host "  3) 查看串口： $Pio device monitor -b 115200"
Write-Host ""
Write-Host "若仍提示 '/' 找不到字库：确认 platformio.ini 的 board_build.partitions / flash_size 与板子一致，然后重跑本脚本。"
