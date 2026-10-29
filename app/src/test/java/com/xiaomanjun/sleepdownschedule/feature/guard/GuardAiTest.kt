package com.xiaomanjun.sleepdownschedule.feature.guard

import org.junit.Assert.*
import org.junit.Test

class GuardAiTest {
    @Test fun clarificationCannotBeMistakenForRules() {
        val result = GuardAi.parse("""{"questions":["下午几点开始？"],"rules":[]}""")
        assertTrue(result.rules.isEmpty()); assertEquals(1, result.questions.size)
    }
    @Test fun unknownActionsAreRejected() {
        assertThrows(Exception::class.java) { GuardAi.parse("""{"shell":"set volume 100","rules":[]}""") }
    }
    @Test fun malformedOutputIsNotCommitted() {
        assertThrows(Exception::class.java) { GuardAi.parse("我已经帮你静音了") }
    }
    @Test fun emptyOutputIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { GuardAi.parse("""{"rules":[],"questions":[]}""") }
    }
}
