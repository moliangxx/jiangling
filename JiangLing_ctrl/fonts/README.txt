JiangLing CJK font
=================

data/JiangLingCJK16.vlw is a 16 px TFT_eSPI Smooth Font generated from
Noto Sans SC. It includes ASCII and the characters encodable in GB2312.
The generated file is about 1.9 MB and fits the 8 MB board's 2.94 MB SPIFFS partition.
The custom single-app partition table is partitions_8MB.csv; the standard 8 MB
OTA layout has too little SPIFFS space for this font.
The source Noto font is not included; see OFL.txt for attribution and license.

Regenerate on Windows with Java and Noto Sans SC installed:

  java scripts\GenerateChineseFont.java "C:\Windows\Fonts\Noto Sans SC (TrueType).otf" data\JiangLingCJK16.vlw

Use the project script to upload the firmware, partition table, and SPIFFS image
(especially after switching from the previous 16 MB partition map):

  powershell -ExecutionPolicy Bypass -File scripts\flash_all.ps1

The firmware loads /JiangLingCJK16.vlw from SPIFFS. Characters outside the
included character set are not available in this font.
