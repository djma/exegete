package dev.margin.reader

/** Text captured from the page where the reader opens chat. */
data class ChatPageText(val resourceText: String, val visibleText: String) {
    init {
        require(resourceText.isNotBlank() && visibleText.isNotBlank())
    }

    val preview: String
        get() = visibleText.lineSequence().filter { it.isNotBlank() }.toList()
            .takeLast(3).joinToString("\n")

    fun contains(selection: String): Boolean =
        selection.isNotBlank() && resourceText.contains(selection.trim().replace(Regex("\\s+"), " "))
}
