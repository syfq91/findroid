package dev.jdtech.jellyfin

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.databinding.ActivityBookReaderBinding
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.presentation.book.components.ReaderAppearanceSheet
import dev.jdtech.jellyfin.presentation.book.components.ReaderPreferencesState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression as NavReadingProgression
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression as PubReadingProgression
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.FormatHints
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import timber.log.Timber

@OptIn(ExperimentalReadiumApi::class, FlowPreview::class)
@AndroidEntryPoint
class BookReaderActivity : AppCompatActivity() {

    @Inject lateinit var jellyfinRepository: JellyfinRepository
    @Inject lateinit var appPreferences: AppPreferences

    private lateinit var binding: ActivityBookReaderBinding
    private lateinit var bookId: String
    private lateinit var bookUUID: UUID
    private var bookTitle: String = ""

    private var publication: Publication? = null
    private var navigator: OverflowableNavigator? = null
    private var locatorJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityBookReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId =
            intent.getStringExtra(EXTRA_BOOK_ID)
                ?: run {
                    finish()
                    return
                }
        bookUUID = UUID.fromString(bookId)
        bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()

        binding.textBookTitle.text = bookTitle
        applyOrientation()
        applyReadingProgression()
        updateBackgroundTheme()
        setupControls()

        loadBookAndOpen(savedInstanceState)
    }

    private fun setupControls() {
        binding.buttonBack.setOnClickListener { finish() }

        binding.buttonAppearance.setOnClickListener { showAppearanceDialog() }

        binding.buttonToc.setOnClickListener { showTableOfContents() }

        binding.buttonPrevPage.setOnClickListener { navigator?.goBackward(animated = true) }

        binding.buttonNextPage.setOnClickListener { navigator?.goForward(animated = true) }
    }

    private fun toggleControls() {
        val willShow = !binding.controlsOverlay.isVisible
        binding.controlsOverlay.isVisible = willShow
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (willShow) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun isReadingProgressionRTL(pub: Publication? = publication): Boolean {
        return when (appPreferences.getValue(appPreferences.readerReadingProgression)) {
            "rtl" -> true
            "ltr" -> false
            else -> pub?.metadata?.readingProgression == PubReadingProgression.RTL
        }
    }

    private fun applyReadingProgression(pub: Publication? = publication) {
        val isRtl = isReadingProgressionRTL(pub)
        val direction = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        window.decorView.layoutDirection = direction
        if (::binding.isInitialized) {
            binding.root.layoutDirection = direction
            binding.fragmentContainer.layoutDirection = direction
        }
    }

    private fun applyOrientation() {
        requestedOrientation =
            when (appPreferences.getValue(appPreferences.readerOrientation)) {
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_USER
            }
    }

    private fun updateBackgroundTheme() {
        val themeStr = appPreferences.getValue(appPreferences.readerTheme)
        val color =
            when (themeStr) {
                "light" -> Color.parseColor("#FAFAFA")
                "sepia" -> Color.parseColor("#FBF0D9")
                else -> Color.parseColor("#121212")
            }
        binding.readerRoot.setBackgroundColor(color)
    }

    private fun buildEpubPreferences(): EpubPreferences {
        val theme =
            when (appPreferences.getValue(appPreferences.readerTheme)) {
                "light" -> Theme.LIGHT
                "sepia" -> Theme.SEPIA
                else -> Theme.DARK
            }
        val fontFamily =
            when (appPreferences.getValue(appPreferences.readerFontFamily)) {
                "serif" -> FontFamily.SERIF
                "sans" -> FontFamily.SANS_SERIF
                "opendyslexic" -> FontFamily.OPEN_DYSLEXIC
                else -> null
            }
        val fontSize = appPreferences.getValue(appPreferences.readerFontSize).toDouble()
        val scroll = appPreferences.getValue(appPreferences.readerScroll)

        val progression =
            when (appPreferences.getValue(appPreferences.readerReadingProgression)) {
                "ltr" -> NavReadingProgression.LTR
                "rtl" -> NavReadingProgression.RTL
                else -> null
            }

        return EpubPreferences(
            theme = theme,
            fontFamily = fontFamily,
            fontSize = fontSize,
            scroll = scroll,
            readingProgression = progression,
        )
    }

    private fun applyPreferences() {
        val epubPrefs = buildEpubPreferences()
        (navigator as? EpubNavigatorFragment)?.submitPreferences(epubPrefs)
        updateBackgroundTheme()
    }

    private fun showAppearanceDialog() {
        val bottomSheet = BottomSheetDialog(this)
        val isImageBased = publication?.conformsTo(Publication.Profile.DIVINA) == true
        val initialPrefs =
            ReaderPreferencesState(
                fontSize = appPreferences.getValue(appPreferences.readerFontSize),
                fontFamily = appPreferences.getValue(appPreferences.readerFontFamily),
                theme = appPreferences.getValue(appPreferences.readerTheme),
                scroll = appPreferences.getValue(appPreferences.readerScroll),
                orientation = appPreferences.getValue(appPreferences.readerOrientation),
                readingProgression =
                    appPreferences.getValue(appPreferences.readerReadingProgression),
                isImageBased = isImageBased,
            )

        val composeView =
            ComposeView(this).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    var sheetState by remember { mutableStateOf(initialPrefs) }
                    FindroidTheme {
                        ReaderAppearanceSheet(
                            state = sheetState,
                            onFontSizeChanged = { newSize ->
                                sheetState = sheetState.copy(fontSize = newSize)
                                appPreferences.setValue(appPreferences.readerFontSize, newSize)
                                applyPreferences()
                            },
                            onFontFamilyChanged = { newFont ->
                                sheetState = sheetState.copy(fontFamily = newFont)
                                appPreferences.setValue(appPreferences.readerFontFamily, newFont)
                                applyPreferences()
                            },
                            onThemeChanged = { newTheme ->
                                sheetState = sheetState.copy(theme = newTheme)
                                appPreferences.setValue(appPreferences.readerTheme, newTheme)
                                applyPreferences()
                            },
                            onScrollChanged = { newScroll ->
                                sheetState = sheetState.copy(scroll = newScroll)
                                appPreferences.setValue(appPreferences.readerScroll, newScroll)
                                applyPreferences()
                            },
                            onOrientationChanged = { newOrientation ->
                                sheetState = sheetState.copy(orientation = newOrientation)
                                appPreferences.setValue(
                                    appPreferences.readerOrientation,
                                    newOrientation,
                                )
                                applyOrientation()
                            },
                            onProgressionChanged = { newProgression ->
                                sheetState = sheetState.copy(readingProgression = newProgression)
                                appPreferences.setValue(
                                    appPreferences.readerReadingProgression,
                                    newProgression,
                                )
                                applyReadingProgression()
                                val pub = publication
                                if (pub != null && pub.conformsTo(Publication.Profile.DIVINA)) {
                                    val currentLoc = navigator?.currentLocator?.value
                                    initDivinaNavigator(pub, currentLoc, savedInstanceState = null)
                                } else {
                                    applyPreferences()
                                }
                            },
                            onDismiss = { bottomSheet.dismiss() },
                        )
                    }
                }
            }

        bottomSheet.setContentView(composeView)
        bottomSheet.show()
    }

    private fun loadBookAndOpen(savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            binding.loadingLayout.isVisible = true
            try {
                val prepared = prepareBookFile()
                openPublication(prepared, savedInstanceState)
                binding.loadingLayout.isVisible = false
            } catch (e: Exception) {
                Timber.e(e, "Error opening book $bookId")
                binding.loadingLayout.isVisible = false
                Toast.makeText(
                        this@BookReaderActivity,
                        e.localizedMessage ?: getString(CoreR.string.error_opening_book),
                        Toast.LENGTH_LONG,
                    )
                    .show()
                finish()
            }
        }
    }

    private fun detectBookFormat(file: File): String {
        try {
            ZipFile(file).use { zip ->
                if (
                    zip.getEntry("META-INF/container.xml") != null ||
                        zip.getEntry("mimetype") != null
                ) {
                    return "epub"
                }
                val hasImages =
                    zip.entries().asSequence().any { entry ->
                        !entry.isDirectory &&
                            entry.name.lowercase().let { name ->
                                name.endsWith(".jpg") ||
                                    name.endsWith(".jpeg") ||
                                    name.endsWith(".png") ||
                                    name.endsWith(".webp") ||
                                    name.endsWith(".avif") ||
                                    name.endsWith(".gif")
                            }
                    }
                if (hasImages) {
                    return "cbz"
                }
            }
        } catch (_: Exception) {
            try {
                FileInputStream(file).use { input ->
                    val header = ByteArray(7)
                    val read = input.read(header)
                    if (read >= 4) {
                        if (
                            header[0] == 0x52.toByte() &&
                                header[1] == 0x61.toByte() &&
                                header[2] == 0x72.toByte() &&
                                header[3] == 0x21.toByte()
                        ) {
                            return "cbr"
                        }
                        if (
                            header[0] == 0x25.toByte() &&
                                header[1] == 0x50.toByte() &&
                                header[2] == 0x44.toByte() &&
                                header[3] == 0x46.toByte()
                        ) {
                            return "pdf"
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return "epub"
    }

    private suspend fun prepareBookFile(): PreparedBook =
        withContext(Dispatchers.IO) {
            var container = intent.getStringExtra(EXTRA_BOOK_CONTAINER)
            var serverPositionTicks = 0L
            try {
                val book = jellyfinRepository.getBook(bookUUID)
                serverPositionTicks = book.playbackPositionTicks
                if (container.isNullOrBlank()) {
                    container = book.container
                }
                val localSource = book.sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                if (localSource != null) {
                    val localFile = File(localSource.path)
                    if (localFile.exists() && localFile.length() > 0) {
                        Timber.d("Using downloaded local book file: ${localFile.absolutePath}")
                        val detected = detectBookFormat(localFile)
                        val ext =
                            container?.lowercase()?.removePrefix(".")?.takeIf {
                                it in listOf("cbz", "cbr", "epub", "pdf")
                            } ?: detected
                        val hints = FormatHints().addFileExtension(ext)
                        return@withContext PreparedBook(localFile, serverPositionTicks, hints)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not fetch book details or local source")
            }

            val booksDir = File(cacheDir, "books").apply { mkdirs() }
            val existing =
                booksDir
                    .listFiles { _, name -> name.startsWith("$bookId.") && !name.endsWith(".tmp") }
                    ?.firstOrNull { it.length() > 0 }
            if (existing != null) {
                val detected = detectBookFormat(existing)
                val resolvedFile =
                    if (
                        existing.extension != detected &&
                            (detected == "cbz" || detected == "cbr" || detected == "epub")
                    ) {
                        val corrected = File(booksDir, "$bookId.$detected")
                        if (corrected.exists()) corrected.delete()
                        existing.renameTo(corrected)
                        corrected
                    } else {
                        existing
                    }
                val hints =
                    FormatHints().addFileExtension(resolvedFile.extension.ifBlank { detected })
                return@withContext PreparedBook(resolvedFile, serverPositionTicks, hints)
            }

            val tempFile = File(booksDir, "$bookId.tmp")

            try {
                jellyfinRepository.openDownloadStream(bookUUID).use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }

            val detected = detectBookFormat(tempFile)
            val finalExt =
                container?.lowercase()?.removePrefix(".")?.takeIf {
                    it in listOf("cbz", "cbr", "epub", "pdf")
                } ?: detected

            val targetFile = File(booksDir, "$bookId.$finalExt")
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
            val hints = FormatHints().addFileExtension(finalExt)
            PreparedBook(targetFile, serverPositionTicks, hints)
        }

    /** The resolved book file plus the reading position the server has for it, in ticks. */
    private data class PreparedBook(
        val file: File,
        val serverPositionTicks: Long,
        val formatHints: FormatHints? = null,
    )

    private suspend fun openPublication(prepared: PreparedBook, savedInstanceState: Bundle?) {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(contentResolver, httpClient)
        val hints = prepared.formatHints ?: FormatHints()
        val asset =
            assetRetriever.retrieve(prepared.file, hints).getOrElse {
                throw IOException("Failed to read book file: $it")
            }
        val streamer =
            PublicationOpener(
                DefaultPublicationParser(
                    context = this,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null,
                )
            )
        val pub =
            streamer.open(asset, allowUserInteraction = false).getOrElse {
                throw IOException("Failed to open book: $it")
            }
        this.publication = pub

        applyReadingProgression(pub)

        val initialLocator = restoreLocator(pub, prepared.serverPositionTicks)

        if (pub.conformsTo(Publication.Profile.DIVINA)) {
            initDivinaNavigator(pub, initialLocator, savedInstanceState)
        } else {
            initEpubNavigator(pub, initialLocator, savedInstanceState)
        }
    }

    /**
     * Returns the locator to open the book at. The local locator is authoritative when present,
     * since it is more precise. Otherwise fall back to the reading position the server has for this
     * book so a book read on another device resumes near where it was left off.
     */
    private suspend fun restoreLocator(pub: Publication, serverPositionTicks: Long): Locator? {
        val savedLocatorJson =
            appPreferences.sharedPreferences.getString(KEY_LOCATOR_PREFIX + bookId, null)
        val localLocator = savedLocatorJson?.let {
            try {
                Locator.fromJSON(JSONObject(it))
            } catch (e: Exception) {
                Timber.e(e, "Error parsing saved locator")
                null
            }
        }
        if (localLocator != null) return localLocator

        return locatorFromServerProgress(pub, serverPositionTicks)
    }

    /**
     * Derives a locator from the server's saved position.
     *
     * The reader posts `ticks = progression * VIRTUAL_TICKS_TOTAL`, so the inverse is
     * `playbackPositionTicks / VIRTUAL_TICKS_TOTAL` — a synthetic scale unrelated to the runtime
     * Jellyfin reports for a book. Readium 3.4 has no `locatorFromProgression`, so map the
     * progression onto the positions service the publication opener registered
     * (EpubPositionsService for EPUB, PerResourcePositionsService for Divina).
     */
    private suspend fun locatorFromServerProgress(
        pub: Publication,
        serverPositionTicks: Long,
    ): Locator? {
        if (serverPositionTicks <= 0L) return null

        val progression = (serverPositionTicks.toDouble() / VIRTUAL_TICKS_TOTAL).coerceIn(0.0, 1.0)

        val positions =
            try {
                pub.positions()
            } catch (e: Exception) {
                Timber.w(e, "Could not read publication positions")
                return null
            }
        if (positions.isEmpty()) return null

        val index = (positions.size * progression).toInt().coerceIn(0, positions.lastIndex)
        return positions[index]
    }

    private fun initDivinaNavigator(
        pub: Publication,
        initialLocator: Locator?,
        savedInstanceState: Bundle?,
    ) {
        supportFragmentManager.fragmentFactory =
            ImageNavigatorFragment.createFactory(
                publication = pub,
                initialLocator = initialLocator,
                listener = object : ImageNavigatorFragment.Listener {},
            )

        val fragment =
            if (savedInstanceState == null) {
                val created =
                    supportFragmentManager.fragmentFactory.instantiate(
                        classLoader,
                        ImageNavigatorFragment::class.java.name,
                    ) as ImageNavigatorFragment
                supportFragmentManager
                    .beginTransaction()
                    .replace(binding.fragmentContainer.id, created, TAG_NAVIGATOR)
                    .commit()
                created
            } else {
                supportFragmentManager.findFragmentByTag(TAG_NAVIGATOR) as? ImageNavigatorFragment
                    ?: (supportFragmentManager.fragmentFactory.instantiate(
                            classLoader,
                            ImageNavigatorFragment::class.java.name,
                        ) as ImageNavigatorFragment)
                        .also { created ->
                            supportFragmentManager
                                .beginTransaction()
                                .replace(binding.fragmentContainer.id, created, TAG_NAVIGATOR)
                                .commit()
                        }
            }

        this.navigator = fragment
        setupInputListener(fragment)
        observeNavigator()
    }

    private fun initEpubNavigator(
        pub: Publication,
        initialLocator: Locator?,
        savedInstanceState: Bundle?,
    ) {
        val navigatorFactory = EpubNavigatorFactory(pub)
        supportFragmentManager.fragmentFactory =
            navigatorFactory.createFragmentFactory(
                initialLocator = initialLocator,
                initialPreferences = buildEpubPreferences(),
                listener =
                    object : EpubNavigatorFragment.Listener {
                        override fun onExternalLinkActivated(url: AbsoluteUrl) {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
                            }
                        }
                    },
            )

        val fragment =
            if (savedInstanceState == null) {
                val created =
                    supportFragmentManager.fragmentFactory.instantiate(
                        classLoader,
                        EpubNavigatorFragment::class.java.name,
                    ) as EpubNavigatorFragment
                supportFragmentManager
                    .beginTransaction()
                    .replace(binding.fragmentContainer.id, created, TAG_NAVIGATOR)
                    .commit()
                created
            } else {
                supportFragmentManager.findFragmentByTag(TAG_NAVIGATOR) as? EpubNavigatorFragment
                    ?: (supportFragmentManager.fragmentFactory.instantiate(
                            classLoader,
                            EpubNavigatorFragment::class.java.name,
                        ) as EpubNavigatorFragment)
                        .also { created ->
                            supportFragmentManager
                                .beginTransaction()
                                .replace(binding.fragmentContainer.id, created, TAG_NAVIGATOR)
                                .commit()
                        }
            }

        this.navigator = fragment
        setupInputListener(fragment)
        observeNavigator()
    }

    private fun setupInputListener(nav: OverflowableNavigator) {
        nav.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    toggleControls()
                    return true
                }
            }
        )
    }

    private fun observeNavigator() {
        val nav = navigator ?: return
        locatorJob?.cancel()
        locatorJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                coroutineScope {
                    // Keep the UI and the locally persisted locator in sync immediately:
                    // the local position is what powers instant resume.
                    launch { nav.currentLocator.collect { locator -> onLocatorChanged(locator) } }
                    // Debounce the server sync so a long read does not send one request
                    // per page turn (or per scroll step in continuous mode).
                    launch {
                        nav.currentLocator
                            .distinctUntilChangedBy { it.locations.totalProgression }
                            .debounce(PLAYBACK_PROGRESS_DEBOUNCE_MS)
                            .collect { locator -> postProgress(locator) }
                    }
                }
            }
        }
    }

    private fun onLocatorChanged(locator: Locator) {
        val pub = publication
        if (pub != null && pub.conformsTo(Publication.Profile.DIVINA)) {
            val totalPages = pub.readingOrder.size
            val currentPage =
                locator.locations.position
                    ?: pub.readingOrder.indexOfFirstWithHref(locator.href)?.let { it + 1 }
                    ?: if (totalPages > 0) {
                        val progression = locator.locations.totalProgression ?: 0.0
                        ((progression * totalPages).toInt() + 1).coerceIn(1, totalPages)
                    } else {
                        1
                    }
            binding.textProgress.text = "$currentPage/$totalPages"
        } else {
            val progression = locator.locations.totalProgression ?: 0.0
            val percent = (progression * 100).toInt()
            binding.textProgress.text = "$percent%"
        }

        locator.title?.let { if (it.isNotBlank()) binding.textChapterTitle.text = it }

        // Persist local reading position
        appPreferences.sharedPreferences
            .edit()
            .putString(KEY_LOCATOR_PREFIX + bookId, locator.toJSON().toString())
            .apply()
    }

    private suspend fun postProgress(locator: Locator) {
        val progression = locator.locations.totalProgression ?: 0.0
        val ticks = (progression * VIRTUAL_TICKS_TOTAL).toLong()
        try {
            jellyfinRepository.postPlaybackProgress(bookUUID, ticks, isPaused = false)
        } catch (e: Exception) {
            Timber.w(e, "Failed to post playback progress")
        }
    }

    private fun showTableOfContents() {
        val pub = publication ?: return
        val toc =
            if (pub.tableOfContents.isNotEmpty()) {
                pub.tableOfContents
            } else if (pub.conformsTo(Publication.Profile.DIVINA)) {
                pub.readingOrder.mapIndexed { index, link ->
                    link.copy(title = link.title ?: "Page ${index + 1}")
                }
            } else {
                emptyList()
            }

        if (toc.isEmpty()) {
            Toast.makeText(this, getString(CoreR.string.no_toc_available), Toast.LENGTH_SHORT)
                .show()
            return
        }

        val flattenedLinks = mutableListOf<Link>()
        fun flatten(links: List<Link>) {
            for (link in links) {
                flattenedLinks.add(link)
                flatten(link.children)
            }
        }
        flatten(toc)

        val titles =
            flattenedLinks
                .map { it.title?.ifBlank { it.href.toString() } ?: it.href.toString() }
                .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(CoreR.string.table_of_contents))
            .setItems(titles) { _, which ->
                val selectedLink = flattenedLinks[which]
                navigator?.go(selectedLink, animated = true)
            }
            .setNegativeButton(getString(CoreR.string.cancel), null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        navigator?.currentLocator?.value?.let { locator ->
            val progression = locator.locations.totalProgression ?: 0.0
            val ticks = (progression * VIRTUAL_TICKS_TOTAL).toLong()
            val percent = (progression * 100).toInt()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    jellyfinRepository.postPlaybackStop(bookUUID, ticks, percent)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to post playback stop")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locatorJob?.cancel()
        // Close the publication only after the container view has detached. Fragment teardown
        // happens inside super.onDestroy(), but the Readium navigator runs coroutines that can
        // still touch the publication while it is being closed.
        binding.fragmentContainer.post { publication?.close() }
    }

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        const val EXTRA_BOOK_CONTAINER = "extra_book_container"
        private const val TAG_NAVIGATOR = "reader_navigator"
        private const val KEY_LOCATOR_PREFIX = "pref_book_locator_"
        private const val VIRTUAL_TICKS_TOTAL = 1_000_000_000L

        /**
         * How long to wait after the last locator change before reporting progress to the server.
         * The local position is written on every change; only the network sync is debounced so a
         * long read does not produce one request per page.
         */
        private const val PLAYBACK_PROGRESS_DEBOUNCE_MS = 5_000L

        fun createIntent(
            context: Context,
            bookId: UUID,
            bookTitle: String,
            container: String? = null,
        ): Intent {
            return Intent(context, BookReaderActivity::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId.toString())
                putExtra(EXTRA_BOOK_TITLE, bookTitle)
                if (container != null) {
                    putExtra(EXTRA_BOOK_CONTAINER, container)
                }
            }
        }
    }
}
