package dev.margin.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryTest {
    @Test
    fun `history keeps only the last twelve messages`() {
        val history = ChatHistory()

        repeat(8) { index ->
            history.recordExchange("question $index", "answer $index")
        }

        val turns = history.snapshot()
        assertEquals(12, turns.size)
        assertEquals("question 2", turns.first().text)
        assertEquals("answer 7", turns.last().text)
    }

    @Test
    fun `clear removes all history`() {
        val history = ChatHistory()
        history.recordExchange("question", "answer")

        history.clear()

        assertEquals(emptyList<ChatTurn>(), history.snapshot())
    }
}
