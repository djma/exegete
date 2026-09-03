package dev.margin.reader

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

/** Adds earlier chapters to the exact page captured when chat opens. */
@OptIn(ExperimentalReadiumApi::class)
object BookContextBuilder {
    suspend fun build(
        publication: Publication,
        current: Locator,
        selectedText: String?,
        pageText: ChatPageText,
        maxCharacters: Int = 1_300_000
    ): String {
        val currentResourceIndex = publication.readingOrder.indexOfFirst {
            it.href.toString().substringBefore('#') == current.href.toString().substringBefore('#')
        }
        require(currentResourceIndex >= 0) { "The current chapter could not be found." }
        val safeText = StringBuilder()
        val iterator = publication.content()?.iterator()

        if (iterator != null) {
            while (iterator.hasNext()) {
                val element = iterator.next()
                val resourceIndex = publication.readingOrder.indexOfFirst {
                    it.href.toString().substringBefore('#') == element.locator.href.toString().substringBefore('#')
                }
                // The captured DOM text supplies this chapter through the last visible word.
                if (!isEarlierResource(currentResourceIndex, resourceIndex)) break

                val text = (element as? Content.TextualElement)?.text
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: continue
                safeText.append(text).append("\n\n")
            }
        }

        safeText.append(pageText.resourceText)
        val recentText = safeText.takeLast(maxCharacters)

        return assemble(
            title = publication.metadata.title ?: "Unknown title",
            author = publication.metadata.authors.joinToString { it.name },
            spoilerPercent = (100 * (current.locations.totalProgression ?: 0.0)).toInt(),
            recentText = recentText,
            selectedText = selectedText?.takeIf { pageText.contains(it) },
            currentPageText = pageText.visibleText
        )
    }

    internal fun isEarlierResource(currentResourceIndex: Int, resourceIndex: Int): Boolean =
        resourceIndex >= 0 && resourceIndex < currentResourceIndex

    internal fun assemble(
        title: String,
        author: String,
        spoilerPercent: Int,
        recentText: CharSequence,
        selectedText: String?,
        currentPageText: String?
    ): String = buildString {
        appendLine("BOOK: $title")
        appendLine("AUTHOR: $author")
        appendLine("SOURCE FORMAT: EPUB")
        appendLine("SPOILER LIMIT: $spoilerPercent%")
        appendLine("MOST RECENT BOOK TEXT BEFORE THE SPOILER LIMIT:")
        appendLine(recentText)
        selectedText?.takeIf { it.isNotBlank() }?.let {
            appendLine("SELECTED PASSAGE:")
            appendLine(it)
        }
        // Keep the current page next to the reader's question and separate from older text.
        currentPageText?.takeIf { it.isNotBlank() }?.let {
            appendLine("TEXT CURRENTLY VISIBLE ON THE READER'S PAGE:")
            append(it)
        }
    }
}
