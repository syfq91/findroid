package dev.jdtech.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubProgressTest {

    private fun createLocator(href: String, progression: Double? = null): Locator {
        val url = Url(href)!!
        return Locator(
            href = url,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(progression = progression),
        )
    }

    @Test
    fun testPaginatedModeUsesCachedPagesWhenChapterMatches() {
        val locator = createLocator("chapter1.xhtml", progression = 0.25)
        val chapterUrl = Url("chapter1.xhtml")!!
        val (page, total) =
            BookReaderActivity.calculateEpubChapterProgress(
                locator = locator,
                readingOrderIndex = 0,
                positionsByReadingOrder = listOf(listOf(locator, locator)),
                cachedChapterHref = chapterUrl,
                cachedPage = 3,
                cachedTotalPages = 10,
                isScrollMode = false,
            )
        assertEquals(3, page)
        assertEquals(10, total)
    }

    @Test
    fun testPaginatedModeNormalizesUrlWithFragmentAndQuery() {
        val locatorWithFragment =
            createLocator("chapter1.xhtml#section2?key=val", progression = 0.25)
        val chapterUrl = Url("chapter1.xhtml")!!
        val (page, total) =
            BookReaderActivity.calculateEpubChapterProgress(
                locator = locatorWithFragment,
                readingOrderIndex = 0,
                positionsByReadingOrder = emptyList(),
                cachedChapterHref = chapterUrl,
                cachedPage = 4,
                cachedTotalPages = 8,
                isScrollMode = false,
            )
        assertEquals(4, page)
        assertEquals(8, total)
    }

    @Test
    fun testFallbackToPositionsByReadingOrderWhenChapterNotCached() {
        val locator = createLocator("chapter2.xhtml", progression = 0.5)
        val dummyChapter0 = listOf(createLocator("c0.xhtml"))
        val dummyChapter1 =
            listOf(
                createLocator("c1.xhtml"),
                createLocator("c1.xhtml"),
                createLocator("c1.xhtml"),
                createLocator("c1.xhtml"),
            )
        val (page, total) =
            BookReaderActivity.calculateEpubChapterProgress(
                locator = locator,
                readingOrderIndex = 1,
                positionsByReadingOrder = listOf(dummyChapter0, dummyChapter1),
                cachedChapterHref = null,
                cachedPage = null,
                cachedTotalPages = null,
                isScrollMode = false,
            )
        assertEquals(4, total)
        assertEquals(3, page)
    }

    @Test
    fun testFallbackAtChapterStartAndEnd() {
        val positions = List(5) { createLocator("c.xhtml") }
        val startLocator = createLocator("c.xhtml", progression = 0.0)
        val (startPage, totalPages) =
            BookReaderActivity.calculateEpubChapterProgress(
                locator = startLocator,
                readingOrderIndex = 0,
                positionsByReadingOrder = listOf(positions),
            )
        assertEquals(1, startPage)
        assertEquals(5, totalPages)

        val endLocator = createLocator("c.xhtml", progression = 1.0)
        val (endPage, _) =
            BookReaderActivity.calculateEpubChapterProgress(
                locator = endLocator,
                readingOrderIndex = 0,
                positionsByReadingOrder = listOf(positions),
            )
        assertEquals(5, endPage)
    }

    @Test
    fun testContinuousScrollModeUsesPositionsRegardlessOfCache() {
        val locator = createLocator("chapter1.xhtml", progression = 0.75)
        val chapterUrl = Url("chapter1.xhtml")!!
        val positions = List(10) { createLocator("chapter1.xhtml") }
        val (page, total) =
            BookReaderActivity.calculateEpubChapterProgress(
                locator = locator,
                readingOrderIndex = 0,
                positionsByReadingOrder = listOf(positions),
                cachedChapterHref = chapterUrl,
                cachedPage = 2,
                cachedTotalPages = 2,
                isScrollMode = true,
            )
        assertEquals(10, total)
        assertEquals(8, page)
    }

    @Test
    fun testEmptyOrInvalidReadingOrderFallback() {
        val locator = createLocator("unknown.xhtml", progression = 0.5)
        val (page, total) =
            BookReaderActivity.calculateEpubChapterProgress(
                locator = locator,
                readingOrderIndex = null,
                positionsByReadingOrder = emptyList(),
            )
        assertEquals(1, page)
        assertEquals(1, total)
    }

    @Test
    fun testResolveChapterTitleUsesLocatorTitleWhenAvailable() {
        val locator =
            Locator(
                href = Url("ch1.xhtml")!!,
                mediaType = MediaType.XHTML,
                title = "Chapter 1: The Beginning",
            )
        val resolved =
            BookReaderActivity.resolveChapterTitle(
                locator = locator,
                bookTitle = "Sample Book",
            )
        assertEquals("Chapter 1: The Beginning", resolved)
    }

    @Test
    fun testResolveChapterTitleFallsBackToToc() {
        val locator =
            Locator(
                href = Url("ch2.xhtml#section1")!!,
                mediaType = MediaType.XHTML,
            )
        val toc =
            listOf(
                Link(href = Url("ch1.xhtml")!!, title = "Chapter 1"),
                Link(href = Url("ch2.xhtml")!!, title = "Chapter 2"),
            )
        val resolved =
            BookReaderActivity.resolveChapterTitle(
                locator = locator,
                tableOfContents = toc,
                bookTitle = "Sample Book",
            )
        assertEquals("Chapter 2", resolved)
    }

    @Test
    fun testResolveChapterTitleNestedTocChild() {
        val locator =
            Locator(
                href = Url("ch1.xhtml#sub1")!!,
                mediaType = MediaType.XHTML,
            )
        val child = Link(href = Url("ch1.xhtml#sub1")!!, title = "Subsection 1.1")
        val toc =
            listOf(
                Link(
                    href = Url("ch1.xhtml")!!,
                    title = "Chapter 1",
                    children = listOf(child),
                )
            )
        val resolved =
            BookReaderActivity.resolveChapterTitle(
                locator = locator,
                tableOfContents = toc,
                bookTitle = "Sample Book",
            )
        assertEquals("Subsection 1.1", resolved)
    }

    @Test
    fun testResolveChapterTitleFallsBackToReadingOrder() {
        val locator =
            Locator(
                href = Url("intro.xhtml")!!,
                mediaType = MediaType.XHTML,
            )
        val readingOrder =
            listOf(
                Link(href = Url("intro.xhtml")!!, title = "Introduction"),
                Link(href = Url("ch1.xhtml")!!, title = "Chapter 1"),
            )
        val resolved =
            BookReaderActivity.resolveChapterTitle(
                locator = locator,
                readingOrder = readingOrder,
                bookTitle = "Sample Book",
            )
        assertEquals("Introduction", resolved)
    }

    @Test
    fun testResolveChapterTitleFallsBackToBookTitleWhenChapterNotAvailable() {
        // Typical CBZ or EPUB without chapter titles
        val locator =
            Locator(
                href = Url("001.jpg")!!,
                mediaType = MediaType.JPEG,
            )
        val resolved =
            BookReaderActivity.resolveChapterTitle(
                locator = locator,
                tableOfContents = emptyList(),
                readingOrder = listOf(Link(href = Url("001.jpg")!!)),
                bookTitle = "My Manga Vol. 1",
            )
        assertEquals("My Manga Vol. 1", resolved)
    }

    @Test
    fun testResolveChapterTitleFallsBackToBookTitleWhenLocatorNull() {
        val resolved =
            BookReaderActivity.resolveChapterTitle(
                locator = null,
                bookTitle = "My Book",
            )
        assertEquals("My Book", resolved)
    }
}
