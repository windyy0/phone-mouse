package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class SourceWindowTitleTest {
    @Test fun omitsMissingOrRepeatedApplicationTitle() {
        assertNull(sourceWindowTitle("ChatGPT", null))
        assertNull(sourceWindowTitle("ChatGPT", "  \n  "))
        assertNull(sourceWindowTitle("ChatGPT", "chatgpt"))
        assertNull(sourceWindowTitle("ChatGPT", "ChatGPT.exe"))
        assertNull(sourceWindowTitle("Notepad", "Notepad - Notepad"))
        assertNull(sourceWindowTitle("codex-switcher", "Codex Switcher"))
        assertEquals("会议安排", sourceWindowTitle("codex-switcher", "Codex Switcher — 会议安排"))
        assertEquals("会议安排", sourceWindowTitle("codex-switcher", "会议安排 - Codex Switcher"))
    }
    @Test fun removesAppPrefixOrSuffixWithoutChangingDocumentTitle() {
        assertEquals("会议安排", sourceWindowTitle("Notepad", "会议安排 - Notepad"))
        assertEquals("会议安排", sourceWindowTitle("Notepad", "NOTEPAD — 会议安排"))
        assertEquals("会议安排", sourceWindowTitle("Notepad.Exe", "会议安排 | notepad.exe"))
        assertEquals("ChatGPT API guide", sourceWindowTitle("ChatGPT", "ChatGPT API guide"))
        assertEquals("讨论 - 会议安排", sourceWindowTitle("Notepad", "讨论 - 会议安排"))
    }
    @Test fun foldsMultilineCaptionsIntoOneLineAndPreservesUnicode() {
        assertEquals("会议 📅 安排", sourceWindowTitle("editor", " 会议\n📅\t 安排 "))
        assertEquals("会议安排 - 记事本", sourceWindowTitle("notepad", "会议安排 - 记事本"))
    }
}
