package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class InputParagraphTest {
    @Test fun anEmptyListMarkerDoesNotReserveInvisibleRows() {
        for (text in listOf("", " \n", "•", "· ", "• \n", "· \n\n")) {
            assertEquals(1, InputParagraph.displayRows(text, 3, 0))
        }
        for (text in listOf("• 正文\n", "· 正文", "• \n• ", "甲\n\n乙")) {
            assertEquals(3, InputParagraph.displayRows(text, 3, 2))
        }
    }
    @Test fun trailingListFillersDoNotCreateSpaceAboveTheActiveRow() {
        val text = "• 接待\n• \n"
        assertEquals(2, InputParagraph.displayRows(text, 3, 1))
        assertEquals(1, InputParagraph.displayRows(text, 3, 0))
        // A real soft break with its caret on the new line remains visible.
        assertEquals(3, InputParagraph.displayRows("• 接待\n• \n\n", 4, 2))
        assertEquals(2, InputParagraph.displayRows("· \n", 2, 1))
        assertEquals(5, InputParagraph.displayRows("正文", 12, 8, maximumRows = 5))
        assertEquals(9, InputParagraph.displayRows("正文", 12, 8, maximumRows = 9))
    }
    @Test fun centeredContextNeverNeedsBlankRowsAboveTheDocument() {
        for (total in 1..12) for (line in 0 until total) for (maximum in listOf(3, 5, 9)) {
            val rows = InputParagraph.displayRows("正文", total, line, maximum, showContext = true)
            val below = minOf(rows / 2, total - 1 - line)
            assertTrue(rows in 1..minOf(total, maximum))
            assertTrue("total=$total line=$line rows=$rows", rows <= line + 1 + below)
            // Pick the largest viewport that actually has rows to fill it.
            if (rows < minOf(total, maximum)) {
                val next = rows + 1
                assertTrue(next > line + 1 + minOf(next / 2, total - 1 - line))
            }
        }
        assertEquals(2, InputParagraph.displayRows("正文", 12, 0, 5, showContext = true))
        assertEquals(4, InputParagraph.displayRows("正文", 12, 1, 5, showContext = true))
        assertEquals(5, InputParagraph.displayRows("正文", 12, 2, 5, showContext = true))
    }
    @Test fun shortTextStaysWholeAndWrappedLongParagraphIsStillOneParagraph() {
        assertEquals(InputParagraph("", "短文\n下一行", ""), InputParagraph.select("短文\n下一行", 2, 2))
        val long = "段落".repeat(300)
        assertEquals(long, InputParagraph.select(long, 100, 100).text)
    }
    @Test fun paragraphEditsPreserveNeighboringTextAndUnicodeOffsets() {
        val above = "甲".repeat(500)
        val document = "前文\n$above\n你好😀\n后文\n范围之外"
        val offset = document.indexOf("你好")
        val paragraph = InputParagraph.select(document, offset + 4, offset + 4)
        assertEquals("$above\n你好😀\n后文", paragraph.text)
        assertEquals("前文\n".length, paragraph.offset)
        val changedScope = paragraph.text.replace("你好😀", "你好😀\n新段")
        val changed = paragraph.replace(changedScope)
        assertEquals(document.replace("你好😀", "你好😀\n新段"), changed)
        assertEquals(changedScope, paragraph.retained(changed)!!.text)
    }
    @Test fun emptyParagraphAndCrossParagraphSelectionHaveExactRanges() {
        val document = "1\n2\n3\n\n5\n6"
        assertEquals("3\n\n5", InputParagraph.select(document, 6, 6).text)
        val selected = InputParagraph.select(document, 2, 8)
        assertEquals(document, selected.text)
        assertEquals("修改", selected.replace("修改"))
    }
    @Test fun contextHasPreviousCurrentAndNextParagraphAndClipsAtDocumentEdges() {
        val document = "0\n1\n2\n3\n4\n5\n6"
        assertEquals("2\n3\n4", InputParagraph.select(document, 6, 6).text)
        assertEquals("0\n1", InputParagraph.select(document, 0, 0).text)
        assertEquals("5\n6", InputParagraph.select(document, document.length, document.length).text)
        assertEquals("1\n2\n3\n4\n5", InputParagraph.select(document, 4, 9).text)
        val trailingEmpty = document.dropLast(1)
        assertEquals("5\n", InputParagraph.select(trailingEmpty, trailingEmpty.length, trailingEmpty.length).text)
    }
}
