package com.xiaomanjun.sleepdownschedule.feature.guard

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomanjun.sleepdownschedule.CourseScheduleTheme
import com.xiaomanjun.sleepdownschedule.data.guard.GuardStore
import com.xiaomanjun.sleepdownschedule.data.guard.LockStore
import com.xiaomanjun.sleepdownschedule.domain.guard.LockQuotes
import com.xiaomanjun.sleepdownschedule.domain.guard.LockSettings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 上课锁机页面：只用 Miuix 组件（Card / Text / Button / TextButton），保持极简与平静。
 *
 * 防退出是分层的：锁任务（可用时）→ 返回键拦截 → 离开界面后自动拉回 → 无障碍服务把非白名单
 * 应用切回本页。全部失效时仍可用「每月 3 次」的退出配额，保证紧急情况能出去。
 */
class GuardLockActivity : ComponentActivity() {
    private val lockStore by lazy { LockStore(this) }
    @Volatile private var launchingAllowed = false
    @Volatile private var exiting = false
    private var verifyingKiosk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@GuardLockActivity, "锁机中，先完成这一节", Toast.LENGTH_SHORT).show()
            }
        })
        setContent {
            CourseScheduleTheme {
                MiuixTheme {
                    GuardLockContent(
                        onOpenAllowed = { packageName -> openAllowedApp(packageName) },
                        onExit = { requestExit() },
                        onStay = { Toast.makeText(this, "很好，继续。", Toast.LENGTH_SHORT).show() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (verifyingKiosk || exiting) return
        verifyingKiosk = true
        lifecycleScope.launch {
            val key = intent.getStringExtra(GuardLockController.WindowKey).orEmpty()
            val session = lockStore.session()
            if (!com.xiaomanjun.sleepdownschedule.domain.guard.LockStateMachine.valid(session, key, System.currentTimeMillis()) || session.skipped) {
                finishLock(); return@launch
            }
            val manager = getSystemService(ActivityManager::class.java)
            val managed = getSystemService(DevicePolicyManager::class.java)?.isLockTaskPermitted(packageName) == true
            val expected = if (managed) ActivityManager.LOCK_TASK_MODE_LOCKED else ActivityManager.LOCK_TASK_MODE_PINNED
            val started = runCatching {
                if (manager?.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
            }.isSuccess
            var verified = false
            if (started) {
                repeat(40) {
                    if (manager?.lockTaskModeState == expected) verified = true
                    if (!verified) delay(250)
                }
            }
            if (verified && androidx.lifecycle.Lifecycle.State.RESUMED == lifecycle.currentState &&
                GuardLockController.entered(this@GuardLockActivity, key)) {
                verifyingKiosk = false
            } else {
                lockStore.transition { old ->
                    if (old.windowKey == key) old.copy(decided = false, pendingEntry = true) else old
                }
                Toast.makeText(this@GuardLockActivity, "${if (managed) "受管锁任务" else "屏幕固定"}未确认，仍在等待进入，可从通知重试", Toast.LENGTH_LONG).show()
                finishLock()
            }
        }
    }

    private fun openAllowedApp(packageName: String) {
        val mode = getSystemService(ActivityManager::class.java)?.lockTaskModeState
        val permitted = getSystemService(DevicePolicyManager::class.java)?.isLockTaskPermitted(packageName) == true
        if (mode == ActivityManager.LOCK_TASK_MODE_PINNED || (mode == ActivityManager.LOCK_TASK_MODE_LOCKED && !permitted)) {
            Toast.makeText(this, "普通屏幕固定不能切换应用；受管设备还需管理员授权该白名单应用", Toast.LENGTH_LONG).show()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchingAllowed = true
        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        // Returning to the lock page is enforced again once the allowed app is left.
        window.decorView.postDelayed({ launchingAllowed = false }, 1_500)
    }

    private fun requestExit() {
        lifecycleScope.launch {
        val document = GuardStore(this@GuardLockActivity).document.first()
        val quota = document.lock.safeQuota
        val session = lockStore.session()
        if (withContext(Dispatchers.IO) { lockStore.exit(session.windowKey, System.currentTimeMillis(), quota) }) {
            exiting = true
            runCatching { stopLockTask() }
            Toast.makeText(this@GuardLockActivity, "已使用一次退出机会，本月剩余 ${lockStore.exitsLeft(quota)} 次", Toast.LENGTH_LONG).show()
            finishAndRemoveTask()
            startActivity(Intent(this@GuardLockActivity, SnowLeopardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Toast.makeText(this@GuardLockActivity, "本月 $quota 次直接退出已用完，请继续这一节课", Toast.LENGTH_LONG).show()
        }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!launchingAllowed && !exiting && GuardLockController.isLocked(this)) bringBack()
    }

    override fun onStop() {
        super.onStop()
        if (!launchingAllowed && !exiting && GuardLockController.isLocked(this) && !isChangingConfigurations) {
            window.decorView.postDelayed({ if (!launchingAllowed && !exiting) bringBack() }, 400)
        }
    }

    internal fun finishLock() {
        exiting = true
        runCatching { stopLockTask() }
        finishAndRemoveTask()
    }

    private fun bringBack() {
        val manager = getSystemService(ActivityManager::class.java) ?: return
        manager.appTasks.firstOrNull()?.moveToFront()
    }

    companion object {
        fun intent(context: Context, key: String): Intent =
            Intent(context, GuardLockActivity::class.java)
                .setData(android.net.Uri.parse("shutup://lock/entry/${android.net.Uri.encode(key)}"))
                .putExtra(GuardLockController.WindowKey, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Composable
private fun GuardLockContent(onOpenAllowed: (String) -> Unit, onExit: () -> Unit, onStay: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lockStore = remember { LockStore(context) }
    val settings by produceState(LockSettings()) {
        GuardStore(context).document.collect { value = it.lock }
    }
    val allowed by produceState(emptyList<Pair<String, String>>(), settings.allowedPackages) {
        value = withContext(Dispatchers.IO) { allowedApps(context, settings.allowedPackages) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            val session = lockStore.session()
            val ended = session.endsAt in 1 until now
            // 结束保护、关闭锁机开关、到期或用掉退出机会后，页面必须自己收起来，
            // 否则用户会被一个不再属于任何会话的全屏页困住。
            if (!session.active || ended) {
                (context as? GuardLockActivity)?.finishLock()
                break
            }
            delay(1_000)
        }
    }
    val session = lockStore.session()
    val remaining = ((session.endsAt - now) / 1000L).coerceAtLeast(0)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MiuixText(
            text = session.courseName.ifBlank { "专注时段" },
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
        )
        MiuixText(
            text = "${if (session.decided) "已锁定" else "等待进入 · 请确认系统屏幕固定"} · 还剩 ${formatRemaining(remaining)}",
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Card(Modifier.fillMaxWidth().wrapContentHeight(), insideMargin = PaddingValues(20.dp)) {
            MiuixText(
                text = LockQuotes.quote(
                    settings = settings,
                    seed = now
                ),
                fontSize = 18.sp,
                lineHeight = 28.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            )
        }
        if (allowed.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                MiuixText("白名单应用", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    allowed.forEach { (pkg, label) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MiuixText(label, fontSize = 15.sp, modifier = Modifier.align(Alignment.CenterVertically))
                            TextButton(text = "打开", onClick = { onOpenAllowed(pkg) }, minHeight = 34.dp)
                        }
                    }
                }
            }
        }
        val quota = settings.safeQuota
        val left = lockStore.exitsLeft(quota)
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            MiuixText(
                text = "本月直接退出机会：$left / $quota",
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )
            Button(onClick = onExit, modifier = Modifier.fillMaxWidth(), minHeight = 46.dp) {
                MiuixText(if (left > 0) "使用一次退出机会" else "本月机会已用完", fontSize = 16.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(text = "继续学习", onClick = onStay)
            }
        }
        message?.let { MiuixText(it, fontSize = 13.sp, modifier = Modifier.fillMaxWidth()) }
    }
}

private fun formatRemaining(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

private fun allowedApps(context: Context, packages: List<String>): List<Pair<String, String>> = runCatching {
    packages.mapNotNull { pkg ->
        runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            pkg to context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }
}.getOrDefault(emptyList())
