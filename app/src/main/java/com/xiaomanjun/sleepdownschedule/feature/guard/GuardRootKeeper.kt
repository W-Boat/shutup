package com.xiaomanjun.sleepdownschedule.feature.guard

import android.content.Context
import android.os.Process
import android.util.Base64
import java.io.File

/** No init/Magisk installation, downloaded script, general command input, or package-wide kill. */
internal object GuardRootKeeper {
    private fun flag(context: Context) = File(context.filesDir, "guard-root-enabled")
    private fun beat(context: Context) = File(context.filesDir, "guard-root-heartbeat")
    private fun runtime() = "/data/local/tmp/shutup-root-${Process.myUid()}"
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    @Synchronized fun heartbeat(context: Context) {
        val file = android.util.AtomicFile(beat(context))
        val output = file.startWrite()
        try {
            output.write((System.currentTimeMillis() / 1000).toString().toByteArray())
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }

    @Synchronized fun stopLocal(context: Context) {
        val enabled = flag(context)
        check(!enabled.exists() || enabled.delete()) { "未能移除 Root 守护开关文件" }
    }

    private fun ownProcessCheck(): String {
        val dir = quote(runtime())
        return "dir=$dir; pid=\$(cat \"\$dir/pid\" 2>/dev/null); " +
            "case \"\$pid\" in ''|*[!0-9]*) exit 1;; esac; " +
            "kill -0 \"\$pid\" 2>/dev/null && tr '\\000' ' ' < /proc/\$pid/cmdline | grep -F -- \"\$dir/watch.sh\" >/dev/null"
    }

    @Synchronized fun start(context: Context): String {
        check(GuardRootBridge.isAuthorized(context)) { "请先授予 Root" }
        heartbeat(context)
        flag(context).writeText("enabled")
        try {
            if (GuardRootBridge.run(ownProcessCheck())?.exitCode == 0) return "Root 守护进程已运行，仍需真机验证恢复能力"
            val script = context.assets.open("guard/root-watchdog.sh").bufferedReader().use { it.readText() }
                .replace("__ENABLED__", quote(flag(context).absolutePath))
                .replace("__HEARTBEAT__", quote(beat(context).absolutePath))
                .replace("__RUNTIME__", quote(runtime()))
                .replace("__PACKAGE__", quote(context.packageName))
                // Android's per-user UID range is 100000; getIdentifier is not a public SDK API.
                .replace("__USER__", (Process.myUid() / 100_000).toString())
                .replace("__SERVICE__", quote("${context.packageName}/${GuardService::class.java.name}"))
            val encoded = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
            val dir = quote(runtime())
            val result = GuardRootBridge.run("dir=$dir; umask 077; [ ! -L \"\$dir\" ] || exit 1; mkdir -p \"\$dir\" || exit 1; " +
                "[ \"\$(stat -c %u \"\$dir\")\" = 0 ] || exit 1; chmod 700 \"\$dir\"; " +
                "rmdir \"\$dir/running\" 2>/dev/null; " +
                "printf '%s' '$encoded' | base64 -d > \"\$dir/watch.sh\" || exit 1; " +
                "chmod 700 \"\$dir/watch.sh\"; nohup /system/bin/sh \"\$dir/watch.sh\" </dev/null >/dev/null 2>&1 &")
            check(result?.exitCode == 0) { "Root 守护启动命令失败" }
            Thread.sleep(300)
            check(GuardRootBridge.run(ownProcessCheck())?.exitCode == 0) { "未确认 Root 守护存活，系统可能限制后台 su" }
            return "Root 守护进程已运行；心跳中断后尝试恢复，仍需真机验证"
        } catch (e: Exception) {
            stopLocal(context)
            if (GuardRootBridge.exec("id -u")?.trim() != "0") GuardRootBridge.forget(context)
            throw e
        }
    }

    @Synchronized fun stop(context: Context) {
        stopLocal(context) // Still works after su is revoked; the loop checks this app-owned flag.
        if (GuardRootBridge.isAuthorized(context)) GuardRootBridge.run(
            ownProcessCheck() + " && kill \"\$pid\"")
    }

    fun status(context: Context): String {
        if (!flag(context).exists()) return "未运行"
        if (!GuardRootBridge.isAuthorized(context)) return "Root 未授权，守护状态无法确认"
        if (GuardRootBridge.run(ownProcessCheck())?.exitCode != 0) {
            if (GuardRootBridge.exec("id -u")?.trim() != "0") {
                GuardRootBridge.forget(context)
                return "su 检查失败，已撤销守护标记；请重新授权 Root"
            }
            return "守护未存活，请关闭后重新启用"
        }
        val state = GuardRootBridge.exec("cat ${quote(runtime() + "/status")}").orEmpty()
        return if (state.startsWith("restart:")) "守护存活，最近尝试恢复服务（$state）；不代表恢复成功" else "守护存活，正在检查服务心跳"
    }
}
