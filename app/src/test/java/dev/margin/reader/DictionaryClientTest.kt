package dev.margin.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryClientTest {
    @Test
    fun `accepts punctuation around one word`() {
        assertEquals("reader’s", singleSelectedWord("“reader’s”"))
    }

    @Test
    fun `rejects a phrase`() {
        assertNull(singleSelectedWord("two words"))
    }

    @Test
    fun `finds regular inflection candidates`() {
        assertTrue("read" in dictionaryWordCandidates("reading"))
        assertTrue("story" in dictionaryWordCandidates("stories"))
        assertTrue("run" in dictionaryWordCandidates("running"))
    }
}
