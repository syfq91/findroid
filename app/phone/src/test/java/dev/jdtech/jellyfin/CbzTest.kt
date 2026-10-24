package dev.jdtech.jellyfin

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.asset.DefaultArchiveOpener
import org.readium.r2.shared.util.asset.DefaultFormatSniffer
import org.readium.r2.shared.util.file.FileResourceFactory
import org.readium.r2.shared.util.format.Specification
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.readium.r2.streamer.parser.image.ImageParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CbzTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun createSampleCbz(
        fileName: String,
        includeComicInfo: Boolean = false,
        includeDirectories: Boolean = false,
        imageNames: List<String> = listOf("page1.jpg", "page2.jpg"),
    ): File {
        val file = tempFolder.newFile(fileName)
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            if (includeDirectories) {
                zos.putNextEntry(ZipEntry("images/"))
                zos.closeEntry()
            }
            if (includeComicInfo) {
                zos.putNextEntry(ZipEntry("ComicInfo.xml"))
                zos.write("<ComicInfo><Title>Test</Title></ComicInfo>".toByteArray())
                zos.closeEntry()
            }
            for (name in imageNames) {
                val entryPath = if (includeDirectories) "images/$name" else name
                zos.putNextEntry(ZipEntry(entryPath))
                // Minimal 1x1 GIF bytes
                zos.write(
                    byteArrayOf(
                        0x47,
                        0x49,
                        0x46,
                        0x38,
                        0x39,
                        0x61,
                        1,
                        0,
                        1,
                        0,
                        0x80.toByte(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0xFF.toByte(),
                        0xFF.toByte(),
                        0xFF.toByte(),
                        0x21,
                        0xF9.toByte(),
                        0x04,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0x2C,
                        0,
                        0,
                        0,
                        0,
                        1,
                        0,
                        1,
                        0,
                        0,
                        2,
                        2,
                        0x44,
                        1,
                        0,
                        0x3B,
                    )
                )
                zos.closeEntry()
            }
        }
        return file
    }

    private fun createSampleEpub(fileName: String): File {
        val file = tempFolder.newFile(fileName)
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            // Uncompressed mimetype
            val mimeEntry = ZipEntry("mimetype")
            mimeEntry.method = ZipEntry.STORED
            val mimeBytes = "application/epub+zip".toByteArray()
            mimeEntry.size = mimeBytes.size.toLong()
            val crc = java.util.zip.CRC32()
            crc.update(mimeBytes)
            mimeEntry.crc = crc.value
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            // container.xml
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """
                <?xml version="1.0"?>
                                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                                  <rootfiles>
                                    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
                                  </rootfiles>
                                </container>
                """
                    .trimIndent()
                    .toByteArray()
            )
            zos.closeEntry()

            // content.opf
            zos.putNextEntry(ZipEntry("content.opf"))
            zos.write(
                """
                <?xml version="1.0" encoding="utf-8"?>
                                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                                    <dc:identifier id="uid">urn:uuid:12345</dc:identifier>
                                    <dc:title>Sample EPUB</dc:title>
                                    <dc:language>en</dc:language>
                                  </metadata>
                                  <manifest>
                                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                                  </manifest>
                                  <spine>
                                    <itemref idref="ch1"/>
                                  </spine>
                                </package>
                """
                    .trimIndent()
                    .toByteArray()
            )
            zos.closeEntry()

            // ch1.xhtml
            zos.putNextEntry(ZipEntry("ch1.xhtml"))
            zos.write(
                """
                <?xml version="1.0" encoding="utf-8"?>
                                <html xmlns="http://www.w3.org/1999/xhtml">
                                  <body><p>Hello EPUB</p></body>
                                </html>
                """
                    .trimIndent()
                    .toByteArray()
            )
            zos.closeEntry()

            // nav.xhtml
            zos.putNextEntry(ZipEntry("nav.xhtml"))
            zos.write(
                """
                <?xml version="1.0" encoding="utf-8"?>
                                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                                  <body><nav epub:type="toc"><ol><li><a href="ch1.xhtml">Chapter 1</a></li></ol></nav></body>
                                </html>
                """
                    .trimIndent()
                    .toByteArray()
            )
            zos.closeEntry()
        }
        return file
    }

    @Test
    fun testDetectBookFormatCbzWithMimetype() {
        val file = tempFolder.newFile("calibre_comic.cbz")
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/vnd.comicbook+zip".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("001.jpg"))
            zos.write(
                byteArrayOf(
                    0x47,
                    0x49,
                    0x46,
                    0x38,
                    0x39,
                    0x61,
                    1,
                    0,
                    1,
                    0,
                    0x80.toByte(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0xFF.toByte(),
                    0xFF.toByte(),
                    0xFF.toByte(),
                    0x21,
                    0xF9.toByte(),
                    0x04,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0x2C,
                    0,
                    0,
                    0,
                    0,
                    1,
                    0,
                    1,
                    0,
                    0,
                    2,
                    2,
                    0x44,
                    1,
                    0,
                    0x3B,
                )
            )
            zos.closeEntry()
        }

        val detected = BookReaderActivity.detectBookFormat(file)
        assertEquals("CBZ with comicbook mimetype must be detected as cbz", "cbz", detected)
    }

    @Test
    fun testDetectBookFormatCbzWithComicInfo() {
        val file = tempFolder.newFile("comic_info_only.cbz")
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("ComicInfo.xml"))
            zos.write("<ComicInfo><Title>Test</Title></ComicInfo>".toByteArray())
            zos.closeEntry()
        }

        val detected = BookReaderActivity.detectBookFormat(file)
        assertEquals("CBZ with ComicInfo.xml must be detected as cbz", "cbz", detected)
    }

    @Test
    fun testDetectBookFormatCbzWithShiftJisNames() {
        val file = tempFolder.newFile("manga_shift_jis.cbz")
        // Use Shift-JIS for entry names (common in Japanese manga archives)
        val shiftJis = Charset.forName("Shift_JIS")
        ZipOutputStream(FileOutputStream(file), shiftJis).use { zos ->
            zos.putNextEntry(ZipEntry("第1話/表紙.png"))
            zos.write(
                byteArrayOf(
                    0x47,
                    0x49,
                    0x46,
                    0x38,
                    0x39,
                    0x61,
                    1,
                    0,
                    1,
                    0,
                    0x80.toByte(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0xFF.toByte(),
                    0xFF.toByte(),
                    0xFF.toByte(),
                    0x21,
                    0xF9.toByte(),
                    0x04,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0x2C,
                    0,
                    0,
                    0,
                    0,
                    1,
                    0,
                    1,
                    0,
                    0,
                    2,
                    2,
                    0x44,
                    1,
                    0,
                    0x3B,
                )
            )
            zos.closeEntry()
        }

        val detected = BookReaderActivity.detectBookFormat(file)
        assertEquals(
            "CBZ with Shift-JIS entries must be detected as cbz without failing",
            "cbz",
            detected,
        )
    }

    @Test
    fun testDetectBookFormatVariousImageExtensions() {
        for (ext in listOf("webp", "avif", "bmp", "png", "gif", "tif", "tiff", "jxl")) {
            val file = tempFolder.newFile("test_img_$ext.cbz")
            ZipOutputStream(FileOutputStream(file)).use { zos ->
                zos.putNextEntry(ZipEntry("page01.$ext"))
                zos.write(
                    byteArrayOf(
                        0x47,
                        0x49,
                        0x46,
                        0x38,
                        0x39,
                        0x61,
                        1,
                        0,
                        1,
                        0,
                        0x80.toByte(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0xFF.toByte(),
                        0xFF.toByte(),
                        0xFF.toByte(),
                        0x21,
                        0xF9.toByte(),
                        0x04,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0x2C,
                        0,
                        0,
                        0,
                        0,
                        1,
                        0,
                        1,
                        0,
                        0,
                        2,
                        2,
                        0x44,
                        1,
                        0,
                        0x3B,
                    )
                )
                zos.closeEntry()
            }
            val detected = BookReaderActivity.detectBookFormat(file)
            assertEquals("CBZ with $ext must be detected as cbz", "cbz", detected)
        }
    }

    @Test
    fun testDetectBookFormatRealEpub() {
        val epubFile = createSampleEpub("sample.epub")
        val detected = BookReaderActivity.detectBookFormat(epubFile)
        assertEquals("Standard EPUB must be detected as epub", "epub", detected)
    }

    @Test
    fun testDetectBookFormatFallbacks() {
        val pdfFile = tempFolder.newFile("doc.pdf")
        pdfFile.writeBytes("%PDF-1.4 sample content".toByteArray())
        assertEquals("pdf", BookReaderActivity.detectBookFormat(pdfFile))

        val rarFile = tempFolder.newFile("archive.cbr")
        rarFile.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00))
        assertEquals("cbr", BookReaderActivity.detectBookFormat(rarFile))

        // Empty zip with container hint
        val emptyZip = tempFolder.newFile("empty.zip")
        ZipOutputStream(FileOutputStream(emptyZip)).use { /* empty */ }
        assertEquals("cbz", BookReaderActivity.detectBookFormat(emptyZip, containerHint = "cbz"))
        assertEquals("cbz", BookReaderActivity.detectBookFormat(emptyZip, containerHint = "zip"))
    }

    @Test
    fun testBuildFormatHints() {
        val cbzHints = BookReaderActivity.buildFormatHints("cbz")
        assertTrue(cbzHints.hasFileExtension("cbz"))
        assertTrue(cbzHints.hasMediaType("application/vnd.comicbook+zip"))

        val epubHints = BookReaderActivity.buildFormatHints("epub")
        assertTrue(epubHints.hasFileExtension("epub"))
        assertTrue(epubHints.hasMediaType("application/epub+zip"))
    }

    @Test
    fun testSafeMoveOrCopy() {
        val source = tempFolder.newFile("source.tmp")
        source.writeText("hello world")
        val target = File(tempFolder.root, "target.cbz")

        val result = BookReaderActivity.safeMoveOrCopy(source, target)
        assertEquals(target.absolutePath, result.absolutePath)
        assertTrue(target.exists())
        assertEquals("hello world", target.readText())
        assertFalse(source.exists())
    }

    @Test
    fun testOpenPlainCbz() = runBlocking {
        val file = createSampleCbz("test.cbz", includeComicInfo = false, includeDirectories = false)
        val assetRetriever =
            AssetRetriever(
                resourceFactory = FileResourceFactory(),
                archiveOpener = DefaultArchiveOpener(),
                formatSniffer = DefaultFormatSniffer(),
            )
        val hints = BookReaderActivity.buildFormatHints("cbz")
        val asset =
            assetRetriever.retrieve(file, hints).getOrElse {
                fail("Failed to retrieve asset: $it")
                throw IllegalStateException()
            }
        assertTrue(
            "Format should conform to InformalComic",
            asset.format.conformsTo(Specification.InformalComic),
        )

        val streamer = PublicationOpener(ImageParser(assetRetriever = assetRetriever))
        val pub =
            streamer.open(asset, allowUserInteraction = false).getOrElse {
                fail("Failed to open publication: $it")
                throw IllegalStateException()
            }
        assertTrue(pub.conformsTo(Publication.Profile.DIVINA))
    }

    @Test
    fun testOpenCbzWithDirectoriesAndXml() = runBlocking {
        val file =
            createSampleCbz("test_dirs.cbz", includeComicInfo = true, includeDirectories = true)
        val assetRetriever =
            AssetRetriever(
                resourceFactory = FileResourceFactory(),
                archiveOpener = DefaultArchiveOpener(),
                formatSniffer = DefaultFormatSniffer(),
            )
        val hints = BookReaderActivity.buildFormatHints("cbz")
        val asset =
            assetRetriever.retrieve(file, hints).getOrElse {
                fail("Failed to retrieve asset: $it")
                throw IllegalStateException()
            }
        assertTrue(
            "Format should conform to InformalComic",
            asset.format.conformsTo(Specification.InformalComic),
        )

        val streamer = PublicationOpener(ImageParser(assetRetriever = assetRetriever))
        val pub =
            streamer.open(asset, allowUserInteraction = false).getOrElse {
                fail("Failed to open publication: $it")
                throw IllegalStateException()
            }
        assertTrue(pub.conformsTo(Publication.Profile.DIVINA))
    }

    @Test
    fun testOpenCbzWithDifferentExtensions() = runBlocking {
        for (ext in listOf("zip", "bin", "cbz", "")) {
            val fileName = if (ext.isEmpty()) "test_no_ext" else "test_ext.$ext"
            val file = createSampleCbz(fileName, includeComicInfo = true, includeDirectories = true)
            val assetRetriever =
                AssetRetriever(
                    resourceFactory = FileResourceFactory(),
                    archiveOpener = DefaultArchiveOpener(),
                    formatSniffer = DefaultFormatSniffer(),
                )
            val hints = BookReaderActivity.buildFormatHints("cbz")
            val asset =
                assetRetriever.retrieve(file, hints).getOrElse {
                    fail("Failed to retrieve asset for $fileName: $it")
                    throw IllegalStateException()
                }

            val streamer = PublicationOpener(ImageParser(assetRetriever = assetRetriever))
            val pub =
                streamer.open(asset, allowUserInteraction = false).getOrElse {
                    fail("Failed to open publication for $fileName: $it")
                    throw IllegalStateException()
                }
            assertTrue(pub.conformsTo(Publication.Profile.DIVINA))
            for (link in pub.manifest.readingOrder) {
                val resource = pub.get(link)
                assertNotNull("Resource for link $link should not be null", resource)
                val bytes =
                    resource!!.read().getOrElse {
                        fail("Failed to read resource for link $link: $it")
                        throw IllegalStateException()
                    }
                assertTrue("Bytes should not be empty", bytes.isNotEmpty())
            }
        }
    }

    @Test
    fun testReopenCbzWithSavedLocator() = runBlocking {
        val file =
            createSampleCbz("test_reopen.cbz", includeComicInfo = false, includeDirectories = false)
        val assetRetriever =
            AssetRetriever(
                resourceFactory = FileResourceFactory(),
                archiveOpener = DefaultArchiveOpener(),
                formatSniffer = DefaultFormatSniffer(),
            )
        val hints = BookReaderActivity.buildFormatHints("cbz")
        val asset =
            assetRetriever.retrieve(file, hints).getOrElse {
                fail("Failed to retrieve asset: $it")
                throw IllegalStateException()
            }
        val streamer = PublicationOpener(ImageParser(assetRetriever = assetRetriever))
        val pub =
            streamer.open(asset, allowUserInteraction = false).getOrElse {
                fail("Failed to open publication: $it")
                throw IllegalStateException()
            }
        val positions = pub.positions()
        assertTrue(positions.isNotEmpty())

        val firstLocator = positions[0]

        // Simulate saving and restoring from JSON
        val jsonStr = firstLocator.toJSON().toString()
        val restoredLocator = Locator.fromJSON(org.json.JSONObject(jsonStr))
        assertNotNull(restoredLocator)

        val factory =
            org.readium.r2.navigator.image.ImageNavigatorFragment.createFactory(
                publication = pub,
                initialLocator = restoredLocator,
                listener =
                    object : org.readium.r2.navigator.image.ImageNavigatorFragment.Listener {},
            )
        val fragment =
            factory.instantiate(
                ClassLoader.getSystemClassLoader(),
                org.readium.r2.navigator.image.ImageNavigatorFragment::class.java.name,
            ) as org.readium.r2.navigator.image.ImageNavigatorFragment
        assertNotNull(fragment)

        // Try second position locator and attach to activity
        val secondLocator = positions[1]
        val secondJsonStr = secondLocator.toJSON().toString()
        val restoredSecondLocator = Locator.fromJSON(org.json.JSONObject(secondJsonStr))
        val factory2 =
            org.readium.r2.navigator.image.ImageNavigatorFragment.createFactory(
                publication = pub,
                initialLocator = restoredSecondLocator,
                listener =
                    object : org.readium.r2.navigator.image.ImageNavigatorFragment.Listener {},
            )
        val fragment2 =
            factory2.instantiate(
                ClassLoader.getSystemClassLoader(),
                org.readium.r2.navigator.image.ImageNavigatorFragment::class.java.name,
            ) as org.readium.r2.navigator.image.ImageNavigatorFragment
        val activityController =
            org.robolectric.Robolectric.buildActivity(
                    androidx.fragment.app.FragmentActivity::class.java
                )
                .setup()
        val activity = activityController.get()
        activity.supportFragmentManager.fragmentFactory = factory2
        activity.supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, fragment2)
            .commitNow()
        assertNotNull(fragment2.currentLocator.value)
    }

    @Test
    fun testSelfHealMisnamedCachedCbzFile() = runBlocking {
        // Simulate a CBZ that was previously renamed to .epub due to the bug
        val corruptedEpubFile = createSampleCbz("book-1234.epub", includeComicInfo = true)
        val detected = BookReaderActivity.detectBookFormat(corruptedEpubFile)
        assertEquals("Should correctly detect as cbz despite .epub extension", "cbz", detected)

        val targetFile = File(tempFolder.root, "book-1234.cbz")
        val healedFile = BookReaderActivity.safeMoveOrCopy(corruptedEpubFile, targetFile)
        assertEquals(targetFile.absolutePath, healedFile.absolutePath)
        assertTrue("Healed CBZ file should exist", healedFile.exists())

        // Now open with DefaultPublicationParser
        val context = RuntimeEnvironment.getApplication()
        val httpClient = org.readium.r2.shared.util.http.DefaultHttpClient()
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        val hints = BookReaderActivity.buildFormatHints(healedFile.extension)
        val asset =
            assetRetriever.retrieve(healedFile, hints).getOrElse {
                fail("Failed to retrieve asset: $it")
                throw IllegalStateException()
            }
        val streamer =
            PublicationOpener(
                DefaultPublicationParser(
                    context = context,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null,
                )
            )
        val pub =
            streamer.open(asset, allowUserInteraction = false).getOrElse {
                fail("Failed to open healed publication: $it")
                throw IllegalStateException()
            }
        assertTrue(pub.conformsTo(Publication.Profile.DIVINA))
    }

    @Test
    fun testEpubContinuesToWorkWithDefaultParser() = runBlocking {
        val epubFile = createSampleEpub("test_book.epub")
        val context = RuntimeEnvironment.getApplication()
        val httpClient = org.readium.r2.shared.util.http.DefaultHttpClient()
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        val hints = BookReaderActivity.buildFormatHints("epub")
        val asset =
            assetRetriever.retrieve(epubFile, hints).getOrElse {
                fail("Failed to retrieve asset: $it")
                throw IllegalStateException()
            }
        val streamer =
            PublicationOpener(
                DefaultPublicationParser(
                    context = context,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null,
                )
            )
        val pub =
            streamer.open(asset, allowUserInteraction = false).getOrElse {
                fail("Failed to open epub: $it")
                throw IllegalStateException()
            }
        assertTrue(pub.conformsTo(Publication.Profile.EPUB))
    }
}
