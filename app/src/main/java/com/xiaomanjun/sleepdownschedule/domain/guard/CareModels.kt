package com.xiaomanjun.sleepdownschedule.domain.guard

import kotlinx.serialization.Serializable

/**
 * AI 主动关怀通知。
 *
 * 课前 / 课中由 AI 根据课程名与用户自己写的「吐槽」生成一句短文案；没有配置 AI 时使用内置风格模板，
 * 保证离线也能收到提醒。
 */
@Serializable
data class CareSettings(
    val enabled: Boolean = false,
    val leadMinutes: Int = 10,
    val duringClass: Boolean = true,
    val duringClassEveryMinutes: Int = 25,
    /** cute / gentle / strict / meme */
    val style: String = "cute",
    val customPrompt: String = "",
    /** 只对指定课程生效；为空表示全部课程。 */
    val onlyCourseNames: List<String> = emptyList(),
    /** 每门课一句「吐槽」，AI 生成通知时会带上，让文案更像本人的口吻。 */
    val notes: Map<String, String> = emptyMap()
) {
    fun validate() {
        require(leadMinutes in 0..60) { "课前提醒应为 0—60 分钟" }
        require(duringClassEveryMinutes in 10..60) { "课中提醒间隔应为 10—60 分钟" }
        require(style in setOf("cute", "gentle", "strict", "meme")) { "未知的关怀风格" }
        require(customPrompt.length <= 300) { "自定义提示词过长" }
        require(onlyCourseNames.size <= 40) { "限定课程过多" }
        require(notes.size <= 40 && notes.values.all { it.length <= 120 }) { "课程吐槽过多或过长" }
    }

    val safeLeadMinutes: Int get() = leadMinutes.coerceIn(0, 60)
    val safeEveryMinutes: Int get() = duringClassEveryMinutes.coerceIn(10, 60)
}

object CareStyles {
    val labels: List<Pair<String, String>> = listOf(
        "cute" to "可爱",
        "gentle" to "温柔",
        "strict" to "严格",
        "meme" to "梗王"
    )

    fun label(style: String): String = labels.firstOrNull { it.first == style }?.second ?: "可爱"

    fun prompt(style: String, course: String, note: String, phase: String): String = buildString {
        append("你是学生手机里的学习搭子，正在")
        append(if (phase == "before") "提醒即将开始的课程" else "陪学生上这节课")
        append("。课程：").append(course.ifBlank { "自习" })
        if (note.isNotBlank()) append("；学生的吐槽：").append(note)
        append("。风格：").append(
            when (style) {
                "gentle" -> "温柔、简短、像朋友轻声说话"
                "strict" -> "严格但不凶，像负责任的学长"
                "meme" -> "有梗、俏皮、可以玩网络流行语但不油腻"
                else -> "可爱、软萌、带一个表情符号"
            }
        )
        append("。要求：一句话，不超过 40 个汉字，不要引号，不要解释，不要换行。")
    }

    /** 离线兜底文案，按风格与阶段变化，保证通知永不空白。 */
    fun fallback(style: String, course: String, phase: String, seed: Long): String {
        val name = course.ifBlank { "自习" }
        val before = listOf(
            "还有一会儿就上$name 啦，水杯装满了吗？",
            "$name 要开始了，深呼吸一下～",
            "收拾好桌面，$name 马上开始。",
            "马上$name，先把手机调成安静模式吧。"
        )
        val during = listOf(
            "$name 进行中，专注 25 分钟试试？",
            "这一节是$name，别让通知打断思路。",
            "$name 上到一半啦，撑住，你很棒。",
            "抬头听两句，$name 的重点常在中间。"
        )
        val pool = when (style) {
            "strict" -> if (phase == "before") {
                listOf("$name 即将开始，提前两分钟进入状态。", "课本、笔、笔记，$name 之前准备好。")
            } else {
                listOf("$name 正在进行，别切应用。", "现在的位置决定期末的分数，继续。")
            }
            "gentle" -> if (phase == "before") {
                listOf("$name 快开始了，慢慢来，你可以的。", "给自己一分钟安静，然后开始$name。")
            } else {
                listOf("$name 中，累了就坐直一点，继续。", "不用完美，只要在场，$name 也是。")
            }
            "meme" -> if (phase == "before") {
                listOf("$name 要来了，装死无效，准备上课！", "系统提示：$name 即将加载，请勿退出。")
            } else {
                listOf("$name 进行中，摸鱼进度条已暂停。", "$name 加载中……专注度 +1。")
            }
            else -> if (phase == "before") before else during
        }
        val index = ((seed / 60_000L) % pool.size).toInt().let { if (it < 0) 0 else it }
        return pool[index]
    }
}
