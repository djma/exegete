package dev.margin.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookContextBuilderTest {
    @Test
    fun `visible page is the final context section`() {
        val context = BookContextBuilder.assemble(
            title = "A Book",
            author = "An Author",
            spoilerPercent = 42,
            recentText = "older book text",
            selectedText = "selected words",
            currentPageText = "words visible now"
        )

        assertTrue(context.indexOf("older book text") < context.indexOf("selected words"))
        assertTrue(context.indexOf("selected words") < context.indexOf("words visible now"))
        assertTrue(context.endsWith("words visible now"))
    }

    @Test
    fun `only earlier chapters use extracted book text`() {
        assertTrue(BookContextBuilder.isEarlierResource(3, 2))
        assertFalse(BookContextBuilder.isEarlierResource(3, 3))
        assertFalse(BookContextBuilder.isEarlierResource(3, 4))
    }

    @Test
    fun `going back excludes chapters that were previously available`() {
        assertTrue(BookContextBuilder.isEarlierResource(4, 3))
        assertFalse(BookContextBuilder.isEarlierResource(2, 3))
    }

    @Test
    fun `unknown chapter positions do not include extracted text`() {
        assertFalse(BookContextBuilder.isEarlierResource(-1, 0))
        assertFalse(BookContextBuilder.isEarlierResource(3, -1))
    }
}
