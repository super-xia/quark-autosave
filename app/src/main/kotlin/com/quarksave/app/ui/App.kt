package com.quarksave.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.quarksave.app.QuarkApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS = "quark_save"
private const val KEY_COOKIE = "cookie"
private const val KEY_PATH = "savepath"

object Prefs {
    fun cookie(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_COOKIE, "") ?: ""
    fun savepath(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PATH, "") ?: ""
    fun setCookie(c: Context, v: String) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_COOKIE, v).apply()
    fun setPath(c: Context, v: String) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PATH, v).apply()
}

/** 从一坨文字里提取所有夸克分享链接，兼容用户复制的长文案（含口令/宣传语）。
 * 只收 URL 合法字符（字母数字 ?&=./#%_-），避免贪婪吞掉后面的中文标点/文字。 */
fun extractQuarkLinks(text: String): List<String> {
    val pat = java.util.regex.Pattern.compile("https?://(?:pan|drive)\\.quark\\.cn/s/[A-Za-z0-9]+[A-Za-z0-9?&=./#%_-]*")
    val m = pat.matcher(text)
    val out = mutableListOf<String>()
    while (m.find()) {
        val u = m.group().trimEnd('.', '，', '。', '！', '；', '、')
        if (u.isNotEmpty()) out.add(u)
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuarkSaveApp() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = {
            androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = androidx.compose.animation.core.tween(300)
            )
        },
        exitTransition = {
            androidx.compose.animation.slideOutHorizontally(
                targetOffsetX = { -it / 3 },
                animationSpec = androidx.compose.animation.core.tween(300)
            )
        },
        popEnterTransition = {
            androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = androidx.compose.animation.core.tween(300)
            )
        },
        popExitTransition = {
            androidx.compose.animation.slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = androidx.compose.animation.core.tween(300)
            )
        }
    ) {
        composable("home") {
            HomeScreen(onOpenSettings = { nav.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() }, onLogin = {})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var links by remember { mutableStateOf(TextFieldValue("")) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showProgress by remember { mutableStateOf(false) }
    val logText = remember { StringBuilder() }
    var log by remember { mutableStateOf("") }

    // 去重追加工具：按行去重（同样分享码视为重复）；粘贴的长文案会自动提取其中的所有夸克链接
    fun appendDedup(current: String, addition: String): Pair<String, Int> {
        val extracted = extractQuarkLinks(addition).ifEmpty { addition.split("\n").map { it.trim() }.filter { it.isNotEmpty() } }
        val curLines = current.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val curSet = curLines.toSet()
        // 用 pwd_id 去重：同一分享不同URL形式视为同一链接
        fun pid(s: String): String? = try { com.quarksave.app.QuarkApi.extractUrl(s)[0] } catch (_: Exception) { null }
        val curPids = curLines.mapNotNull { pid(it) }.toSet()
        val newLines = extracted.map { it.trim() }.filter { it.isNotEmpty() }.filterNot { line ->
            if (curSet.contains(line)) return@filterNot true
            val p = pid(line)
            p != null && curPids.contains(p)
        }.distinct()
        if (newLines.isEmpty()) return Pair(current, 0)
        val merged = (curLines + newLines).joinToString("\n")
        return Pair(merged, newLines.size)
    }

    // 消费来自 Deep Link / 扫码的待填充链接（监听状态变化，回到前台也生效）
    val incomingLinkState = LinkState.incoming
    LaunchedEffect(incomingLinkState.value) {
        val incoming = LinkState.consume()
        if (incoming != null && incoming.isNotBlank()) {
            val (merged, added) = appendDedup(links.text, incoming)
            if (added == 0) {
                android.widget.Toast.makeText(context, "链接已在列表中，已去重", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                links = TextFieldValue(merged)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("夸克自动转存", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { /* 扫码入口放在输入区 */ onOpenSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(8.dp))
            Text("粘贴分享链接", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "每行一个，转存到你的夸克网盘",
                fontSize = 12.sp,
                color = Color(0xFF8A93A0),
                modifier = Modifier.padding(top = 3.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("分享链接", fontSize = 11.sp, color = Color(0xFF8A93A0), modifier = Modifier.weight(1f))
                        // 从剪贴板粘贴（带去重）
                        TextButton(onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = cm.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val pasted = clip.getItemAt(0).coerceToText(context).toString().trim()
                                if (pasted.isNotEmpty()) {
                                    val (merged, added) = appendDedup(links.text, pasted)
                                    if (added == 0) {
                                        android.widget.Toast.makeText(context, "链接已在列表中，已去重", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        links = TextFieldValue(merged)
                                        android.widget.Toast.makeText(context, "已填入 $added 个新链接", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    android.widget.Toast.makeText(context, "剪贴板为空", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                android.widget.Toast.makeText(context, "剪贴板为空", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("粘贴", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        // 扫码按钮
                        TextButton(onClick = {
                            context.startActivity(android.content.Intent(context, com.quarksave.app.ScanActivity::class.java))
                        }) {
                            Text("扫码", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    OutlinedTextField(
                        value = links,
                        onValueChange = { links = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        placeholder = { Text("粘贴夸克网盘分享链接，每行一个", fontSize = 13.sp) },
                        minLines = 3, maxLines = 7,
                        shape = RoundedCornerShape(13.dp)
                    )
                    Text(
                        "在微信/浏览器长按复制分享链接，或扫二维码，点「粘贴」/「扫码」填入",
                        fontSize = 10.sp, color = Color(0xFF8A93A0), modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // 转存进度（默认隐藏，有任务才显示）
            if (showProgress) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("转存进度", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(6.dp)
                        )
                        Text(log, fontSize = 11.sp, color = Color(0xFF8A93A0),
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            Button(
                onClick = {
                    val cookie = Prefs.cookie(context)
                    if (cookie.isEmpty()) { android.widget.Toast.makeText(context, "请先在设置里登录夸克账号", android.widget.Toast.LENGTH_SHORT).show(); return@Button }
                    val savepath = Prefs.savepath(context)
                    val text = links.text.trim()
                    if (text.isEmpty()) { android.widget.Toast.makeText(context, "请粘贴分享链接", android.widget.Toast.LENGTH_SHORT).show(); return@Button }
                    startTransfer(context, text, savepath, running, { running = it }, { progress = it }, { showProgress = it }, logText, { log = logText.toString() }) { done, total ->
                        log = logText.toString()
                        // 不清空输入框：避免误删用户粘贴的长文案中的原文案；去重已在粘贴/提交时处理
                        if (done > 0) {
                            android.widget.Toast.makeText(context, "已转存 $done 个", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !running,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(48.dp)
            ) {
                Text(if (running) "转存中…" else "开始转存", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun startTransfer(
    context: Context,
    links: String,
    savepath: String,
    running: Boolean,
    setRunning: (Boolean) -> Unit,
    setProgress: (Float) -> Unit,
    setShowProgress: (Boolean) -> Unit,
    logText: StringBuilder,
    commitLog: () -> Unit,
    onComplete: (done: Int, total: Int) -> Unit = { _, _ -> }
) {
    setRunning(true); setShowProgress(true); setProgress(0f)
    logText.setLength(0)
    val cookie = Prefs.cookie(context)
    val api = QuarkApi(cookie)
    api.setLogListener { line ->
        logText.append(line); commitLog()
    }
    // 只提链接：从长文案中提取所有夸克链接，无链接时 fallback 原行
    val extractedForTransfer = extractQuarkLinks(links)
    val raw = if (extractedForTransfer.isNotEmpty()) extractedForTransfer else links.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    if (extractedForTransfer.isNotEmpty() && extractedForTransfer.size != links.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.size) {
        logText.append("已从长文案提取 ${extractedForTransfer.size} 个链接\n"); commitLog()
    }
    val seenPid = mutableSetOf<String>()
    val seenUrl = mutableSetOf<String>()
    val lines = mutableListOf<String>()
    var dupInInput = 0
    for (s in raw) {
        val pidRaw: String? = try { QuarkApi.extractUrl(s)[0] } catch (_: Exception) { null }
        val pid = pidRaw ?: s
        if (seenUrl.contains(s) || seenPid.contains(pid)) { dupInInput++; continue }
        seenUrl.add(s); seenPid.add(pid)
        lines.add(s)
    }
    if (dupInInput > 0) { logText.append("输入去重：跳过 $dupInInput 个重复链接\n"); commitLog() }
    val total = lines.size
    logText.append("开始转存，共 $total 个链接（${if (savepath.isEmpty()) "根目录" else savepath}）\n"); commitLog()
    // 在后台线程跑，UI 回调切回主线程
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    val t = Thread {
        var done = 0
        var processed = 0
        for ((i, link) in lines.withIndex()) {
            mainHandler.post { logText.append("\n【${i + 1}/$total】$link\n"); commitLog() }
            val ok = try { api.saveTask(link, savepath) } catch (e: Exception) { false }
            if (ok) done++
            processed++
            val p = processed.toFloat() / total
            mainHandler.post { setProgress(p) }
        }
        mainHandler.post { logText.append("\n全部完成：成功 $done / $total\n"); commitLog() }
        mainHandler.post {
            setRunning(false)
            onComplete(done, total)
        }
    }
    t.start()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogin: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var cookie by remember { mutableStateOf(Prefs.cookie(context)) }
    var path by remember { mutableStateOf(Prefs.savepath(context)) }
    var status by remember { mutableStateOf(if (Prefs.cookie(context).isEmpty()) "未登录" else "已登录") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // 夸克账号卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("夸克账号", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A93A0))
                    Text(status, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "Cookie（登录 pan.quark.cn 后 F12 复制）",
                        fontSize = 11.sp, color = Color(0xFF8A93A0), modifier = Modifier.padding(top = 12.dp)
                    )
                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        placeholder = { Text("粘贴 Cookie 覆盖当前账号…", fontSize = 12.sp) },
                        minLines = 2, maxLines = 4,
                        shape = RoundedCornerShape(13.dp)
                    )
                    Button(
                        onClick = {
                            Prefs.setCookie(context, cookie)
                            status = "验证中…"
                            val ck = cookie
                            Thread {
                                try {
                                    val api = QuarkApi(ck)
                                    val nick = api.accountNickname
                                    if (nick != null) {
                                        Prefs.setCookie(context, ck)
                                        status = "已登录：$nick"
                                    } else {
                                        status = "未登录"
                                    }
                                } catch (e: Exception) {
                                    status = "未登录"
                                }
                            }.start()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(44.dp)
                    ) {
                        Text("登录 / 切换账号", fontSize = 14.sp)
                    }
                }
            }

            // 转存目标卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("转存目标", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A93A0))
                    Text("目标文件夹", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        placeholder = { Text("/自动转存（留空=根目录）", fontSize = 14.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(13.dp)
                    )
                    Text(
                        "留空 = 存到网盘根目录；不存在的文件夹会自动创建",
                        fontSize = 11.sp, color = Color(0xFF8A93A0), modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // 保存设置
            Button(
                onClick = { Prefs.setPath(context, path); android.widget.Toast.makeText(context, "设置已保存", android.widget.Toast.LENGTH_SHORT).show(); onBack() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(48.dp)
            ) {
                Text("保存设置", fontSize = 15.sp)
            }
        }
    }
}