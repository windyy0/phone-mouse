package com.windy.phonemouse.app

enum class TextInputMode(val code: String, val label: String) {
    Realtime("realtime", "实时输入"),
    Draft("draft", "编辑后发送");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: Draft
    }
}

internal sealed interface KeyboardOperation {
    data class Text(val value: String, val draft: Boolean) : KeyboardOperation
    data class Key(val value: String) : KeyboardOperation
}

/** One acknowledged text at a time; keys keep their place between text commits. */
internal class KeyboardDeliveryQueue(private val capacity: Int = 64) {
    private val waiting = ArrayDeque<KeyboardOperation>()
    var active: KeyboardOperation.Text? = null
        private set
    val hasDraft get() = active?.draft == true || waiting.any { it is KeyboardOperation.Text && it.draft }
    val hasPending get() = active != null || waiting.isNotEmpty()

    fun enqueue(operations: List<KeyboardOperation>): Boolean {
        if (waiting.size + (if (active != null) 1 else 0) + operations.size > capacity) return false
        waiting.addAll(operations)
        return true
    }

    fun next(): KeyboardOperation? {
        if (active != null) return null
        return waiting.removeFirstOrNull()?.also { if (it is KeyboardOperation.Text) active = it }
    }

    fun acknowledge(): KeyboardOperation.Text? = active.also { active = null }

    fun stop(): List<KeyboardOperation> {
        val pending = listOfNotNull(active) + waiting.toList()
        active = null
        waiting.clear()
        return pending
    }
}

/** The protocol counts Unicode characters, rather than UTF-16 units. */
internal fun textChunks(value: String, maxCharacters: Int = 2000): List<String> {
    require(maxCharacters > 0)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < value.length) {
        val count = value.codePointCount(start, value.length).coerceAtMost(maxCharacters)
        val end = value.offsetByCodePoints(start, count)
        chunks += value.substring(start, end)
        start = end
    }
    return chunks
}
