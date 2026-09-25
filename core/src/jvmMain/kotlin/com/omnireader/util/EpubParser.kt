package com.omnireader.util

import com.omnireader.data.model.EpubBook
import com.omnireader.data.model.EpubChapter
import com.omnireader.data.model.EpubMetadata
import org.jsoup.Jsoup
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private val frontMatterNamePattern = Regex(
    "\\b(title\\s*page|front\\s*cover|full\\s*cover|cover|copyright|colophon|contents|synopsis|acknowledge?ments?|dedication|about the author)\\b",
    RegexOption.IGNORE_CASE
)

private val frontMatterTitlePattern = Regex(
    "^(front\\s*cover|full\\s*cover|cover|title\\s*page|copyright|colophon|table of contents|contents|synopsis|volume\\s+\\d)",
    RegexOption.IGNORE_CASE
)

private fun isFrontMatter(fullPath: String, title: String): Boolean {
    val fileName = fullPath.substringAfterLast('/').substringBeforeLast('.').lowercase()
    return frontMatterNamePattern.containsMatchIn(fileName) ||
        frontMatterTitlePattern.containsMatchIn(title.trim())
}

private fun resolvePath(baseDir: String, href: String): String {
    val decoded = URLDecoder.decode(href, StandardCharsets.UTF_8)
    val clean = decoded.removePrefix("/").removePrefix("./").replace('\\', '/')
    return if (baseDir.isEmpty()) clean else "$baseDir/$clean"
}

class FileCoverStore(private val cacheDir: File) : CoverStore {

    private val knownExtensions = listOf("jpg", "jpeg", "png", "gif", "webp")

    override fun load(key: String): String? {
        for (ext in knownExtensions) {
            val file = cacheDir.resolve(fileName(key, ext))
            if (file.isFile) return file.absolutePath
        }
        return null
    }

    override fun save(key: String, data: ByteArray, extension: String): String? {
        val normalized = extension.lowercase()
        if (normalized == "svg" || data.isEmpty()) return null
        if (!cacheDir.isDirectory) cacheDir.mkdirs()
        val file = cacheDir.resolve(fileName(key, normalized))
        if (!file.isFile) {
            runCatching { file.writeBytes(data) }.getOrElse { return null }
        }
        return file.absolutePath
    }

    private fun fileName(key: String, extension: String): String =
        "cover_${key.hashCode()}.$extension"
}

class EpubParser(
    private val openStream: (String) -> InputStream?,
    private val coverStore: CoverStore
) {

    data class ParsedZip(
        val entries: Map<String, String>,
        val binaryEntries: Map<String, ByteArray>
    )

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private data class OpfInfo(
        val metadata: Map<String, String>,
        val spineItems: List<String>,
        val manifest: List<ManifestItem>
    )

    fun parseEpub(source: String): Result<EpubBook> = runCatching {
        val stream = openStream(source) ?: throw IllegalStateException("Cannot open EPUB file")

        stream.use { input ->
            val parsed = readZipSelective(input, textFilter = true)
            val entries = parsed.entries

            val containerXml = entries["META-INF/container.xml"]
                ?: throw IllegalStateException("Invalid EPUB: missing container.xml")

            val rootFilePath = parseContainerXml(containerXml)
            val opfContent = entries[rootFilePath]
                ?: throw IllegalStateException("Invalid EPUB: missing OPF file")

            val opfDir = rootFilePath.substringBeforeLast("/", "")
            val opfInfo = parseOpf(opfContent)
            val tocTitles = buildTocTitles(opfDir, opfInfo.manifest, entries)

            val chapters = opfInfo.spineItems
                .mapNotNull { href ->
                    val fullPath = resolveEntryKey(entries, opfDir, href) ?: return@mapNotNull null
                    val html = entries.getValue(fullPath)

                    val doc = Jsoup.parse(html)
                    doc.select("script, style, link[rel=stylesheet], nav, header, footer").remove()

                    val plainText = doc.body()?.text() ?: ""
                    if (plainText.isBlank()) return@mapNotNull null

                    val chapterTitle = tocTitles[fullPath]
                        ?: tocTitles["#norm:${fullPath.substringAfterLast('/').substringBeforeLast('.').lowercase()}"]
                        ?: doc.title()

                    EpubChapter(
                        index = -1,
                        title = chapterTitle,
                        htmlContent = html,
                        plainText = plainText,
                        isFrontMatter = isFrontMatter(fullPath, chapterTitle)
                    )
                }
                .mapIndexed { position, chapter ->
                    chapter.copy(
                        index = position,
                        title = chapter.title.ifBlank { "Chapter ${position + 1}" }
                    )
                }

            val coverSource = runCatching {
                openStream(source)?.use { coverInput ->
                    val coverPath = findCoverImage(opfContent, opfDir, opfInfo.spineItems, entries)
                    coverPath?.let { path ->
                        readSingleBinaryEntry(coverInput, path)?.let { data ->
                            coverStore.save(source, data, path.substringAfterLast('.', "png"))
                        }
                    }
                }
            }.getOrNull()

            EpubBook(
                source = source,
                title = opfInfo.metadata["title"] ?: "Unknown Title",
                author = opfInfo.metadata["creator"] ?: "Unknown Author",
                chapters = chapters,
                coverSource = coverSource
            )
        }
    }

    fun extractMetadata(source: String, absPath: String? = null): Result<EpubMetadata> = runCatching {
        val stream = openStream(source) ?: throw IllegalStateException("Cannot open EPUB file")

        stream.use { input ->
            val parsed = readZipSelective(input, textFilter = true)
            val entries = parsed.entries

            val containerXml = entries["META-INF/container.xml"]
                ?: throw IllegalStateException("Invalid EPUB: missing container.xml")

            val rootFilePath = parseContainerXml(containerXml)
            val opfContent = entries[rootFilePath]
                ?: throw IllegalStateException("Invalid EPUB: missing OPF file")

            val opfDir = rootFilePath.substringBeforeLast("/", "")
            val opfInfo = parseOpf(opfContent)
            val metadata = opfInfo.metadata
            val spineItems = opfInfo.spineItems

            val cacheKey = absPath ?: source
            val cachedCover = coverStore.load(cacheKey)
            val coverPath = if (cachedCover != null) null else findCoverImage(opfContent, opfDir, spineItems, entries)
            val coverSource = when {
                cachedCover != null -> cachedCover
                coverPath != null -> {
                    runCatching {
                        openStream(source)?.use { coverInput ->
                            readSingleBinaryEntry(coverInput, coverPath)?.let { data ->
                                coverStore.save(cacheKey, data, coverPath.substringAfterLast('.', "png"))
                            }
                        }
                    }.getOrNull()
                }
                else -> null
            }

            EpubMetadata(
                title = metadata["title"] ?: extractTitleFromFilename(source),
                author = metadata["creator"] ?: "Unknown Author",
                coverSource = coverSource
            )
        }
    }

    private fun findCoverImage(
        opfContent: String,
        opfDir: String,
        spineItems: List<String>,
        entries: Map<String, String>
    ): String? {
        val doc = Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())

        val manifest = mutableMapOf<String, String>()
        doc.select("manifest > item").forEach { item ->
            manifest[item.attr("id")] = item.attr("href")
        }

        val coverId = doc.selectFirst("metadata meta[name=cover]")?.attr("content")
        if (coverId != null) {
            val href = manifest[coverId]
            if (!href.isNullOrBlank()) return resolvePath(opfDir, href)
        }

        val coverItem = doc.select("manifest > item[properties~=cover-image]").firstOrNull()
        if (coverItem != null) {
            val href = coverItem.attr("href")
            if (href.isNotBlank()) return resolvePath(opfDir, href)
        }

        val idMatch = doc.select("manifest > item").firstOrNull {
            it.attr("id").lowercase().contains("cover")
        }
        if (idMatch != null) {
            val href = idMatch.attr("href")
            if (href.isNotBlank()) return resolvePath(opfDir, href)
        }

        val hrefMatch = doc.select("manifest > item").firstOrNull {
            it.attr("href").lowercase().contains("cover")
        }
        if (hrefMatch != null) {
            val href = hrefMatch.attr("href")
            if (href.isNotBlank()) return resolvePath(opfDir, href)
        }

        for (href in spineItems) {
            val fullPath = resolvePath(opfDir, href)
            val html = entries[fullPath] ?: continue
            val htmlDoc = Jsoup.parse(html)

            val img = htmlDoc.selectFirst("img[src]") ?: continue
            val src = img.attr("src")
            if (src.isNotBlank()) {
                val spineDir = fullPath.substringBeforeLast('/', "")
                return resolvePath(spineDir, src)
            }

            val svgImage = htmlDoc.selectFirst("svg image[xlink\\:href], svg image[href]")
            if (svgImage != null) {
                val src = svgImage.attr("xlink:href").ifBlank { svgImage.attr("href") }
                if (src.isNotBlank()) {
                    val spineDir = fullPath.substringBeforeLast('/', "")
                    return resolvePath(spineDir, src)
                }
            }
        }

        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "svg")
        val firstImage = manifest.values.firstOrNull {
            it.substringAfterLast('.', "").lowercase() in imageExtensions
        }
        return firstImage?.let { resolvePath(opfDir, it) }
    }

    private fun resolveEntryKey(entries: Map<String, String>, opfDir: String, href: String): String? {
        val raw = if (opfDir.isEmpty()) href else "$opfDir/$href"
        entries[raw]?.let { return raw }

        val normalized = URLDecoder.decode(raw, StandardCharsets.UTF_8)
            .replace('\\', '/')
            .let { if (it.startsWith("./")) it.removePrefix("./") else it }
        entries[normalized]?.let { return normalized }

        val needleLower = normalized.lowercase()
        entries.keys.firstOrNull { it.replace('\\', '/').lowercase() == needleLower }?.let { return it }

        val lastSegment = normalized.substringAfterLast('/', normalized)
        if (lastSegment.isNotEmpty()) {
            val lastLower = lastSegment.lowercase()
            entries.keys.firstOrNull {
                val n = it.replace('\\', '/').lowercase()
                n == lastLower || n.endsWith("/$lastLower")
            }?.let { return it }
        }
        return null
    }

    private fun readSingleBinaryEntry(inputStream: InputStream, targetName: String): ByteArray? {
        val zipStream = ZipInputStream(inputStream)
        var entry: ZipEntry?
        val decodedTarget = URLDecoder.decode(targetName, StandardCharsets.UTF_8).replace('\\', '/')
        val targetLower = decodedTarget.lowercase()
        val targetLastSegment = decodedTarget.substringAfterLast('/', decodedTarget)
        while (zipStream.nextEntry.also { entry = it } != null) {
            val entryName = entry!!.name.replace('\\', '/')
            val entryLower = entryName.lowercase()
            if (entryLower == targetLower || entryLower.endsWith("/$targetLastSegment") || entryLower == targetLastSegment.lowercase()) {
                return zipStream.readBytes().also { zipStream.close() }
            }
        }
        zipStream.close()
        return null
    }

    private fun readZipSelective(inputStream: InputStream, textFilter: Boolean): ParsedZip {
        val textEntries = mutableMapOf<String, String>()
        val binaryEntries = mutableMapOf<String, ByteArray>()
        val zipStream = ZipInputStream(inputStream)

        val textExtensions = setOf("xhtml", "html", "htm", "xml", "opf", "ncx")
        val binaryExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "svg")

        var entry: ZipEntry?
        while (zipStream.nextEntry.also { entry = it } != null) {
            val name = entry!!.name
            if (entry!!.isDirectory) continue

            val ext = name.substringAfterLast('.', "").lowercase()

            if (textFilter) {
                if (ext in textExtensions || name.endsWith("container.xml")) {
                    textEntries[name] = String(zipStream.readBytes(), Charsets.UTF_8)
                } else {
                    zipStream.readBytes()
                }
            } else {
                if (ext in textExtensions || name.endsWith("container.xml")) {
                    textEntries[name] = String(zipStream.readBytes(), Charsets.UTF_8)
                } else if (ext in binaryExtensions) {
                    binaryEntries[name] = zipStream.readBytes()
                } else {
                    zipStream.readBytes()
                }
            }
        }
        zipStream.close()
        return ParsedZip(textEntries, binaryEntries)
    }

    private fun parseContainerXml(xml: String): String {
        val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        return doc.selectFirst("rootfile")?.attr("full-path")
            ?: throw IllegalStateException("Invalid container.xml: no rootfile element")
    }

    private fun parseOpf(opfContent: String): OpfInfo {
        val doc = Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())

        val metadata = mutableMapOf<String, String>()
        doc.select("metadata > *").forEach { element ->
            val key = element.tagName().substringAfter(":")
            val value = element.text()
            if (value.isNotBlank()) {
                metadata[key.lowercase()] = value
            }
        }

        val manifest = mutableListOf<ManifestItem>()
        doc.select("manifest > item").forEach { item ->
            manifest.add(
                ManifestItem(
                    id = item.attr("id"),
                    href = item.attr("href"),
                    mediaType = item.attr("media-type"),
                    properties = item.attr("properties")
                )
            )
        }

        val hrefById = manifest.associate { it.id to it.href }
        val spineItems = mutableListOf<String>()
        doc.select("spine > itemref").forEach { itemref ->
            val href = hrefById[itemref.attr("idref")] ?: return@forEach
            spineItems.add(href)
        }

        return OpfInfo(metadata, spineItems, manifest)
    }

    private fun buildTocTitles(
        opfDir: String,
        manifest: List<ManifestItem>,
        entries: Map<String, String>
    ): Map<String, String> {
        val titles = mutableMapOf<String, String>()

        val ncxHref = manifest.firstOrNull {
            it.mediaType == "application/x-dtbncx+xml" || it.href.substringAfterLast('.').lowercase() == "ncx"
        }?.href
        if (ncxHref != null) {
            val ncxPath = resolvePath(opfDir, ncxHref)
            val ncx = entries[ncxPath]
                ?: entries.keys.firstOrNull { it.equals(ncxPath, ignoreCase = true) }
            if (ncx != null) {
                parseNcxTitles(ncx, opfDir, titles)
            }
        }

        val navHref = manifest.firstOrNull {
            it.properties.split(" ").any { p -> p.equals("nav", ignoreCase = true) }
        }?.href
        if (navHref != null) {
            val navPath = resolvePath(opfDir, navHref)
            val navContent = entries[navPath]
                ?: entries.keys.firstOrNull { it.equals(navPath, ignoreCase = true) }
            if (navContent != null) {
                parseNavTitles(navContent, opfDir, titles)
            }
        }

        val result = HashMap<String, String>()
        titles.forEach { (path, title) ->
            result[path] = title
            result["#norm:${path.substringAfterLast('/').substringBeforeLast('.').lowercase()}"] = title
        }
        return result
    }

    private fun parseNcxTitles(ncxContent: String, opfDir: String, titles: MutableMap<String, String>) {
        val doc = Jsoup.parse(ncxContent, "", org.jsoup.parser.Parser.xmlParser())
        doc.select("navMap navPoint").forEach { navPoint ->
            val title = navPoint.selectFirst("navLabel text")?.text()?.trim().orEmpty()
            val src = navPoint.selectFirst("content")?.attr("src")?.trim().orEmpty()
            if (title.isNotBlank() && src.isNotBlank()) {
                val clean = src.substringBefore('#').removePrefix("/")
                titles[normalizeTocPath(resolvePath(opfDir, clean))] = title
            }
        }
    }

    private fun parseNavTitles(navHtml: String, opfDir: String, titles: MutableMap<String, String>) {
        val doc = Jsoup.parse(navHtml)
        val tocNav = doc.select("nav").firstOrNull {
            it.attr("epub:type").lowercase() == "toc" || it.attr("type").lowercase() == "toc"
        } ?: doc.selectFirst("nav") ?: return
        tocNav.select("a[href]").forEach { a ->
            val title = a.text().trim()
            val href = a.attr("href").trim()
            if (title.isNotBlank() && href.isNotBlank()) {
                val clean = href.substringBefore('#').removePrefix("/")
                titles[normalizeTocPath(resolvePath(opfDir, clean))] = title
            }
        }
    }

    private fun normalizeTocPath(path: String): String {
        val slashed = path.replace('\\', '/')
        return if ('%' in slashed) {
            URLDecoder.decode(slashed, StandardCharsets.UTF_8)
        } else {
            slashed
        }
    }

    private fun extractTitleFromFilename(source: String): String {
        val name = source.substringAfterLast('/').substringAfterLast('\\')
        if (name.isBlank()) return "Unknown Title"
        return name.substringBeforeLast(".")
            .replace("_", " ")
            .replace("-", " ")
            .trim()
            .ifBlank { "Unknown Title" }
    }

    fun splitIntoSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }
            .map { it.trim() }
    }

    fun extractTextForTts(chapter: EpubChapter): String {
        val doc = Jsoup.parse(chapter.htmlContent)
        return doc.body()?.text() ?: chapter.plainText
    }
}
