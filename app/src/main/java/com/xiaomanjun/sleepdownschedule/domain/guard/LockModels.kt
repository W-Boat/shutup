package com.xiaomanjun.sleepdownschedule.domain.guard

import kotlinx.serialization.Serializable

/**
 * 上课锁机配置。默认值保证旧版 GuardDocument JSON 仍可解码。
 *
 * @param graceSeconds 上课后允许「决定是否锁机」的时间窗（默认 120 秒）
 * @param monthlyExitQuota 每个自然月允许直接退出的次数（默认 3）
 * @param autoLockOnTimeout 时间窗内没有选择时是否自动锁机
 * @param lockOnManualProtect 手动「立即保护」是否同样进入锁机
 */
@Serializable
data class LockSettings(
    val enabled: Boolean = false,
    val graceSeconds: Int = 120,
    val monthlyExitQuota: Int = 3,
    val autoLockOnTimeout: Boolean = true,
    val lockOnManualProtect: Boolean = false,
    val allowedPackages: List<String> = emptyList(),
    val quotePack: String = "calm",
    val customQuote: String = ""
) {
    fun validate() {
        require(graceSeconds in 30..900) { "锁机决定时间应为 30—900 秒" }
        require(monthlyExitQuota in 1..10) { "每月退出次数应为 1—10 次" }
        require(allowedPackages.size <= 60) { "白名单应用过多" }
        require(customQuote.length <= 200) { "自定义句子过长" }
    }

    /** 归一化到默认值范围内的安全读法，供运行时使用。 */
    val safeGraceSeconds: Int get() = graceSeconds.coerceIn(30, 900)
    val safeQuota: Int get() = monthlyExitQuota.coerceIn(1, 10)
}

object LockQuotes {
    val packs: List<Pair<String, String>> = listOf(
        "calm" to "静心",
        "study" to "苦读",
        "feynman" to "费曼",
        "poem" to "古诗"
    )

    private val calm = listOf(
        "把注意力放回这一页，剩下的交给时间。",
        "现在只做一件事：把这一节课听完。",
        "安静不是压抑，是把力气留给重要的事。",
        "你已经在做了，慢一点也没关系。",
        "呼吸三次，然后继续。",
        "此刻的专注，就是明天的从容。"
    )
    private val study = listOf(
        "书山有路勤为径，学海无涯苦作舟。",
        "此刻多写的一行，未来少求人的一句。",
        "不怕慢，只怕站。",
        "今天多撑十分钟，考试少慌十分钟。",
        "你羡慕的成绩，都是别人熬出来的。"
    )
    private val feynman = listOf(
        "如果讲不清，就是还没真懂——试着给自己讲一遍。",
        "把不懂的地方圈出来，比抄十遍有用。",
        "先看懂一步，再看下一步。",
        "问自己：这一段的因果关系是什么？"
    )
    private val poem = listOf(
        "长风破浪会有时，直挂云帆济沧海。",
        "莫等闲，白了少年头，空悲切。",
        "纸上得来终觉浅，绝知此事要躬行。",
        "少年易老学难成，一寸光阴不可轻。"
    )

    fun quotes(pack: String): List<String> = when (pack) {
        "study" -> study
        "feynman" -> feynman
        "poem" -> poem
        else -> calm
    }

    fun quote(settings: LockSettings, seed: Long): String {
        if (settings.customQuote.isNotBlank()) return settings.customQuote.trim()
        val list = quotes(settings.quotePack)
        return list[((seed / 60_000L) % list.size).toInt().let { if (it < 0) 0 else it }]
    }
}
