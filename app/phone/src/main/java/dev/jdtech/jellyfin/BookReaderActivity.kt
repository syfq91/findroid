package dev.jdtech.jellyfin

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
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
import dev.jdtech.jellyfin.presentation.book.components.ReaderAppearanceSheet
import dev.jdtech.jellyfin.presentation.book.components.ReaderPreferencesState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.asset.FileAsset
import org.readium.r2.streamer.Streamer
import timber.log.Timber

@AndroidEntryPoint
class BookReaderActivity : AppCompatActivity() {

    @Inject lateinit var jellyfinRepository: JellyfinRepository
    @Inject lateinit var appPreferences: AppPreferences

    private lateinit var binding: ActivityBookReaderBinding
    private lateinit var bookId: String
    private lateinit var bookUUID: UUID
    private var bookTitle: String = ""

    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityBookReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run {
            finish()
            return
        }
        bookUUID = UUID.fromString(bookId)
        bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()

        binding.textBookTitle.text = bookTitle
        applyOrientation()
        updateBackgroundTheme()
        setupControls()

        loadBookAndOpen(savedInstanceState)
    }

    private fun setupControls() {
        binding.buttonBack.setOnClickListener {
            finish()
        }

        binding.buttonAppearance.setOnClickListener {
            showAppearanceDialog()
        }

        binding.buttonToc.setOnClickListener {
            showTableOfContents()
        }

        binding.buttonPrevPage.setOnClickListener {
            navigator?.goBackward(animated = true)
        }

        binding.buttonNextPage.setOnClickListener {
            navigator?.goForward(animated = true)
        }
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

    private fun applyOrientation() {
        requestedOrientation = when (appPreferences.getValue(appPreferences.readerOrientation)) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    private fun updateBackgroundTheme() {
        val themeStr = appPreferences.getValue(appPreferences.readerTheme)
        val color = when (themeStr) {
            "light" -> Color.parseColor("#FAFAFA")
            "sepia" -> Color.parseColor("#FBF0D9")
            else -> Color.parseColor("#121212")
        }
        binding.readerRoot.setBackgroundColor(color)
    }

    private fun buildEpubPreferences(): EpubPreferences {
        val theme = when (appPreferences.getValue(appPreferences.readerTheme)) {
            "light" -> Theme.LIGHT
            "sepia" -> Theme.SEPIA
            else -> Theme.DARK
        }
        val fontFamily = when (appPreferences.getValue(appPreferences.readerFontFamily)) {
            "serif" -> FontFamily.SERIF
            "sans" -> FontFamily.SANS_SERIF
            "opendyslexic" -> FontFamily.OPEN_DYSLEXIC
            else -> null
        }
        val fontSize = appPreferences.getValue(appPreferences.readerFontSize).toDouble()
        val scroll = appPreferences.getValue(appPreferences.readerScroll)

        return EpubPreferences(
            theme = theme,
            fontFamily = fontFamily,
            fontSize = fontSize,
            scroll = scroll,
        )
    }

    private fun applyPreferences() {
        val epubPrefs = buildEpubPreferences()
        navigator?.submitPreferences(epubPrefs)
        updateBackgroundTheme()
    }

    private fun showAppearanceDialog() {
        val bottomSheet = BottomSheetDialog(this)
        val initialPrefs = ReaderPreferencesState(
            fontSize = appPreferences.getValue(appPreferences.readerFontSize),
            fontFamily = appPreferences.getValue(appPreferences.readerFontFamily),
            theme = appPreferences.getValue(appPreferences.readerTheme),
            scroll = appPreferences.getValue(appPreferences.readerScroll),
            orientation = appPreferences.getValue(appPreferences.readerOrientation),
        )

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
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
                            appPreferences.setValue(appPreferences.readerOrientation, newOrientation)
                            applyOrientation()
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
                val bookFile = prepareBookFile()
                openPublication(bookFile, savedInstanceState)
                binding.loadingLayout.isVisible = false
            } catch (e: Exception) {
                Timber.e(e, "Error opening book $bookId")
                binding.loadingLayout.isVisible = false
                Toast.makeText(
                    this@BookReaderActivity,
                    e.localizedMessage ?: getString(CoreR.string.error_opening_book),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private suspend fun prepareBookFile(): File = withContext(Dispatchers.IO) {
        val booksDir = File(cacheDir, "books").apply { mkdirs() }
        val targetFile = File(booksDir, "$bookId.epub")

        if (targetFile.exists() && targetFile.length() > 0) {
            return@withContext targetFile
        }

        val downloadUrl = jellyfinRepository.getDownloadUrl(bookUUID)
        val tempFile = File(booksDir, "$bookId.epub.tmp")

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            connect()
        }

        if (connection.responseCode in 200..299) {
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
            targetFile
        } else {
            tempFile.delete()
            throw IOException("Download failed with HTTP ${connection.responseCode}")
        }
    }

    private fun openPublication(file: File, savedInstanceState: Bundle?) {
        val streamer = Streamer(this@BookReaderActivity)
        val pub = streamer.open(FileAsset(file), allowUserInteraction = false).getOrThrow()
        this.publication = pub

        val savedLocatorJson = appPreferences.sharedPreferences.getString(KEY_LOCATOR_PREFIX + bookId, null)
        val initialLocator = savedLocatorJson?.let {
            try {
                Locator.fromJSON(JSONObject(it))
            } catch (e: Exception) {
                Timber.e(e, "Error parsing saved locator")
                null
            }
        }

        val navigatorFactory = EpubNavigatorFactory(pub)
        supportFragmentManager.fragmentFactory = navigatorFactory.createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = buildEpubPreferences(),
            listener = object : EpubNavigatorFragment.Listener {
                override fun onTap(point: PointF): Boolean {
                    toggleControls()
                    return true
                }
            }
        )

        val fragment = if (savedInstanceState == null) {
            val created = supportFragmentManager.fragmentFactory.instantiate(
                classLoader,
                EpubNavigatorFragment::class.java.name
            ) as EpubNavigatorFragment
            supportFragmentManager.beginTransaction()
                .replace(binding.fragmentContainer.id, created, TAG_NAVIGATOR)
                .commit()
            created
        } else {
            supportFragmentManager.findFragmentByTag(TAG_NAVIGATOR) as? EpubNavigatorFragment
        }

        this.navigator = fragment
        observeNavigator()
    }

    private fun observeNavigator() {
        val nav = navigator ?: return

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                nav.currentLocator.collect { locator ->
                    onLocatorChanged(locator)
                }
            }
        }
    }

    private fun onLocatorChanged(locator: Locator) {
        val progression = locator.locations.totalProgression ?: 0.0
        val percent = (progression * 100).toInt()
        binding.textProgress.text = "$percent%"

        locator.title?.let {
            if (it.isNotBlank()) binding.textChapterTitle.text = it
        }

        // Persist local reading position
        appPreferences.sharedPreferences.edit()
            .putString(KEY_LOCATOR_PREFIX + bookId, locator.toJSON().toString())
            .apply()

        // Sync with Jellyfin server in background
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ticks = (progression * VIRTUAL_TICKS_TOTAL).toLong()
                jellyfinRepository.postPlaybackProgress(bookUUID, ticks, isPaused = false)
            } catch (e: Exception) {
                Timber.w(e, "Failed to post playback progress")
            }
        }
    }

    private fun showTableOfContents() {
        val pub = publication ?: return
        val toc = pub.tableOfContents

        if (toc.isEmpty()) {
            Toast.makeText(this, getString(CoreR.string.no_toc_available), Toast.LENGTH_SHORT).show()
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

        val titles = flattenedLinks.map { it.title?.ifBlank { it.href } ?: it.href }.toTypedArray()

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
        publication?.close()
    }

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        private const val TAG_NAVIGATOR = "epub_navigator"
        private const val KEY_LOCATOR_PREFIX = "pref_book_locator_"
        private const val VIRTUAL_TICKS_TOTAL = 1_000_000_000L

        fun createIntent(context: Context, bookId: UUID, bookTitle: String): Intent {
            return Intent(context, BookReaderActivity::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId.toString())
                putExtra(EXTRA_BOOK_TITLE, bookTitle)
            }
        }
    }
}
