package com.quarksave.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import com.quarksave.app.ui.LinkState
import com.quarksave.app.ui.QuarkSaveApp
import com.quarksave.app.ui.QuarkSaveTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            QuarkSaveTheme {
                Surface {
                    QuarkSaveApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 解析 Deep Link intent 的分享链接并存入 LinkState */
    private fun handleIntent(intent: android.content.Intent?) {
        val data = intent?.dataString ?: return
        val extracted = extractShareLink(data)
        if (extracted != null) {
            LinkState.put(extracted)
        }
    }

    /**
     * 从 intent data 中提取夸克分享链接。
     * 兼容网页「去客户端查看」真实跳转格式:
     *  qklink:// / uclink:// / qkcloudlink:// / quark:// 深链，
     *  url= 参数指向 www.myquark.cn H5 落地页，
     *  而分享码 pwd_id 藏在多层 URL 编码 JSON 里。
     *  最多解码5层，从「query":{"pwd_id":"xxx"}」提取分享码组装为分享链接。
     */
    private fun extractShareLink(data: String): String? {
        // 1) 直接 pan/drive.quark.cn 分享链接
        val direct = java.util.regex.Pattern.compile(
            "(https?://(pan|drive)\\.quark\\.cn/s/[^\\s\"'<>]*)"
        ).matcher(data)
        if (direct.find()) return direct.group(1)

        // 2) purl / link / url 参数值为 pan/drive.quark.cn 分享链接
        for (key in listOf("purl", "link", "shareUrl", "share_url")) {
            val m = java.util.regex.Pattern.compile(
                "(?:^|[?&])" + key + "=([^&]*)"
            ).matcher(data)
            if (m.find()) {
                val v = android.net.Uri.decode(m.group(1))
                val s = java.util.regex.Pattern.compile(
                    "(https?://(pan|drive)\\.quark\\.cn/s/[^\\s\"'<>]*)"
                ).matcher(v)
                if (s.find()) return s.group(1)
            }
        }

        // 3) 深链路径 /s/xxx
        val deep = java.util.regex.Pattern.compile(
            "(?:quark|qkcloudlink|qklink|uclink)://[^\\s\"'<>]*(/s/[A-Za-z0-9]+)"
        ).matcher(data)
        if (deep.find()) return "https://pan.quark.cn" + deep.group(1)

        // 4) 多层解码提取分享码: qklink://...url=https%3A...%22pwd_id%22%3A%22CODE%22...
        return extractShareCodeFromEncoded(data)
    }

    /**
     * 从(可能多层URL编码的)整个 data 中挖掘分享码。
     * 真实URL格式: qklink://...?url=https%3A%2F%2Fwww.myquark.cn%2F%3Fqk_params%3D%2522pwd_id%2522%253A%2522CODE%2522
     * 逐层Uri.decode, 最多5层, 直到出现裸 pwd_id 可匹配。
     * pwd_id 后跟随灵活分隔符（：":= 等），捕获12-32位十六进制分享码。
     */
    private fun extractShareCodeFromEncoded(data: String): String? {
        var cur = data
        val codeRegex = java.util.regex.Pattern.compile(
            "(?i)pwd_id[\"':=\\s]{0,15}([a-f0-9]{12,32})"
        )
        for (k in 0..5) {
            val m = codeRegex.matcher(cur)
            if (m.find()) {
                val code = m.group(1)
                if (code.length >= 12 && !code.contains("%")) {
                    return "https://pan.quark.cn/s/$code"
                }
            }
            // 再解码一层
            val next = try { android.net.Uri.decode(cur) } catch (_: Exception) { cur }
            if (next == cur) break
            cur = next
        }
        return null
    }
}