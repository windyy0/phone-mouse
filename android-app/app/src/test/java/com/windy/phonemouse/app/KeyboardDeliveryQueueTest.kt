package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class KeyboardDeliveryQueueTest {
    @Test fun preservesTextBackspaceAndEnterOrderWhileWaitingForAcknowledgement() {
        val queue = KeyboardDeliveryQueue()
        val first = KeyboardOperation.Text("你好", false)
        val last = KeyboardOperation.Text("世界", false)
        assertTrue(queue.enqueue(listOf(first, KeyboardOperation.Key("backspace"), KeyboardOperation.Key("enter"), last)))
        assertEquals(first, queue.next())
        assertNull(queue.next())
        assertEquals(first, queue.acknowledge())
        assertEquals(KeyboardOperation.Key("backspace"), queue.next())
        assertEquals(KeyboardOperation.Key("enter"), queue.next())
        assertEquals(last, queue.next())
        assertNull(queue.next())
        assertEquals(last, queue.acknowledge())
        assertNull(queue.next())
    }

    @Test fun interruptionReturnsOnlyUnconfirmedTextAndNeverRestartsIt() {
        val queue = KeyboardDeliveryQueue()
        val delivered = KeyboardOperation.Text("已收到", false)
        val uncertain = KeyboardOperation.Text("未确认", false)
        val unsent = KeyboardOperation.Text("未发送", false)
        queue.enqueue(listOf(delivered, uncertain, unsent))
        queue.next()
        queue.acknowledge()
        queue.next()
        assertEquals(listOf(uncertain, unsent), queue.stop())
        assertNull(queue.next())
        assertNull(queue.acknowledge())
        val fresh = KeyboardOperation.Text("新输入", false)
        queue.enqueue(listOf(fresh))
        assertEquals(fresh, queue.next())
    }

    @Test fun boundedQueueRejectsTheWholeCommitRatherThanPartOfIt() {
        val queue = KeyboardDeliveryQueue(capacity = 2)
        val first = KeyboardOperation.Text("A", false)
        val second = KeyboardOperation.Text("B", false)
        queue.enqueue(listOf(first))
        queue.next()
        assertFalse(queue.enqueue(listOf(second, KeyboardOperation.Key("enter"))))
        assertEquals(listOf(first), queue.stop())
    }

    @Test fun draftRemainsBusyUntilEveryPartIsAcknowledged() {
        val queue = KeyboardDeliveryQueue()
        queue.enqueue(listOf(KeyboardOperation.Text("第一行\n", true), KeyboardOperation.Text("第二行", true)))
        assertTrue(queue.hasDraft)
        queue.next()
        queue.acknowledge()
        assertTrue(queue.hasDraft)
        queue.next()
        queue.acknowledge()
        assertFalse(queue.hasDraft)
    }

    @Test fun unicodeChunksKeepEmojiPairsNewlinesAndTabsIntact() {
        val value = "你好😀\n第二行\t😄尾"
        val parts = textChunks(value, maxCharacters = 3)
        assertEquals(value, parts.joinToString(""))
        assertEquals("你好😀", parts.first())
        parts.forEach {
            assertTrue(it.codePointCount(0, it.length) <= 3)
            assertFalse(Character.isLowSurrogate(it.first()))
            assertFalse(Character.isHighSurrogate(it.last()))
        }
    }

    @Test fun longMultilineDraftFitsExistingProtocolWithoutChangingItsContent() {
        val value = "第一行😀\n第二行\n".repeat(500)
        val parts = textChunks(value)
        assertEquals(value, parts.joinToString(""))
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.codePointCount(0, it.length) in 1..2000 })
        assertTrue(textChunks("").isEmpty())
    }

    @Test fun olderOrUnknownSettingsStartInDraftMode() {
        assertEquals(TextInputMode.Draft, TextInputMode.fromCode(null))
        assertEquals(TextInputMode.Draft, TextInputMode.fromCode("future"))
        assertEquals(TextInputMode.Realtime, TextInputMode.fromCode("realtime"))
    }
}
