package dev.margin.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPageTextTest {
    @Test
    fun `preview shows the last three lines including the final word`() {
        val page = ChatPageText("one two three four", "one\ntwo\nthree\nfour")
        assertEquals("two\nthree\nfour", page.preview)
    }

    @Test
    fun `short pages show all available lines`() {
        assertEquals("one\ntwo", ChatPageText("one two", "one\ntwo\n").preview)
    }

    @Test
    fun `selection must be within the captured boundary`() {
        val page = ChatPageText("Earlier text. The final visible words.", "The final visible words.")
        assertTrue(page.contains("The final\nvisible words."))
        assertFalse(page.contains("The final visible words. Next page."))
        assertFalse(page.contains(" "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing page text cannot create chat context`() {
        ChatPageText("Earlier text.", "")
    }
}
