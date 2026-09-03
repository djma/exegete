package dev.margin.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DictionaryEntry(
    val word: String,
    val meanings: List<DictionaryMeaning>
) {
    fun displayText(): String = buildString {
        meanings.forEachIndexed { index, meaning ->
            if (index > 0) append("\n\n")
            append(meaning.partOfSpeech)
            meaning.definitions.forEachIndexed { definitionIndex, definition ->
                append("\n")
                append(definitionIndex + 1)
                append(". ")
                append(definition)
            }
        }
    }
}

internal data class DictionaryMeaning(
    val partOfSpeech: String,
    val definitions: List<String>
)

internal fun singleSelectedWord(selection: String): String? {
    val word = selection.trim().trim { character ->
        !character.isLetter() && character != '\'' && character != '’' && character != '-'
    }
    return word.takeIf {
        it.isNotEmpty() && it.matches(Regex("^[\\p{L}]+(?:['’-][\\p{L}]+)*$"))
    }
}

internal fun dictionaryWordCandidates(selectedWord: String): List<String> {
    val word = selectedWord.lowercase().replace('’', '\'').removeSuffix("'s")
    return buildSet {
        add(word)
        when {
            word.endsWith("ies") && word.length > 3 -> add(word.dropLast(3) + "y")
            word.endsWith("es") && word.length > 2 -> {
                add(word.dropLast(2))
                add(word.dropLast(1))
            }
            word.endsWith("s") && word.length > 1 -> add(word.dropLast(1))
        }
        if (word.endsWith("ied") && word.length > 3) add(word.dropLast(3) + "y")
        if (word.endsWith("ed") && word.length > 2) {
            add(word.dropLast(2))
            add(word.dropLast(1))
        }
        if (word.endsWith("ing") && word.length > 3) {
            val stem = word.dropLast(3)
            add(stem)
            add(stem + "e")
            if (stem.length > 2 && stem.last() == stem[stem.lastIndex - 1]) {
                add(stem.dropLast(1))
            }
        }
        if (word.endsWith("er") && word.length > 2) {
            add(word.dropLast(2))
            add(word.dropLast(1))
        }
        if (word.endsWith("est") && word.length > 3) {
            add(word.dropLast(3))
            add(word.dropLast(2))
        }
    }.toList()
}

internal class DictionaryClient(private val context: Context) {
    private var database: SQLiteDatabase? = null

    suspend fun define(selectedWord: String): DictionaryEntry = withContext(Dispatchers.IO) {
        val database = openDatabase()
        val candidates = dictionaryWordCandidates(selectedWord).toMutableList()
        candidates.toList().forEach { candidate ->
            database.rawQuery(
                "SELECT lemma FROM forms WHERE form = ? COLLATE NOCASE LIMIT 4",
                arrayOf(candidate)
            ).use { cursor ->
                while (cursor.moveToNext()) candidates.add(cursor.getString(0))
            }
        }

        val rows = candidates.distinct().firstNotNullOfOrNull { candidate ->
            lookup(database, candidate).takeIf { it.isNotEmpty() }
        } ?: error("No offline definition found.")

        DictionaryEntry(
            word = selectedWord,
            meanings = rows
                .groupBy({ it.first }, { it.second })
                .map { (partOfSpeech, definitions) ->
                    DictionaryMeaning(partOfSpeech, definitions.distinct().take(MAX_DEFINITIONS_PER_PART))
                }
                .take(MAX_PARTS_OF_SPEECH)
        )
    }

    private fun lookup(database: SQLiteDatabase, word: String): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        database.rawQuery(
            """
            SELECT s.part_of_speech, s.definition
            FROM words w
            JOIN synsets s ON s.id = w.synset_id
            WHERE w.word = ? COLLATE NOCASE
            ORDER BY CASE s.part_of_speech
                WHEN 'noun' THEN 1
                WHEN 'verb' THEN 2
                WHEN 'adjective' THEN 3
                ELSE 4
            END, s.id
            LIMIT 12
            """.trimIndent(),
            arrayOf(word)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += cursor.getString(0) to cursor.getString(1)
            }
        }
        return entries
    }

    @Synchronized
    private fun openDatabase(): SQLiteDatabase {
        database?.takeIf { it.isOpen }?.let { return it }
        val directory = File(context.filesDir, "dictionary").apply { mkdirs() }
        val file = File(directory, DATABASE_ASSET)
        if (!file.exists()) {
            val temporary = File(directory, "$DATABASE_ASSET.tmp")
            context.assets.open(DATABASE_ASSET).use { input ->
                temporary.outputStream().use(input::copyTo)
            }
            check(temporary.renameTo(file)) { "Could not install offline dictionary." }
        }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            .also { database = it }
    }

    companion object {
        private const val DATABASE_ASSET = "wordnet-3.0.sqlite"
        private const val MAX_PARTS_OF_SPEECH = 3
        private const val MAX_DEFINITIONS_PER_PART = 3
    }
}
