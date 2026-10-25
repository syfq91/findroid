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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
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
import java.nio.charset.StandardCharsets
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
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression as PubReadingProgression
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.FormatHints
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.readium.r2.streamer.parser.image.ImageParser
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
    private var positionsByReadingOrder: List<List<Locator>> = emptyList()
    private var epubChapterHref: Url? = null
    private var epubChapterPage: Int = 1
    private var epubChapterTotalPages: Int = 1
    private var serverRuntimeTicks: Long = 0L

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
        binding.statusTitle.text = bookTitle
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

        binding.readerStatusBar.setOnClickListener { toggleControls() }

        binding.controlsOverlay.setOnClickListener { toggleControls() }
        binding.topBar.setOnClickListener { /* Consume clicks to prevent dismissing controls */ }
        binding.bottomBar.setOnClickListener { /* Consume clicks to prevent dismissing controls */ }

        val baseHorizontalPadding = (16 * resources.displayMetrics.density).toInt()
        val baseBottomPadding = (6 * resources.displayMetrics.density).toInt()
        val baseTopPadding = (4 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.readerStatusBar) { v, insets ->
            val navBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or
                        WindowInsetsCompat.Type.displayCutout()
                )
            v.updatePadding(
                left = baseHorizontalPadding + navBars.left,
                right = baseHorizontalPadding + navBars.right,
                top = baseTopPadding,
                bottom = maxOf(baseBottomPadding, navBars.bottom),
            )
            insets
        }
    }

    private fun toggleControls() {
        val willShow = !binding.controlsOverlay.isVisible
        binding.controlsOverlay.isVisible = willShow
        binding.readerStatusBar.isVisible = !willShow
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
            binding.readerStatusBar.layoutDirection = View.LAYOUT_DIRECTION_LTR
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

        val isDivina = publication?.conformsTo(Publication.Profile.DIVINA) == true
        if (isDivina) {
            binding.readerStatusBar.setBackgroundColor(Color.parseColor("#B3000000"))
            val textColor = Color.parseColor("#D9FFFFFF")
            binding.statusClock.setTextColor(textColor)
            binding.statusTitle.setTextColor(textColor)
            binding.statusPageCount.setTextColor(textColor)
        } else {
            val (bgColor, textColor) =
                when (themeStr) {
                    "light" -> Color.parseColor("#E6FAFAFA") to Color.parseColor("#8A000000")
                    "sepia" -> Color.parseColor("#E6FBF0D9") to Color.parseColor("#8A5B4636")
                    else -> Color.parseColor("#E6121212") to Color.parseColor("#8AFFFFFF")
                }
            binding.readerStatusBar.setBackgroundColor(bgColor)
            binding.statusClock.setTextColor(textColor)
            binding.statusTitle.setTextColor(textColor)
            binding.statusPageCount.setTextColor(textColor)
        }
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
        epubChapterHref = null
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
            binding.readerStatusBar.isVisible = false
            try {
                val prepared = prepareBookFile()
                openPublication(prepared, savedInstanceState)
                try {
                    jellyfinRepository.postPlaybackStart(bookUUID)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to post playback start")
                }
                binding.loadingLayout.isVisible = false
                if (!binding.controlsOverlay.isVisible) {
                    binding.readerStatusBar.isVisible = true
                }
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

    private suspend fun prepareBookFile(): PreparedBook =
        withContext(Dispatchers.IO) {
            var container = intent.getStringExtra(EXTRA_BOOK_CONTAINER)
            var serverPositionTicks = 0L
            var serverRuntimeTicks = 0L
            try {
                val book = jellyfinRepository.getBook(bookUUID)
                serverPositionTicks = book.playbackPositionTicks
                serverRuntimeTicks = book.runtimeTicks
                if (container.isNullOrBlank()) {
                    container = book.container
                }
                val localSource = book.sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                if (localSource != null) {
                    val localFile = File(localSource.path)
                    if (localFile.exists() && localFile.length() > 0) {
                        Timber.d("Using downloaded local book file: ${localFile.absolutePath}")
                        val detected = detectBookFormat(localFile, container)
                        val ext =
                            container?.lowercase()?.removePrefix(".")?.takeIf {
                                it in listOf("cbz", "cbr", "epub", "pdf")
                            } ?: detected
                        val hints = buildFormatHints(ext)
                        return@withContext PreparedBook(
                            file = localFile,
                            serverPositionTicks = serverPositionTicks,
                            serverRuntimeTicks = serverRuntimeTicks,
                            formatHints = hints,
                        )
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
                val detected = detectBookFormat(existing, container)
                val targetExt =
                    container?.lowercase()?.removePrefix(".")?.takeIf {
                        it in listOf("cbz", "cbr", "epub", "pdf")
                    } ?: detected
                val resolvedFile =
                    if (
                        existing.extension.lowercase() != targetExt &&
                            (targetExt in listOf("cbz", "cbr", "epub", "pdf"))
                    ) {
                        val corrected = File(booksDir, "$bookId.$targetExt")
                        safeMoveOrCopy(existing, corrected)
                    } else {
                        existing
                    }
                val finalExt = resolvedFile.extension.lowercase().ifBlank { targetExt }
                val hints = buildFormatHints(finalExt)
                return@withContext PreparedBook(
                    file = resolvedFile,
                    serverPositionTicks = serverPositionTicks,
                    serverRuntimeTicks = serverRuntimeTicks,
                    formatHints = hints,
                )
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

            val detected = detectBookFormat(tempFile, container)
            val finalExt =
                container?.lowercase()?.removePrefix(".")?.takeIf {
                    it in listOf("cbz", "cbr", "epub", "pdf")
                } ?: detected

            val targetFile = File(booksDir, "$bookId.$finalExt")
            val resolvedFile = safeMoveOrCopy(tempFile, targetFile)
            val hints = buildFormatHints(finalExt)
            PreparedBook(
                file = resolvedFile,
                serverPositionTicks = serverPositionTicks,
                serverRuntimeTicks = serverRuntimeTicks,
                formatHints = hints,
            )
        }

    /**
     * The resolved book file plus the reading position and runtime the server has for it, in ticks.
     */
    private data class PreparedBook(
        val file: File,
        val serverPositionTicks: Long,
        val serverRuntimeTicks: Long = 0L,
        val formatHints: FormatHints? = null,
    )

    private suspend fun openPublication(prepared: PreparedBook, savedInstanceState: Bundle?) {
        this.serverRuntimeTicks = prepared.serverRuntimeTicks
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
            streamer.open(asset, allowUserInteraction = false).getOrElse { error ->
                if (
                    prepared.file.extension.equals("cbz", ignoreCase = true) ||
                        prepared.formatHints?.hasFileExtension("cbz") == true
                ) {
                    val fallbackOpener =
                        PublicationOpener(ImageParser(assetRetriever = assetRetriever))
                    fallbackOpener.open(asset, allowUserInteraction = false).getOrElse {
                        throw IOException("Failed to open book: $error")
                    }
                } else {
                    throw IOException("Failed to open book: $error")
                }
            }
        this.publication = pub
        if (bookTitle.isBlank()) {
            pub.metadata.title
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    bookTitle = it
                    binding.textBookTitle.text = bookTitle
                    binding.statusTitle.text = bookTitle
                }
        }
        updateBackgroundTheme()
        this.positionsByReadingOrder =
            if (!pub.conformsTo(Publication.Profile.DIVINA)) {
                try {
                    pub.positionsByReadingOrder()
                } catch (e: Exception) {
                    Timber.w(e, "Could not load positionsByReadingOrder")
                    emptyList()
                }
            } else {
                emptyList()
            }

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
        if (localLocator != null) {
            val matchingIndex = pub.readingOrder.indexOfFirstWithHref(localLocator.href)
            if (matchingIndex != null && matchingIndex >= 0) {
                return localLocator
            }
        }

        return locatorFromServerProgress(pub, serverPositionTicks)
    }

    /**
     * Derives a locator from the server's saved position.
     *
     * For EPUB, Jellyfin uses 10,000,000 ticks (1s) and position is proportion of that.
     * For Divina (CBZ/CBR/PDF), Jellyfin uses pageCount * 10,000 ticks.
     * If the server position exceeds runtime ticks but is within the legacy 1,000,000,000 scale,
     * it falls back to the legacy scale.
     */
    private suspend fun locatorFromServerProgress(
        pub: Publication,
        serverPositionTicks: Long,
    ): Locator? {
        if (serverPositionTicks <= 0L) return null

        val totalTicks = calculateTotalTicks(pub, serverRuntimeTicks)
        val progression = calculateProgressionFromServerTicks(serverPositionTicks, totalTicks)

        val positions =
            try {
                pub.positions()
            } catch (e: Exception) {
                Timber.w(e, "Could not read publication positions")
                return null
            }
        if (positions.isEmpty()) {
            if (pub.conformsTo(Publication.Profile.DIVINA) && pub.readingOrder.isNotEmpty()) {
                val index =
                    (pub.readingOrder.size * progression)
                        .toInt()
                        .coerceIn(0, pub.readingOrder.lastIndex)
                val link = pub.readingOrder[index]
                return Locator(
                    href = link.href,
                    mediaType = link.mediaType ?: MediaType.BINARY,
                    locations =
                        Locator.Locations(
                            position = index + 1,
                            totalProgression =
                                (index.toDouble() / pub.readingOrder.size).coerceIn(0.0, 1.0),
                        ),
                )
            }
            return null
        }

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
        val paginationListener =
            object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(
                    pageIndex: Int,
                    totalPages: Int,
                    locator: Locator,
                ) {
                    val isScroll = appPreferences.getValue(appPreferences.readerScroll)
                    if (!isScroll) {
                        epubChapterHref = locator.href.removeFragment().removeQuery()
                        epubChapterPage = pageIndex + 1
                        epubChapterTotalPages = totalPages.coerceAtLeast(1)
                        val progressText = "$epubChapterPage/$epubChapterTotalPages"
                        binding.textProgress.text = progressText
                        binding.statusPageCount.text = progressText
                        val title =
                            resolveChapterTitle(
                                locator = locator,
                                tableOfContents = pub.tableOfContents,
                                readingOrder = pub.readingOrder,
                                bookTitle = bookTitle,
                            )
                        binding.statusTitle.text = title
                        if (title != bookTitle) {
                            binding.textChapterTitle.text = title
                        }
                    }
                }
            }

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
                paginationListener = paginationListener,
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
                (supportFragmentManager.findFragmentByTag(TAG_NAVIGATOR) as? EpubNavigatorFragment)
                    ?.also { existing ->
                        try {
                            val field =
                                EpubNavigatorFragment::class
                                    .java
                                    .getDeclaredField("paginationListener")
                            field.isAccessible = true
                            field.set(existing, paginationListener)
                        } catch (_: Exception) {}
                    }
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
            DirectionalNavigationAdapter(
                navigator = nav,
                animatedTransition = true,
            )
        )
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
                            .distinctUntilChangedBy {
                                calculateProgressTicks(it, publication, serverRuntimeTicks).first
                            }
                            .debounce(PLAYBACK_PROGRESS_DEBOUNCE_MS)
                            .collect { locator -> postProgress(locator) }
                    }
                }
            }
        }
    }

    private fun onLocatorChanged(locator: Locator) {
        val pub = publication
        val progressText =
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
                "$currentPage/$totalPages"
            } else {
                val chapterIndex = pub?.readingOrder?.indexOfFirstWithHref(locator.href)
                val isScroll = appPreferences.getValue(appPreferences.readerScroll)
                val (currentPage, totalPages) =
                    calculateEpubChapterProgress(
                        locator = locator,
                        readingOrderIndex = chapterIndex,
                        positionsByReadingOrder = positionsByReadingOrder,
                        cachedChapterHref = epubChapterHref,
                        cachedPage = epubChapterPage,
                        cachedTotalPages = epubChapterTotalPages,
                        isScrollMode = isScroll,
                    )
                "$currentPage/$totalPages"
            }
        binding.textProgress.text = progressText
        binding.statusPageCount.text = progressText

        val title =
            resolveChapterTitle(
                locator = locator,
                tableOfContents = pub?.tableOfContents ?: emptyList(),
                readingOrder = pub?.readingOrder ?: emptyList(),
                bookTitle = bookTitle,
            )
        binding.statusTitle.text = title
        if (title != bookTitle) {
            binding.textChapterTitle.text = title
        }

        // Persist local reading position
        appPreferences.sharedPreferences
            .edit()
            .putString(KEY_LOCATOR_PREFIX + bookId, locator.toJSON().toString())
            .apply()
    }

    private suspend fun postProgress(locator: Locator) {
        val (ticks, _) = calculateProgressTicks(locator, publication, serverRuntimeTicks)
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
            val (ticks, progression) = calculateProgressTicks(locator, publication, serverRuntimeTicks)
            val percent = (progression * 100).toInt().coerceIn(0, 100)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    jellyfinRepository.postPlaybackStop(
                        itemId = bookUUID,
                        positionTicks = ticks,
                        playedPercentage = percent,
                        isBook = true,
                    )
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
        const val TICKS_PER_PAGE = 10_000L
        const val JELLYFIN_EPUB_TICKS_TOTAL = 10_000_000L
        const val LEGACY_VIRTUAL_TICKS_TOTAL = 1_000_000_000L

        /**
         * Calculates total ticks used for progress reporting and tracking.
         * For EPUB, Jellyfin standardizes RunTimeTicks as 10,000,000L (1 second).
         * For Divina (CBZ/CBR/PDF), Jellyfin standardizes RunTimeTicks as pageCount * 10,000L.
         */
        internal fun calculateTotalTicks(pub: Publication?, serverRuntimeTicks: Long = 0L): Long {
            if (serverRuntimeTicks > 0L) {
                return serverRuntimeTicks
            }
            if (pub?.conformsTo(Publication.Profile.DIVINA) == true && pub.readingOrder.isNotEmpty()) {
                return pub.readingOrder.size * TICKS_PER_PAGE
            }
            return JELLYFIN_EPUB_TICKS_TOTAL
        }

        /**
         * Calculates playback position ticks and normalized progression for a locator.
         */
        internal fun calculateProgressTicks(
            locator: Locator,
            pub: Publication?,
            serverRuntimeTicks: Long = 0L,
        ): Pair<Long, Double> {
            val totalTicks = calculateTotalTicks(pub, serverRuntimeTicks)
            if (pub?.conformsTo(Publication.Profile.DIVINA) == true) {
                val totalPages = pub.readingOrder.size
                val pageIndex =
                    locator.locations.position?.let { it - 1 }
                        ?: pub.readingOrder.indexOfFirstWithHref(locator.href).takeIf { it >= 0 }
                        ?: if (totalPages > 0) {
                            ((locator.locations.totalProgression ?: 0.0) * totalPages).toInt()
                        } else {
                            0
                        }
                val clampedIndex = if (totalPages > 0) pageIndex.coerceIn(0, totalPages - 1) else 0
                val progression =
                    if (totalPages > 0) {
                        (clampedIndex.toDouble() / totalPages).coerceIn(0.0, 1.0)
                    } else {
                        0.0
                    }
                val ticks =
                    if (totalTicks == totalPages * TICKS_PER_PAGE) {
                        clampedIndex * TICKS_PER_PAGE
                    } else if (totalPages > 0) {
                        (progression * totalTicks).toLong()
                    } else {
                        0L
                    }
                return Pair(ticks.coerceIn(0L, totalTicks), progression)
            } else {
                val progression = (locator.locations.totalProgression ?: 0.0).coerceIn(0.0, 1.0)
                val ticks = (progression * totalTicks).toLong().coerceIn(0L, totalTicks)
                return Pair(ticks, progression)
            }
        }

        /**
         * Calculates a normalized progression (0.0 to 1.0) from the server's position ticks.
         * Handles legacy virtual ticks (1,000,000,000L scale) gracefully if present on older records.
         */
        internal fun calculateProgressionFromServerTicks(
            serverPositionTicks: Long,
            totalTicks: Long,
        ): Double {
            if (serverPositionTicks <= 0L) return 0.0
            return if (
                serverPositionTicks > totalTicks &&
                    totalTicks > 0L &&
                    serverPositionTicks <= LEGACY_VIRTUAL_TICKS_TOTAL
            ) {
                (serverPositionTicks.toDouble() / LEGACY_VIRTUAL_TICKS_TOTAL).coerceIn(0.0, 1.0)
            } else if (totalTicks > 0L) {
                (serverPositionTicks.toDouble() / totalTicks).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        }

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

        internal fun detectBookFormat(file: File, containerHint: String? = null): String {
            val header = ByteArray(7)
            try {
                FileInputStream(file).use { input -> input.read(header) }
            } catch (_: Exception) {}

            if (
                header.size >= 4 &&
                    header[0] == 0x25.toByte() &&
                    header[1] == 0x50.toByte() &&
                    header[2] == 0x44.toByte() &&
                    header[3] == 0x46.toByte()
            ) {
                return "pdf"
            }

            if (
                header.size >= 4 &&
                    header[0] == 0x52.toByte() &&
                    header[1] == 0x61.toByte() &&
                    header[2] == 0x72.toByte() &&
                    header[3] == 0x21.toByte()
            ) {
                return "cbr"
            }

            val comicImageExtensions =
                setOf(
                    "jpg",
                    "jpeg",
                    "png",
                    "webp",
                    "avif",
                    "gif",
                    "bmp",
                    "dib",
                    "tif",
                    "tiff",
                    "jxl",
                )

            // Try opening as ZIP. Use UTF-8 first, and fall back to ISO-8859-1 for non-UTF8 entry
            // encodings (e.g. Shift-JIS manga).
            val zipFile =
                try {
                    ZipFile(file, StandardCharsets.UTF_8)
                } catch (_: Exception) {
                    try {
                        ZipFile(file, StandardCharsets.ISO_8859_1)
                    } catch (_: Exception) {
                        null
                    }
                }

            zipFile?.use { zip ->
                // 1. Strict EPUB verification:
                // Standard EPUB must contain META-INF/container.xml
                if (zip.getEntry("META-INF/container.xml") != null) {
                    return "epub"
                }
                // If it has a mimetype entry, check if its content is application/epub+zip
                val mimetypeEntry = zip.getEntry("mimetype")
                if (mimetypeEntry != null) {
                    try {
                        val mimeContent =
                            zip.getInputStream(mimetypeEntry).use {
                                it.bufferedReader(StandardCharsets.US_ASCII).readText().trim()
                            }
                        if (mimeContent.startsWith("application/epub+zip", ignoreCase = true)) {
                            return "epub"
                        }
                        if (
                            mimeContent.startsWith(
                                "application/vnd.comicbook+zip",
                                ignoreCase = true,
                            ) || mimeContent.startsWith("application/x-cbz", ignoreCase = true)
                        ) {
                            return "cbz"
                        }
                    } catch (_: Exception) {}
                }

                // 2. Comic verification:
                // ComicRack / Komga metadata or comic images
                if (zip.getEntry("ComicInfo.xml") != null) {
                    return "cbz"
                }

                val hasImages =
                    zip.entries().asSequence().any { entry ->
                        if (entry.isDirectory) return@any false
                        val ext = entry.name.substringAfterLast('.', "").lowercase()
                        ext in comicImageExtensions
                    }
                if (hasImages) {
                    return "cbz"
                }
            }

            // 3. Fallback based on hint or file extension
            val normalizedHint = containerHint?.lowercase()?.removePrefix(".")
            if (normalizedHint in listOf("cbz", "cbr", "epub", "pdf")) {
                return normalizedHint!!
            }
            if (normalizedHint == "zip") {
                return "cbz"
            }

            val fileExt = file.extension.lowercase()
            if (fileExt in listOf("cbz", "cbr", "epub", "pdf")) {
                return fileExt
            }

            return "epub"
        }

        internal fun safeMoveOrCopy(source: File, target: File): File {
            if (source.absolutePath == target.absolutePath) return source
            if (target.exists()) {
                target.delete()
            }
            if (source.renameTo(target)) {
                return target
            }
            try {
                source.copyTo(target, overwrite = true)
                source.delete()
                if (target.exists() && target.length() > 0) {
                    return target
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to copy file from ${source.name} to ${target.name}")
            }
            return source
        }

        internal fun buildFormatHints(ext: String): FormatHints {
            val mediaType =
                when (ext.lowercase()) {
                    "cbz" -> MediaType.CBZ
                    "epub" -> MediaType.EPUB
                    "pdf" -> MediaType.PDF
                    else -> null
                }
            return FormatHints(mediaTypes = listOfNotNull(mediaType)).addFileExtension(ext)
        }

        internal fun calculateEpubChapterProgress(
            locator: Locator,
            readingOrderIndex: Int?,
            positionsByReadingOrder: List<List<Locator>>,
            cachedChapterHref: Url? = null,
            cachedPage: Int? = null,
            cachedTotalPages: Int? = null,
            isScrollMode: Boolean = false,
        ): Pair<Int, Int> {
            val normalizedHref = locator.href.removeFragment().removeQuery()
            val totalPages =
                if (
                    !isScrollMode &&
                        cachedChapterHref == normalizedHref &&
                        cachedTotalPages != null &&
                        cachedTotalPages > 0
                ) {
                    cachedTotalPages
                } else if (
                    readingOrderIndex != null &&
                        readingOrderIndex in positionsByReadingOrder.indices
                ) {
                    positionsByReadingOrder[readingOrderIndex].size.coerceAtLeast(1)
                } else {
                    1
                }
            val currentPage =
                if (
                    !isScrollMode &&
                        cachedChapterHref == normalizedHref &&
                        cachedPage != null &&
                        cachedPage in 1..totalPages
                ) {
                    cachedPage
                } else {
                    val progression = locator.locations.progression ?: 0.0
                    ((progression + 1e-5) * totalPages).toInt().coerceIn(0, totalPages - 1) + 1
                }
            return currentPage to totalPages
        }

        private fun findExactTitleInToc(links: List<Link>, targetHref: Url): String? {
            for (link in links) {
                if (link.url() == targetHref && !link.title.isNullOrBlank()) {
                    return link.title!!.trim()
                }
                val childResult = findExactTitleInToc(link.children, targetHref)
                if (!childResult.isNullOrBlank()) {
                    return childResult
                }
            }
            return null
        }

        private fun findNormalizedTitleInToc(links: List<Link>, targetNormalized: Url): String? {
            for (link in links) {
                if (
                    link.url().removeFragment().removeQuery() == targetNormalized &&
                        !link.title.isNullOrBlank()
                ) {
                    return link.title!!.trim()
                }
                val childResult = findNormalizedTitleInToc(link.children, targetNormalized)
                if (!childResult.isNullOrBlank()) {
                    return childResult
                }
            }
            return null
        }

        internal fun findTitleInToc(links: List<Link>, targetHref: Url): String? {
            val exactResult = findExactTitleInToc(links, targetHref)
            if (!exactResult.isNullOrBlank()) {
                return exactResult
            }
            val targetNormalized = targetHref.removeFragment().removeQuery()
            return findNormalizedTitleInToc(links, targetNormalized)
        }

        internal fun resolveChapterTitle(
            locator: Locator?,
            tableOfContents: List<Link> = emptyList(),
            readingOrder: List<Link> = emptyList(),
            bookTitle: String = "",
        ): String {
            val directTitle = locator?.title?.trim()
            if (!directTitle.isNullOrEmpty()) {
                return directTitle
            }

            if (locator != null) {
                val tocTitle = findTitleInToc(tableOfContents, locator.href)
                if (!tocTitle.isNullOrBlank()) {
                    return tocTitle
                }

                val targetNormalized = locator.href.removeFragment().removeQuery()
                val readingOrderItem = readingOrder.firstOrNull {
                    it.url().removeFragment().removeQuery() == targetNormalized
                }
                val readingOrderTitle = readingOrderItem?.title?.trim()
                if (!readingOrderTitle.isNullOrEmpty()) {
                    return readingOrderTitle
                }
            }

            return bookTitle.trim()
        }
    }
}
