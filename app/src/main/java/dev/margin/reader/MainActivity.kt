package dev.margin.reader

import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.text.InputType
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Gravity
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.BaseActionModeCallback
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.toAbsoluteUrl

@OptIn(ExperimentalReadiumApi::class)
class MainActivity : AppCompatActivity(),
    EpubNavigatorFragment.Listener,
    EpubNavigatorFragment.PaginationListener {

    // Keep the legacy preference file name so upgrades preserve the reader's state.
    private val preferences by lazy { getSharedPreferences("margin", MODE_PRIVATE) }
    private val readium by lazy { (application as ExegeteApplication).readium }
    private val chatClient = ChatClient()
    private val dictionaryClient by lazy { DictionaryClient(this) }
    private val apiKeyStore by lazy { ApiKeyStore(this) }
    private val history = ChatHistory()
    private var chatJob: Job? = null
    private var chatRequestGeneration = 0L

    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var selectedText: String? = null
    private var chatPageSnapshot: ChatPageSnapshot? = null
    private var currentBookUri: Uri? = null
    private var currentFontScale = DEFAULT_FONT_SCALE
    private var currentLineHeight = DEFAULT_LINE_HEIGHT
    private var currentReaderFont = ReaderFont.SERIF
    private var currentNightMode = false
    private var pendingReflowAnchor: Locator? = null
    private var preservedReflowAnchor: Locator? = null
    private var lastStablePageAnchor: Locator? = null
    private var anchorCaptureGeneration = 0
    private var reflowRestoreRunnable: Runnable? = null
    private var reflowRestoreAttempt = 0
    private var refreshSavedAnchorOnStablePage = false
    private var responseTailSpace: View? = null
    private var bookPositions: List<Locator> = emptyList()
    private var updatingScrubber = false
    private var chatOpeningJob: Job? = null

    private val selectionActionModeCallback by lazy {
        object : BaseActionModeCallback() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_DEFINE, Menu.NONE, R.string.define)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != MENU_DEFINE) return false
                lifecycleScope.launch {
                    val selection = (navigator as? SelectableNavigator)
                        ?.currentSelection()
                        ?.locator
                        ?.text
                        ?.highlight
                    mode.finish()
                    val word = selection?.let(::singleSelectedWord)
                    if (word == null) {
                        showDictionaryMessage(getString(R.string.define), getString(R.string.select_one_word))
                    } else {
                        showDefinition(word)
                    }
                    (navigator as? SelectableNavigator)?.clearSelection()
                }
                return true
            }
        }
    }

    private lateinit var libraryScreen: View
    private lateinit var readerScreen: View
    private lateinit var chatPanel: View
    private lateinit var loadingPanel: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var bookTitle: TextView
    private lateinit var bookAuthor: TextView
    private lateinit var progressText: TextView
    private lateinit var pageScrubber: SeekBar
    private lateinit var bookmarkButton: TextView
    private lateinit var chaptersButton: TextView
    private lateinit var messages: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var questionInput: EditText
    private lateinit var sendButton: TextView
    private lateinit var clearChatButton: TextView
    private lateinit var chatContextPreview: TextView
    private lateinit var apiKeyButton: TextView
    private lateinit var modelStatus: TextView
    private lateinit var continueButton: TextView
    private lateinit var lastBookText: TextView
    private lateinit var swipeContainer: InstantSwipeFrameLayout

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers grant access without a persistable permission.
            }
            openBook(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.setWindowAnimations(0)
        bindViews()
        bindActions()
        // Remove the retired per-book Exy positions on upgrade.
        preferences.edit().apply {
            preferences.all.keys.filter { it.startsWith("exy-locator:") }.forEach { remove(it) }
        }.apply()
        addWelcomeMessage()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    chatPanel.visibility == View.VISIBLE -> hideChat()
                    readerScreen.visibility == View.VISIBLE -> showLibrary()
                    else -> finish()
                }
            }
        })

        val incomingUri = intent?.data
        val savedUri = preferences.getString(KEY_BOOK_URI, null)?.let(Uri::parse)
        (incomingUri ?: savedUri)?.let(::openBook)
    }

    private fun bindViews() {
        libraryScreen = findViewById(R.id.library_screen)
        readerScreen = findViewById(R.id.reader_screen)
        chatPanel = findViewById(R.id.chat_panel)
        loadingPanel = findViewById(R.id.loading_panel)
        topBar = findViewById(R.id.top_bar)
        bottomBar = findViewById(R.id.bottom_bar)
        bookTitle = findViewById(R.id.book_title)
        bookAuthor = findViewById(R.id.book_author)
        progressText = findViewById(R.id.progress_text)
        pageScrubber = findViewById(R.id.page_scrubber)
        bookmarkButton = findViewById(R.id.bookmark_button)
        chaptersButton = findViewById(R.id.chapters_button)
        messages = findViewById(R.id.messages)
        messageScroll = findViewById(R.id.message_scroll)
        questionInput = findViewById(R.id.question_input)
        sendButton = findViewById(R.id.send_button)
        clearChatButton = findViewById(R.id.clear_chat_button)
        chatContextPreview = findViewById(R.id.chat_context_preview)
        apiKeyButton = findViewById(R.id.api_key_button)
        modelStatus = findViewById(R.id.model_status)
        continueButton = findViewById(R.id.continue_button)
        lastBookText = findViewById(R.id.last_book_text)
        swipeContainer = findViewById(R.id.navigator_container)
        updateApiKeyStatus()
        updateLibraryState()
    }

    private fun bindActions() {
        findViewById<TextView>(R.id.open_book_button).setOnClickListener {
            openDocument.launch(arrayOf("application/epub+zip", "application/zip"))
        }
        apiKeyButton.setOnClickListener { showApiKeyDialog() }
        continueButton.setOnClickListener {
            preferences.getString(KEY_BOOK_URI, null)?.let(Uri::parse)?.let(::openBook)
        }
        findViewById<TextView>(R.id.back_button).setOnClickListener { showLibrary() }
        findViewById<TextView>(R.id.font_button).setOnClickListener { showReadingAppearanceDialog() }
        findViewById<TextView>(R.id.ask_button).setOnClickListener { showChat() }
        bookmarkButton.setOnClickListener { showBookmarksDialog() }
        chaptersButton.setOnClickListener { showChaptersDialog() }
        pageScrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) progressText.text = "${(progress / 10.0).toInt()}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (updatingScrubber || bookPositions.isEmpty()) return
                val index = ((seekBar.progress / seekBar.max.toDouble()) * (bookPositions.size - 1))
                    .roundToInt()
                    .coerceIn(bookPositions.indices)
                beginExplicitNavigation()
                navigator?.go(bookPositions[index], animated = false)
            }
        })
        findViewById<TextView>(R.id.close_chat_button).setOnClickListener { hideChat() }
        clearChatButton.setOnClickListener { clearChat() }
        sendButton.setOnClickListener { submitQuestion() }
        messageScroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) hideKeyboard()
            false
        }
        messages.setOnClickListener { hideKeyboard() }
        questionInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitQuestion()
                true
            } else {
                false
            }
        }
    }

    private fun openBook(uri: Uri) {
        chatOpeningJob?.cancel()
        cancelActiveChatRequest()
        chatPageSnapshot = null
        currentBookUri = uri
        showReaderLoading("Opening book…")
        lifecycleScope.launch {
            try {
                publication?.close()
                publication = null
                val absoluteUrl = uri.toAbsoluteUrl() ?: error("The selected file cannot be opened.")
                val asset = readium.assetRetriever.retrieve(absoluteUrl).getOrElse {
                    error(it.message)
                }
                val opened = readium.publicationOpener.open(
                    asset,
                    allowUserInteraction = true
                ).getOrElse {
                    error(it.message)
                }
                if (!opened.conformsTo(Publication.Profile.EPUB)) {
                    opened.close()
                    error("Exegete currently supports EPUB books only.")
                }

                publication = opened
                preferences.edit()
                    .putString(KEY_BOOK_URI, uri.toString())
                    .putString(KEY_LAST_TITLE, opened.metadata.title ?: "Untitled book")
                    .putString(KEY_LAST_AUTHOR, opened.metadata.authors.joinToString { it.name })
                    .apply()
                showPublication(opened)
            } catch (error: Throwable) {
                forgetSavedBookIfUnavailable(uri)
                currentBookUri = preferences.getString(KEY_BOOK_URI, null)?.let(Uri::parse)
                showLibrary(saveLocation = false)
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Could not open book")
                    .setMessage(error.message ?: "The EPUB could not be read.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showPublication(opened: Publication) {
        hideKeyboard()
        val title = opened.metadata.title ?: "Untitled book"
        val authors = opened.metadata.authors.joinToString { it.name }
        bookTitle.text = title
        bookAuthor.text = authors

        val savedLocator = preferences
            .getString(locatorKey(currentBookUri), null)
            ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        lastStablePageAnchor = savedLocator
        preservedReflowAnchor = savedLocator
        refreshSavedAnchorOnStablePage = savedLocator != null
        bookPositions = emptyList()
        currentFontScale = loadFontScale(currentBookUri)
        currentLineHeight = preferences.getFloat(lineHeightKey(currentBookUri), DEFAULT_LINE_HEIGHT.toFloat()).toDouble()
        currentReaderFont = ReaderFont.fromPreference(
            preferences.getString(fontFamilyKey(currentBookUri), null)
        )
        currentNightMode = preferences.getBoolean(nightModeKey(currentBookUri), false)

        val fragmentFactory = EpubNavigatorFactory(opened).createFragmentFactory(
            initialLocator = savedLocator,
            initialPreferences = EpubPreferences(
                backgroundColor = pageBackgroundColor(),
                textColor = pageTextColor(),
                fontSize = currentFontScale,
                fontFamily = currentReaderFont.family,
                lineHeight = currentLineHeight,
                pageMargins = 1.15,
                publisherStyles = false,
                scroll = false,
                theme = if (currentNightMode) Theme.DARK else Theme.LIGHT
            ),
            listener = this,
            paginationListener = this,
            configuration = EpubNavigatorFragment.Configuration(
                selectionActionModeCallback = selectionActionModeCallback
            )
        )
        supportFragmentManager.fragmentFactory = fragmentFactory
        supportFragmentManager.commitNow(allowStateLoss = true) {
            replace(
                R.id.navigator_container,
                EpubNavigatorFragment::class.java,
                Bundle(),
                NAVIGATOR_TAG
            )
        }
        navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)
            as EpubNavigatorFragment
        navigator?.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val publicationView = navigator?.publicationView ?: return false
                val width = publicationView.width.toFloat()
                if (event.point.x in (width * 0.28f)..(width * 0.72f)) {
                    toggleReaderChrome()
                    return true
                }
                val tappedRight = event.point.x > width / 2f
                val forward = if (publication?.metadata?.readingProgression == ReadingProgression.RTL) {
                    !tappedRight
                } else {
                    tappedRight
                }
                beginExplicitNavigation()
                if (forward) navigator?.goForward(animated = false)
                else navigator?.goBackward(animated = false)
                return true
            }
        })
        swipeContainer.onPageSwipe = { forward ->
            beginExplicitNavigation()
            if (forward) navigator?.goForward(animated = false)
            else navigator?.goBackward(animated = false)
        }

        loadingPanel.visibility = View.GONE
        libraryScreen.visibility = View.GONE
        readerScreen.visibility = View.VISIBLE
        resetChat()
        hideReaderChrome()
        setReaderImmersive(true)

        lifecycleScope.launch {
            bookPositions = runCatching { opened.positions() }.getOrDefault(emptyList())
            updateScrubber(navigator?.currentLocator?.value)
        }
    }

    private fun showReaderLoading(message: String) {
        hideKeyboard()
        libraryScreen.visibility = View.GONE
        chatPanel.visibility = View.GONE
        readerScreen.visibility = View.VISIBLE
        loadingPanel.visibility = View.VISIBLE
        findViewById<TextView>(R.id.loading_text).text = message
    }

    private fun showLibrary(saveLocation: Boolean = true) {
        chatOpeningJob?.cancel()
        hideKeyboard()
        if (saveLocation) saveCurrentLocation()
        setReaderImmersive(false)
        chatPanel.visibility = View.GONE
        readerScreen.visibility = View.GONE
        libraryScreen.visibility = View.VISIBLE
        updateLibraryState()
    }

    private fun showChat() {
        val currentNavigator = navigator ?: return
        if (chatOpeningJob?.isActive == true || chatPanel.visibility == View.VISIBLE) return
        chatOpeningJob = lifecycleScope.launch {
            try {
                // Capture the page before the chat or keyboard can change the reader layout.
                val locator = currentNavigator.currentLocator.value
                val pageText = captureChatPage(currentNavigator)
                if (navigator !== currentNavigator || currentNavigator.currentLocator.value != locator) {
                    return@launch
                }
                val snapshot = ChatPageSnapshot(locator, pageText)
                if (chatPageSnapshot?.let { it.locator.href == locator.href && it.text == pageText } != true) {
                    resetChat()
                }
                chatPageSnapshot = snapshot
                selectedText = (currentNavigator as? SelectableNavigator)
                    ?.currentSelection()?.locator?.text?.highlight
                    ?.takeIf { pageText.contains(it) }
                (currentNavigator as? SelectableNavigator)?.clearSelection()
                val selection = selectedText
                if (!selection.isNullOrBlank()) {
                    questionInput.hint = "Ask about “${selection.take(42)}…”"
                } else {
                    questionInput.setHint(R.string.ask_hint)
                }
                chatContextPreview.text = pageText.preview
                chatPanel.visibility = View.VISIBLE
                hideReaderChrome()
                messageScroll.scrollTo(0, 0)
                questionInput.requestFocus()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.chat_context_unavailable_title)
                    .setMessage(R.string.chat_context_unavailable)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }

    private suspend fun showDefinition(word: String) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(word)
            .setMessage(R.string.looking_up_word)
            .setPositiveButton(R.string.close, null)
            .create()
        dialog.show()

        try {
            val entry = dictionaryClient.define(word)
            if (dialog.isShowing) {
                dialog.setTitle(entry.word)
                dialog.setMessage(entry.displayText())
            }
        } catch (error: CancellationException) {
            dialog.dismiss()
            throw error
        } catch (_: Throwable) {
            if (dialog.isShowing) {
                dialog.setMessage(getString(R.string.dictionary_unavailable))
            }
        }
    }

    private fun showDictionaryMessage(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun hideChat() {
        hideKeyboard()
        chatPanel.visibility = View.GONE
        readerScreen.requestFocus()
    }

    private fun clearChat() {
        hideKeyboard()
        resetChat()
        messageScroll.scrollTo(0, 0)
        questionInput.requestFocus()
    }

    private fun resetChat() {
        cancelActiveChatRequest()
        questionInput.text.clear()
        questionInput.setHint(R.string.ask_hint)
        selectedText = null
        history.clear()
        clearResponseTailSpace()
        messages.removeAllViews()
        addWelcomeMessage()
    }

    private fun cancelActiveChatRequest() {
        chatRequestGeneration++
        chatJob?.cancel()
        chatJob = null
        sendButton.isEnabled = true
        sendButton.setText(R.string.send)
        clearChatButton.isEnabled = true
    }

    private fun forgetSavedBookIfUnavailable(uri: Uri) {
        if (preferences.getString(KEY_BOOK_URI, null) != uri.toString()) return
        preferences.edit()
            .remove(KEY_BOOK_URI)
            .remove(KEY_LAST_TITLE)
            .remove(KEY_LAST_AUTHOR)
            .apply()
    }

    private fun toggleReaderChrome() {
        val show = topBar.visibility != View.VISIBLE
        topBar.visibility = if (show) View.VISIBLE else View.GONE
        bottomBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun hideReaderChrome() {
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    private fun showChaptersDialog() {
        val chapters = flattenChapters(publication?.tableOfContents.orEmpty())
        if (chapters.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Chapters")
                .setMessage("This EPUB does not include chapter links.")
                .setPositiveButton("Done", null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Chapters")
            .setItems(chapters.map { it.label }.toTypedArray()) { dialog, which ->
                beginExplicitNavigation()
                navigator?.go(chapters[which].link, animated = false)
                dialog.dismiss()
                hideReaderChrome()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun flattenChapters(links: List<Link>, depth: Int = 0): List<ChapterEntry> =
        links.flatMap { link ->
            val title = link.title?.trim().orEmpty().ifBlank { "Untitled section" }
            listOf(ChapterEntry(link, "  ".repeat(depth) + title)) +
                flattenChapters(link.children, depth + 1)
        }

    private fun showBookmarksDialog() {
        lifecycleScope.launch {
            val currentNavigator = navigator ?: return@launch
            val anchor = precisePageAnchor(currentNavigator)
                ?: lastStablePageAnchor
                ?: currentNavigator.currentLocator.value
            lastStablePageAnchor = anchor
            showBookmarksDialog(anchor, loadBookmarks())
        }
    }

    private fun showBookmarksDialog(currentAnchor: Locator, saved: MutableList<SavedBookmark>) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 4.dp, 16.dp, 4.dp)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        lateinit var dialog: androidx.appcompat.app.AlertDialog

        content.addView(TextView(this).apply {
            text = "Add bookmark here"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            background = ContextCompat.getDrawable(context, R.drawable.button_outline)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val bookmark = SavedBookmark(currentAnchor, bookmarkLabel(currentAnchor))
                val duplicate = saved.any { samePlace(it.locator, currentAnchor) }
                if (!duplicate) {
                    saved += bookmark
                    saveBookmarks(saved)
                }
                dialog.dismiss()
                hideReaderChrome()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            46.dp
        ).apply { bottomMargin = 12.dp })

        if (saved.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "No saved bookmarks"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.ink_muted))
                setPadding(0, 18.dp, 0, 18.dp)
            })
        } else {
            saved.forEach { bookmark ->
                lateinit var row: LinearLayout
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this).apply {
                    text = bookmark.label
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, R.color.ink))
                    setPadding(10.dp)
                    isClickable = true
                    setOnClickListener {
                        beginExplicitNavigation()
                        navigator?.go(bookmark.locator, animated = false)
                        dialog.dismiss()
                        hideReaderChrome()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = "Remove"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, R.color.ink))
                    background = ContextCompat.getDrawable(context, R.drawable.button_outline)
                    isClickable = true
                    setOnClickListener {
                        saved.remove(bookmark)
                        saveBookmarks(saved)
                        content.removeView(row)
                    }
                }, LinearLayout.LayoutParams(64.dp, 38.dp))
                content.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8.dp })
            }
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Bookmarks")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun bookmarkLabel(locator: Locator): String {
        val chapter = publication?.readingOrder
            ?.firstOrNull { normalizedHref(it.href.toString()) == normalizedHref(locator.href.toString()) }
            ?.title
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val percent = ((locator.locations.totalProgression ?: 0.0) * 100).toInt()
        return listOfNotNull(chapter, "$percent%").joinToString(" · ")
    }

    private fun loadBookmarks(): MutableList<SavedBookmark> {
        val raw = preferences.getString(bookmarksKey(currentBookUri), null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                SavedBookmark(
                    locator = checkNotNull(Locator.fromJSON(item.getJSONObject("locator"))),
                    label = item.getString("label")
                )
            }
        }.getOrDefault(mutableListOf())
    }

    private fun saveBookmarks(bookmarks: List<SavedBookmark>) {
        val array = JSONArray()
        bookmarks.sortedBy { it.locator.locations.totalProgression ?: 0.0 }.forEach { bookmark ->
            array.put(JSONObject().put("label", bookmark.label).put("locator", bookmark.locator.toJSON()))
        }
        preferences.edit().putString(bookmarksKey(currentBookUri), array.toString()).apply()
    }

    private fun samePlace(first: Locator, second: Locator): Boolean {
        if (normalizedHref(first.href.toString()) != normalizedHref(second.href.toString())) return false
        val firstProgress = first.locations.totalProgression
        val secondProgress = second.locations.totalProgression
        return first.locations.fragments == second.locations.fragments ||
            (firstProgress != null && secondProgress != null &&
                kotlin.math.abs(firstProgress - secondProgress) < 0.0005)
    }

    private fun normalizedHref(value: String): String = value.substringBefore('#')

    private fun setReaderImmersive(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, findViewById(R.id.root))
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showReadingAppearanceDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 4.dp, 18.dp, 4.dp)
        }

        val sizeValues = FONT_SCALES
        val selectedSize = sizeValues.indices.minByOrNull {
            kotlin.math.abs(sizeValues[it] - currentFontScale)
        } ?: 1
        addChoiceGroup(
            parent = content,
            title = "Text size",
            labels = listOf("S", "M", "L", "XL", "XXL"),
            selectedIndex = selectedSize
        ) { which ->
            currentFontScale = sizeValues[which]
            saveReadingPreferences()
            applyReadingPreferences()
        }

        val lineHeights = doubleArrayOf(1.25, 1.45, 1.70)
        val selectedLineHeight = lineHeights.indices.minByOrNull {
            kotlin.math.abs(lineHeights[it] - currentLineHeight)
        } ?: 1
        addChoiceGroup(
            parent = content,
            title = "Line spacing",
            labels = listOf("Tight", "Normal", "Relaxed"),
            selectedIndex = selectedLineHeight
        ) { which ->
            currentLineHeight = lineHeights[which]
            saveReadingPreferences()
            applyReadingPreferences()
        }

        val fonts = ReaderFont.entries
        addChoiceGroup(
            parent = content,
            title = "Font",
            labels = fonts.map { it.label },
            selectedIndex = fonts.indexOf(currentReaderFont)
        ) { which ->
            currentReaderFont = fonts[which]
            saveReadingPreferences()
            applyReadingPreferences()
        }

        addChoiceGroup(
            parent = content,
            title = "Page",
            labels = listOf("Day", "Night"),
            selectedIndex = if (currentNightMode) 1 else 0
        ) { which ->
            currentNightMode = which == 1
            saveReadingPreferences()
            applyReadingPreferences()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Reading appearance")
            .setView(content)
            .setPositiveButton("Done", null)
            .create()
            .apply {
                setOnDismissListener { hideReaderChrome() }
                show()
            }
    }

    private fun addChoiceGroup(
        parent: LinearLayout,
        title: String,
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        parent.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setPadding(0, 14.dp, 0, 6.dp)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val choices = labels.mapIndexed { index, label ->
            TextView(this).apply {
                text = label
                textSize = if (labels.size >= 5) 12f else 13f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setTextColor(ContextCompat.getColor(context, R.color.ink))
                setTypeface(typeface, if (index == selectedIndex) Typeface.BOLD else Typeface.NORMAL)
                background = choiceBackground(selected = index == selectedIndex)
                setOnClickListener {
                    row.children().forEachIndexed { choiceIndex, choice ->
                        choice.setTypeface(
                            choice.typeface,
                            if (choiceIndex == index) Typeface.BOLD else Typeface.NORMAL
                        )
                        choice.background = choiceBackground(selected = choiceIndex == index)
                    }
                    onSelected(index)
                }
            }
        }
        choices.forEachIndexed { index, choice ->
            row.addView(
                choice,
                LinearLayout.LayoutParams(0, 42.dp, 1f).apply {
                    if (index > 0) marginStart = 5.dp
                }
            )
        }
        parent.addView(row)
    }

    private fun LinearLayout.children(): List<TextView> =
        (0 until childCount).map { getChildAt(it) as TextView }

    private fun choiceBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(AndroidColor.WHITE)
            setStroke(if (selected) 3.dp else 1.dp, AndroidColor.BLACK)
            cornerRadius = 3.dp.toFloat()
        }

    private fun saveReadingPreferences() {
        preferences.edit()
            .putFloat(fontKey(currentBookUri), currentFontScale.toFloat())
            .putInt(fontScaleVersionKey(currentBookUri), FONT_SCALE_VERSION)
            .putFloat(lineHeightKey(currentBookUri), currentLineHeight.toFloat())
            .putString(fontFamilyKey(currentBookUri), currentReaderFont.preferenceValue)
            .putBoolean(nightModeKey(currentBookUri), currentNightMode)
            .apply()
    }

    private fun applyReadingPreferences() {
        lifecycleScope.launch {
            val currentNavigator = navigator ?: return@launch
            val anchor = pendingReflowAnchor
                ?: preservedReflowAnchor
                ?: precisePageAnchor(currentNavigator)
                ?: lastStablePageAnchor
                ?: currentNavigator.currentLocator.value
            preservedReflowAnchor = anchor
            lastStablePageAnchor = anchor
            currentNavigator.submitPreferences(
                EpubPreferences(
                    backgroundColor = pageBackgroundColor(),
                    textColor = pageTextColor(),
                    fontSize = currentFontScale,
                    fontFamily = currentReaderFont.family,
                    lineHeight = currentLineHeight,
                    pageMargins = 1.15,
                    publisherStyles = false,
                    scroll = false,
                    textNormalization = true,
                    theme = if (currentNightMode) Theme.DARK else Theme.LIGHT
                )
            )
            startReflowRestore(anchor, delayMillis = 500)
        }
    }

    private fun pageBackgroundColor(): Color =
        Color(if (currentNightMode) AndroidColor.BLACK else AndroidColor.WHITE)

    private fun pageTextColor(): Color =
        Color(if (currentNightMode) AndroidColor.WHITE else AndroidColor.BLACK)

    private fun startReflowRestore(anchor: Locator, delayMillis: Long) {
        pendingReflowAnchor = anchor
        reflowRestoreAttempt = 0
        scheduleReflowRestore(delayMillis)
    }

    private fun scheduleReflowRestore(delayMillis: Long) {
        val publicationView = navigator?.publicationView ?: return
        reflowRestoreRunnable?.let(publicationView::removeCallbacks)
        val restore = Runnable {
            val anchor = pendingReflowAnchor ?: return@Runnable
            reflowRestoreRunnable = null
            navigator?.go(anchor, animated = false)
            reflowRestoreAttempt++
            if (reflowRestoreAttempt < REFLOW_RESTORE_ATTEMPTS) {
                scheduleReflowRestore(REFLOW_RESTORE_RETRY_DELAY_MILLIS)
            } else {
                pendingReflowAnchor = null
                reflowRestoreAttempt = 0
            }
        }
        reflowRestoreRunnable = restore
        publicationView.postDelayed(restore, delayMillis)
    }

    private fun updateLibraryState() {
        val uri = preferences.getString(KEY_BOOK_URI, null)
        if (uri == null) {
            continueButton.visibility = View.GONE
            lastBookText.visibility = View.GONE
            return
        }
        val title = preferences.getString(KEY_LAST_TITLE, "Last book") ?: "Last book"
        val author = preferences.getString(KEY_LAST_AUTHOR, "").orEmpty()
        val percent = preferences.getInt(progressKey(Uri.parse(uri)), 0)
        continueButton.text = "Continue reading"
        lastBookText.text = listOf(title, author, "$percent% read")
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        continueButton.visibility = View.VISIBLE
        lastBookText.visibility = View.VISIBLE
    }

    private fun submitQuestion(text: String = questionInput.text.toString()) {
        val question = text.trim()
        val opened = publication ?: return
        val pageSnapshot = chatPageSnapshot ?: return
        if (question.isEmpty() || chatJob?.isActive == true) return
        hideKeyboard()
        val apiKey = apiKeyStore.get()
        if (apiKey.isNullOrBlank()) {
            showApiKeyDialog(afterSave = { submitQuestion(question) })
            return
        }

        questionInput.text.clear()
        clearResponseTailSpace()
        addMessage("You", question, isUser = true)
        val responseView = addMessage("Exy", "Thinking…", isUser = false)
        prepareResponseViewport(responseView)
        val priorHistory = history.snapshot()
        sendButton.isEnabled = false
        sendButton.text = "…"
        clearChatButton.isEnabled = false
        val requestGeneration = ++chatRequestGeneration

        chatJob = lifecycleScope.launch {
            val streamedText = StringBuffer()
            val displayJob = launch {
                while (true) {
                    delay(STREAM_REFRESH_MILLIS)
                    val snapshot = streamedText.toString()
                    if (snapshot.isNotEmpty() && responseView.text.toString() != snapshot) {
                        responseView.text = snapshot
                    }
                }
            }
            try {
                val context = BookContextBuilder.build(
                    publication = opened,
                    current = pageSnapshot.locator,
                    selectedText = selectedText,
                    pageText = pageSnapshot.text
                )
                val answer = chatClient.askStreaming(
                    apiKey = apiKey,
                    question = question,
                    context = context,
                    history = priorHistory,
                    onToken = streamedText::append
                )
                if (requestGeneration != chatRequestGeneration) return@launch
                responseView.text = answer
                history.recordExchange(question, answer)
                selectedText = null
            } catch (_: CancellationException) {
                // A new page or cleared chat cancels the old reply.
            } catch (error: Throwable) {
                if (requestGeneration != chatRequestGeneration) return@launch
                responseView.text = error.message ?: "I could not answer that question."
                if (questionInput.text.isBlank()) {
                    questionInput.setText(question)
                    questionInput.setSelection(questionInput.text.length)
                }
            } finally {
                displayJob.cancel()
                if (requestGeneration == chatRequestGeneration) {
                    chatJob = null
                    sendButton.isEnabled = true
                    sendButton.setText(R.string.send)
                    clearChatButton.isEnabled = true
                }
            }
        }
    }

    private fun addWelcomeMessage() {
        addMessage(
            "Exy",
            getString(R.string.exy_welcome),
            isUser = false
        )
    }

    private suspend fun captureChatPage(currentNavigator: EpubNavigatorFragment): ChatPageText {
        val script = assets.open("chat-page.js").bufferedReader().use { it.readText() }
        val rawResult = currentNavigator.evaluateJavascript(script)
            ?: error("The page is not ready.")
        val encoded = JSONTokener(rawResult).nextValue() as? String
            ?: error("The page has no readable text.")
        val value = JSONObject(encoded)
        return ChatPageText(
            resourceText = value.getString("resourceText"),
            visibleText = value.getString("visibleText")
        )
    }

    private suspend fun precisePageAnchor(currentNavigator: EpubNavigatorFragment): Locator? {
        val rawResult = currentNavigator.evaluateJavascript(VISIBLE_START_PHRASE_ANCHOR_SCRIPT)
            ?: return currentNavigator.firstVisibleElementLocator()
        val text = runCatching {
            val encoded = JSONTokener(rawResult).nextValue() as? String ?: return@runCatching null
            val value = JSONTokener(encoded).nextValue() as? JSONObject ?: return@runCatching null
            Locator.Text(
                before = value.optString("before").takeIf { it.isNotEmpty() },
                highlight = value.optString("highlight").takeIf { it.isNotEmpty() },
                after = value.optString("after").takeIf { it.isNotEmpty() }
            )
        }.getOrNull() ?: return currentNavigator.firstVisibleElementLocator()
        val current = currentNavigator.currentLocator.value
        return current.copy(text = text)
    }

    private fun refreshAnchorAfterOpen() {
        val generation = ++anchorCaptureGeneration
        lifecycleScope.launch {
            delay(SAVED_ANCHOR_REFRESH_DELAY_MILLIS)
            if (generation != anchorCaptureGeneration || pendingReflowAnchor != null) return@launch
            val exactAnchor = navigator?.let { precisePageAnchor(it) } ?: return@launch
            if (generation != anchorCaptureGeneration || pendingReflowAnchor != null) return@launch
            refreshSavedAnchorOnStablePage = false
            preservedReflowAnchor = exactAnchor
            lastStablePageAnchor = exactAnchor
            preferences.edit()
                .putString(locatorKey(currentBookUri), exactAnchor.toJSON().toString())
                .apply()
        }
    }

    private fun showApiKeyDialog(afterSave: (() -> Unit)? = null) {
        val hasExistingKey = !apiKeyStore.get().isNullOrBlank()
        val input = EditText(this).apply {
            hint = "sk-or-v1-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(16.dp)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("OpenRouter API key")
            .setMessage(
                "Exegete encrypts this key with Android Keystore. Your EPUB file stays on this " +
                    "device. When you ask Exy, relevant text up to your current page and the " +
                    "conversation are sent to OpenRouter and its selected model provider."
            )
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
        if (hasExistingKey) {
            builder.setNeutralButton(R.string.remove_key) { _, _ ->
                apiKeyStore.clear()
                updateApiKeyStatus()
            }
        }
        val dialog = builder.create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text.toString().trim()
                if (!key.startsWith("sk-or-")) {
                    input.error = "Enter an OpenRouter key."
                    return@setOnClickListener
                }
                apiKeyStore.save(key)
                updateApiKeyStatus()
                dialog.dismiss()
                afterSave?.invoke()
            }
        }
        dialog.show()
    }

    private fun updateApiKeyStatus() {
        val configured = !apiKeyStore.get().isNullOrBlank()
        apiKeyButton.text = if (configured) "Replace OpenRouter key" else "Set OpenRouter key"
        modelStatus.text = if (configured) {
            "MiniMax M3 · free · key stored securely"
        } else {
            "MiniMax M3 · free · key not set"
        }
    }

    private fun addMessage(label: String, text: String, isUser: Boolean): TextView {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { hideKeyboard() }
            if (isUser) {
                background = ContextCompat.getDrawable(context, R.drawable.panel_border)
                setPadding(12.dp)
            }
        }
        val labelView = TextView(this).apply {
            this.text = label.uppercase()
            textSize = 9f
            setTextColor(ContextCompat.getColor(context, R.color.ink_muted))
            setTypeface(typeface, Typeface.BOLD)
        }
        val bodyView = TextView(this).apply {
            this.text = text
            textSize = 17f
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            typeface = Typeface.SERIF
            setLineSpacing(0f, 1.25f)
            setPadding(0, 5.dp, 0, 0)
        }
        group.addView(labelView)
        group.addView(bodyView)
        messages.addView(
            group,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 18.dp
                if (isUser) marginStart = 32.dp
            }
        )
        return bodyView
    }

    private fun prepareResponseViewport(responseView: TextView) {
        responseView.postDelayed({
            clearResponseTailSpace()
            val tailSpace = View(this).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = true
                setOnClickListener { hideKeyboard() }
            }
            responseTailSpace = tailSpace
            messages.addView(
                tailSpace,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (messageScroll.height * RESPONSE_TAIL_FRACTION).toInt()
                )
            )
            val responseGroup = responseView.parent as? View ?: return@postDelayed
            val target = (responseGroup.top - messageScroll.height * RESPONSE_TOP_FRACTION)
                .toInt()
                .coerceAtLeast(0)
            messageScroll.scrollTo(0, target)
        }, RESPONSE_POSITION_DELAY_MILLIS)
    }

    private fun clearResponseTailSpace() {
        responseTailSpace?.let { messages.removeView(it) }
        responseTailSpace = null
    }

    override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
        if (pendingReflowAnchor != null) {
            // Readium emits transient locations during reflow. The bounded restore sequence
            // owns the location until the new page geometry has settled.
            return
        }
        if (refreshSavedAnchorOnStablePage) {
            // The initial locator can produce several temporary pages while the EPUB loads.
            // Debounce those events, then replace the stored locator with the visible page.
            refreshAnchorAfterOpen()
            return
        }
        val percent = (100 * (locator.locations.totalProgression ?: 0.0)).toInt()
        progressText.text = "$percent%"
        updateScrubber(locator)
        preferences.edit()
            .putInt(progressKey(currentBookUri), percent)
            .apply()

        preservedReflowAnchor?.let { anchor ->
            lastStablePageAnchor = anchor
            preferences.edit()
                .putString(locatorKey(currentBookUri), anchor.toJSON().toString())
                .apply()
            return
        }

        val generation = ++anchorCaptureGeneration
        lifecycleScope.launch {
            delay(PAGE_ANCHOR_CAPTURE_DELAY_MILLIS)
            if (generation != anchorCaptureGeneration || pendingReflowAnchor != null) return@launch
            val exactAnchor = navigator?.let { precisePageAnchor(it) } ?: locator
            if (generation != anchorCaptureGeneration || pendingReflowAnchor != null) return@launch
            lastStablePageAnchor = exactAnchor
            preferences.edit()
                .putString(locatorKey(currentBookUri), exactAnchor.toJSON().toString())
                .apply()
        }
    }

    private fun updateScrubber(locator: Locator?) {
        val progress = ((locator?.locations?.totalProgression ?: 0.0) * pageScrubber.max)
            .roundToInt()
            .coerceIn(0, pageScrubber.max)
        updatingScrubber = true
        pageScrubber.progress = progress
        updatingScrubber = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val anchor = preservedReflowAnchor
            ?: pendingReflowAnchor
            ?: lastStablePageAnchor
            ?: navigator?.currentLocator?.value
        if (anchor != null) {
            preservedReflowAnchor = anchor
        }
        anchorCaptureGeneration++
        super.onConfigurationChanged(newConfig)
        if (anchor != null) startReflowRestore(anchor, delayMillis = ROTATION_RESTORE_DELAY_MILLIS)
    }

    private fun beginExplicitNavigation() {
        reflowRestoreRunnable?.let { navigator?.publicationView?.removeCallbacks(it) }
        reflowRestoreRunnable = null
        reflowRestoreAttempt = 0
        refreshSavedAnchorOnStablePage = false
        preservedReflowAnchor = null
        pendingReflowAnchor = null
        anchorCaptureGeneration++
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
        }
    }

    override fun shouldFollowInternalLink(link: Link, context: org.readium.r2.navigator.HyperlinkNavigator.LinkContext?): Boolean {
        beginExplicitNavigation()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isReading = readerScreen.visibility == View.VISIBLE &&
            chatPanel.visibility != View.VISIBLE &&
            loadingPanel.visibility != View.VISIBLE
        if (!isReading) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                beginExplicitNavigation()
                navigator?.goForward(animated = false) ?: false
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                beginExplicitNavigation()
                navigator?.goBackward(animated = false) ?: false
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        reflowRestoreRunnable?.let { navigator?.publicationView?.removeCallbacks(it) }
        saveCurrentLocation()
        publication?.close()
        super.onDestroy()
    }

    private fun locatorKey(uri: Uri?): String = "locator:${uri ?: "none"}"
    private fun progressKey(uri: Uri?): String = "progress:${uri ?: "none"}"
    private fun fontKey(uri: Uri?): String = "font:${uri ?: "none"}"
    private fun fontScaleVersionKey(uri: Uri?): String = "font-scale-version:${uri ?: "none"}"
    private fun lineHeightKey(uri: Uri?): String = "line-height:${uri ?: "none"}"
    private fun fontFamilyKey(uri: Uri?): String = "font-family:${uri ?: "none"}"
    private fun nightModeKey(uri: Uri?): String = "night-mode:${uri ?: "none"}"
    private fun bookmarksKey(uri: Uri?): String = "bookmarks:${uri ?: "none"}"

    private fun loadFontScale(uri: Uri?): Double {
        val stored = preferences
            .getFloat(fontKey(uri), OLD_DEFAULT_FONT_SCALE.toFloat())
            .toDouble()
        if (preferences.getInt(fontScaleVersionKey(uri), 1) >= FONT_SCALE_VERSION) {
            return stored
        }

        val oldIndex = OLD_FONT_SCALES.indices.minByOrNull {
            kotlin.math.abs(OLD_FONT_SCALES[it] - stored)
        } ?: 1
        val upgraded = FONT_SCALES[oldIndex]
        preferences.edit()
            .putFloat(fontKey(uri), upgraded.toFloat())
            .putInt(fontScaleVersionKey(uri), FONT_SCALE_VERSION)
            .apply()
        return upgraded
    }

    private fun saveCurrentLocation() {
        // If a typesetting reflow is still in progress, the navigator's current location is
        // transient. Preserve the pre-resize semantic anchor instead.
        val locator = pendingReflowAnchor
            ?: preservedReflowAnchor
            ?: lastStablePageAnchor
            ?: navigator?.currentLocator?.value
            ?: return
        preferences.edit()
            .putString(locatorKey(currentBookUri), locator.toJSON().toString())
            .apply()
    }

    private fun hideKeyboard() {
        questionInput.clearFocus()
        WindowInsetsControllerCompat(window, findViewById(R.id.root))
            .hide(WindowInsetsCompat.Type.ime())
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(findViewById<View>(R.id.root).windowToken, 0)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val MENU_DEFINE = 0x455859
        private const val NAVIGATOR_TAG = "epub-navigator"
        private const val KEY_BOOK_URI = "last-book-uri"
        private const val KEY_LAST_TITLE = "last-book-title"
        private const val KEY_LAST_AUTHOR = "last-book-author"
        private const val DEFAULT_FONT_SCALE = 1.15
        private const val OLD_DEFAULT_FONT_SCALE = 1.05
        private const val DEFAULT_LINE_HEIGHT = 1.45
        private const val FONT_SCALE_VERSION = 2
        private const val STREAM_REFRESH_MILLIS = 500L
        private const val PAGE_ANCHOR_CAPTURE_DELAY_MILLIS = 180L
        private const val SAVED_ANCHOR_REFRESH_DELAY_MILLIS = 900L
        private const val ROTATION_RESTORE_DELAY_MILLIS = 450L
        private const val REFLOW_RESTORE_ATTEMPTS = 7
        private const val REFLOW_RESTORE_RETRY_DELAY_MILLIS = 700L
        private const val RESPONSE_POSITION_DELAY_MILLIS = 250L
        private const val RESPONSE_TOP_FRACTION = 0.33
        private const val RESPONSE_TAIL_FRACTION = 0.75
        private val OLD_FONT_SCALES = doubleArrayOf(0.90, 1.00, 1.15, 1.30, 1.50)
        private val FONT_SCALES = doubleArrayOf(1.00, 1.15, 1.30, 1.50, 1.70)
        private val VISIBLE_START_PHRASE_ANCHOR_SCRIPT = """
            (() => {
              const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
              let node;
              const visibleWords = [];
              while (visibleWords.length < 12 && (node = walker.nextNode())) {
                const source = node.nodeValue || '';
                for (const word of source.matchAll(/\S+/g)) {
                  const range = document.createRange();
                  range.setStart(node, word.index);
                  range.setEnd(node, word.index + word[0].length);
                  const visible = Array.from(range.getClientRects()).some(rect =>
                    rect.right > 0 && rect.left < window.innerWidth &&
                    rect.bottom > 0 && rect.top < window.innerHeight
                  );
                  if (visible) {
                    visibleWords.push({
                      node,
                      start: word.index,
                      end: word.index + word[0].length
                    });
                    if (visibleWords.length >= 12) break;
                  }
                }
              }
              if (!visibleWords.length || !window.readium?.getCurrentSelection) {
                return JSON.stringify(null);
              }
              const first = visibleWords[0];
              const last = visibleWords[visibleWords.length - 1];
              const anchor = document.createRange();
              anchor.setStart(first.node, first.start);
              anchor.setEnd(last.node, last.end);
              const selection = window.getSelection();
              selection.removeAllRanges();
              selection.addRange(anchor);
              const info = window.readium.getCurrentSelection();
              selection.removeAllRanges();
              return JSON.stringify(info?.text || null);
            })()
        """.trimIndent()
    }

    private enum class ReaderFont(
        val label: String,
        val preferenceValue: String,
        val family: FontFamily
    ) {
        SERIF("Serif", "serif", FontFamily.SERIF),
        SANS("Sans", "sans", FontFamily.SANS_SERIF),
        OPEN_DYSLEXIC("OpenDyslexic", "open-dyslexic", FontFamily.OPEN_DYSLEXIC);

        companion object {
            fun fromPreference(value: String?): ReaderFont =
                entries.firstOrNull { it.preferenceValue == value } ?: SERIF
        }
    }

    private data class ChapterEntry(val link: Link, val label: String)

    private data class SavedBookmark(val locator: Locator, val label: String)

    private data class ChatPageSnapshot(val locator: Locator, val text: ChatPageText)
}
