"""PlatformIO 构建前脚本：修补 bootloader_dio_40m.elf 以兼容 XMC Flash。

原因：XMC Flash（XMC25QHxxC）返回厂商 ID 0x20，但启动阶段 JEDEC ID 检查失败。
修补后让该启动检查返回成功，使板子可以继续启动。

Python 和 C++ 的区别：Python 不需要预先声明变量类型，缩进表示代码块。
本文件在电脑上由 PlatformIO/SCons 执行，不会被编译进 ESP32 固件。

注意：函数入口使用 a1，寄存器窗口偏移为 8。这里必须写入 a2（不能写 a10），
因为 retw.n 会恢复寄存器窗口：
  bootloader_flash_xmc_startup 的 a2 → retw.n → bootloader_init 的 a10
"""
import os, shutil

# Import 是 PlatformIO/SCons 构建系统提供的函数，用来取得当前构建环境。
Import("env")

# 从已安装的平台包中定位 ESP32-S3 Arduino 框架及其 bootloader。
FRAMEWORK_DIR = env.PioPlatform().get_package_dir("framework-arduinoespressif32-esp32s3-libs")
ELF_PATH = os.path.join(FRAMEWORK_DIR, "bin", "bootloader_dio_40m.elf")
BAK_PATH = ELF_PATH + ".bak"

# SCons 在构建前调用此函数；source/target/env 是构建钩子约定的参数。
def patch_bootloader(source, target, env):
    # 如果存在原始备份，先恢复；这样重复编译不会在已修补文件上重复打补丁。
    if os.path.exists(BAK_PATH):
        shutil.copy2(BAK_PATH, ELF_PATH)

    # bootloader_flash_xmc_startup 位于 .iram_loader.text 段。
    # 地址和偏移依赖当前框架版本；升级框架后要重新确认，不能盲目复用。
    xmc_vaddr = 0x403cbec0
    file_off = 0x3700 + (xmc_vaddr - 0x403cb700)

    # "rb" 表示以二进制方式读取；ELF 是机器文件，不是普通文本。
    data = bytearray(open(ELF_PATH, "rb").read())

    # 指令 movi.n a2, 0 + retw.n 对应文件字节 0c 02 1d f0。
    # 写入 a2 后经 retw.n 返回，调用方读取到 a10=0（成功）。
    # bytes(...) 创建机器码字节；切片赋值替换文件中对应位置的指令。
    patch = bytes([0x0c, 0x02, 0x1d, 0xf0])
    data[file_off + 3: file_off + 7] = patch

    # "wb" 表示以二进制方式写回修补后的 ELF。
    open(ELF_PATH, "wb").write(data)
    print("[patch_bootloader] XMC startup → movi.n a2,0 + retw.n (bootloader bypassed)")

# 注册构建前钩子：生成 ELF 固件之前先执行 patch_bootloader()。
env.AddPreAction("$BUILD_DIR/${framework.args.elf_image}", patch_bootloader)
