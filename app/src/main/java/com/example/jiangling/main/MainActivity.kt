package com.example.jiangling.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.ui.JiangLingTheme

/**
 * 唯一的 Activity（纯 Compose）。
 *
 * 传输层只剩 WiFi：UDP 8081 下行 + TCP 8080 回程，连接生命周期全在
 * [com.example.jiangling.net.WifiLink]，这里不申请任何蓝牙权限、也不碰蓝牙开关。
 * 麦克风权限在首页/操控页点语音开关时各自申请。
 */
class MainActivity : ComponentActivity() {

    /** 全局唯一的 ViewModel，Activity 重建时不会丢数据 */
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            JiangLingTheme {
                JiangLingApp(viewModel = viewModel)
            }
        }
    }
}
