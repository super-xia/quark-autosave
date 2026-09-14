package com.quarksave.app.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * 跨界面共享的待填充链接缓存：
 * - Deep Link 打开时写入 (MainActivity)
 * - 扫码完成后写入 (ScanActivity)
 * - HomeScreen 观察并填入输入框
 *
 * 注意: incoming 暴露为 State, 供 Composable 用 by 委托观察变化以便重组。
 */
object LinkState {
    private val _incoming = mutableStateOf<String?>(null)

    /** 可观察状态: 由 Composable 用 by LinkState.incoming 读取, value 变化会触发重组 */
    val incoming: State<String?> = _incoming

    /** 读取并消费一个待填充链接（一次性） */
    fun consume(): String? {
        val v = _incoming.value
        _incoming.value = null
        return v
    }

    /** 存入一个待填充链接 */
    fun put(url: String) {
        _incoming.value = url
    }
}