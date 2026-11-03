package eu.kanade.tachiyomi.extension.all.hitomi

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.all.hitomi.HitomiImages.Companion.LTN
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Hitomi(
    override val lang: String,
    private val hitomiLang: String,
) : HttpSource(), ConfigurableSource {

    override val name = "Hitomi"

    override val baseUrl = "https://hitomi.la"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val images by lazy { HitomiImages(client, headers) }
    private val searcher by lazy { HitomiSearch(client, headers) }

    // ============================== Browse ===============================

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        val period = preferences.getString(PREF_POPULAR, "week")!!
        val state = HitomiSearch.State(language = hitomiLang, orderBy = "popular", orderByKey = period)
        pageFromIds(searcher.nozomiPage(state, page), page)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        pageFromIds(searcher.nozomiPage(HitomiSearch.State(language = hitomiLang), page), page)
    }

    // ============================== Search ===============================

    @Volatile
    private var searchCache: Pair<String, List<Int>>? = null

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable {
            // Direct id / url lookup: "123456", "id:123456" or any hitomi.la gallery link
            directId(query)?.let { id ->
                val manga = fetchGalleries(listOf(id))
                return@fromCallable MangasPage(manga, false)
            }

            var orderBy = "date"
            var orderByKey = "added"
            val terms = mutableListOf<String>()
            if (query.isNotBlank()) terms.add(query.trim())
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> filter.selected.let { orderBy = it.first; orderByKey = it.second }
                    is TypeFilter -> filter.selected.takeIf { it.isNotEmpty() }?.let { terms.add("type:$it") }
                    is TermFilter -> terms.addAll(filter.terms())
                    else -> {}
                }
            }
            val fullQuery = terms.joinToString(" ")
            val parsed = searcher.parse(fullQuery, hitomiLang, orderBy, orderByKey)

            if (parsed.isSimple) {
                val state = parsed.positive.firstOrNull()
                    ?.let { searcher.stateForTerm(it, parsed.base) }
                    ?: parsed.base
                return@fromCallable pageFromIds(searcher.nozomiPage(state, page), page)
            }

            val cacheKey = "$hitomiLang|$orderBy|$orderByKey|$fullQuery"
            val ids = searchCache?.takeIf { it.first == cacheKey && page > 1 }?.second
                ?: searcher.search(parsed).also { searchCache = cacheKey to it }

            val from = (page - 1) * HitomiSearch.PER_PAGE
            val slice = ids.drop(from).take(HitomiSearch.PER_PAGE)
            MangasPage(fetchGalleries(slice), from + slice.size < ids.size)
        }

    private fun directId(query: String): Int? {
        val q = query.trim()
        ID_REGEXES.forEach { regex -> regex.find(q)?.let { return it.groupValues[1].toIntOrNull() } }
        return null
    }

    override fun getFilterList() = hitomiFilters()

    private fun pageFromIds(idPage: HitomiSearch.IdPage, page: Int): MangasPage {
        val hasNext = page * HitomiSearch.PER_PAGE < idPage.total
        return MangasPage(fetchGalleries(idPage.ids), hasNext)
    }

    /** Loads gallery infos in parallel, keeping the original order and skipping failed ones. */
    private fun fetchGalleries(ids: List<Int>): List<SManga> {
        val tasks = ids.map { id ->
            Callable { runCatching { galleryToManga(fetchGallery(id)) }.getOrNull() }
        }
        return EXECUTOR.invokeAll(tasks, 60, TimeUnit.SECONDS)
            .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
    }

    private fun galleryRequest(id: String): Request = GET("$LTN/galleries/$id.js", headers)

    private fun fetchGallery(id: Int): JsonObject =
        client.newCall(galleryRequest(id.toString())).execute().use { parseGallery(it) }

    private fun parseGallery(response: Response): JsonObject {
        val body = response.body.string()
        val json = body.substring(body.indexOf('{'))
        return Json.parseToJsonElement(json).jsonObject
    }

    // ============================== Details ==============================

    private fun SManga.galleryId() = url.substringAfterLast('/').substringBefore('.')

    override fun mangaDetailsRequest(manga: SManga): Request = galleryRequest(manga.galleryId())

    override fun mangaDetailsParse(response: Response): SManga = galleryToManga(parseGallery(response)).apply {
        initialized = true
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/galleries/${manga.galleryId()}.html"

    private fun galleryToManga(g: JsonObject): SManga = SManga.create().apply {
        val id = g.str("id")!!
        url = "/galleries/$id.html"

        val japaneseTitle = g.str("japanese_title")
        title = if (preferences.getBoolean(PREF_JAPANESE_TITLE, false) && !japaneseTitle.isNullOrBlank()) {
            japaneseTitle
        } else {
            g.str("title") ?: japaneseTitle ?: id
        }

        g.arr("files").firstOrNull()?.jsonObjectOrNull()?.str("hash")?.let {
            thumbnail_url = HitomiImages.thumbnailUrl(it)
        }

        val artists = g.names("artists", "artist")
        val groups = g.names("groups", "group")
        artist = artists.joinToString().ifEmpty { null }
        author = (groups.ifEmpty { artists }).joinToString().ifEmpty { null }

        genre = g.arr("tags").mapNotNull { it.jsonObjectOrNull() }.mapNotNull { tag ->
            val name = tag.str("tag") ?: return@mapNotNull null
            when {
                tag.flag("female") -> "$name ♀"
                tag.flag("male") -> "$name ♂"
                else -> name
            }
        }.joinToString()

        description = buildString {
            japaneseTitle?.takeIf { it.isNotBlank() && it != title }?.let { append("Japanese title: ", it, "\n") }
            g.str("title")?.takeIf { it != title }?.let { append("Title: ", it, "\n") }
            g.str("type")?.let { append("Type: ", it, "\n") }
            (g.str("language_localname") ?: g.str("language"))?.let { append("Language: ", it, "\n") }
            g.names("parodys", "parody").takeIf { it.isNotEmpty() }?.let { append("Series: ", it.joinToString(), "\n") }
            g.names("characters", "character").takeIf { it.isNotEmpty() }?.let { append("Characters: ", it.joinToString(), "\n") }
            append("Pages: ${g.arr("files").size}\n")
            append("ID: $id")
        }

        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    // ============================== Chapters =============================

    override fun chapterListRequest(manga: SManga): Request = galleryRequest(manga.galleryId())

    override fun chapterListParse(response: Response): List<SChapter> {
        val g = parseGallery(response)
        val id = g.str("id")!!
        return listOf(
            SChapter.create().apply {
                url = "/reader/$id.html"
                name = "Chapter"
                chapter_number = 1f
                scanlator = g.names("groups", "group").joinToString().ifEmpty { null }
                date_upload = parseDate(g.str("date"))
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    private fun parseDate(date: String?): Long {
        if (date == null) return 0L
        // "2016-03-27 13:37:33.612-05" -> "2016-03-27 13:37:33-0500"
        val clean = date.replace(Regex("""\.\d+"""), "")
            .replace(Regex("""([+-]\d{2})(?::?(\d{2}))?$""")) { it.groupValues[1] + it.groupValues[2].ifEmpty { "00" } }
        return runCatching { DATE_FORMAT.get()!!.parse(clean)!!.time }.getOrDefault(0L)
    }

    // ============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        galleryRequest(chapter.url.substringAfterLast('/').substringBefore('.'))

    override fun pageListParse(response: Response): List<Page> {
        val useAvif = preferences.getString(PREF_IMAGE_FORMAT, "webp") == "avif"
        return parseGallery(response).arr("files").mapIndexedNotNull { i, element ->
            val file = element.jsonObjectOrNull() ?: return@mapIndexedNotNull null
            val hash = file.str("hash") ?: return@mapIndexedNotNull null
            val format = if (useAvif && file.flag("hasavif")) "avif" else "webp"
            // The real URL depends on gg.js which changes often, so it is resolved lazily in fetchImageUrl.
            Page(i, "$hash.$format")
        }
    }

    override fun fetchImageUrl(page: Page): Observable<String> = Observable.fromCallable {
        images.imageUrl(page.url.substringBefore('.'), page.url.substringAfter('.'))
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Settings =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_IMAGE_FORMAT
            title = "Image format"
            entries = arrayOf("WebP (most compatible)", "AVIF (smaller, needs AVIF support)")
            entryValues = arrayOf("webp", "avif")
            setDefaultValue("webp")
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_POPULAR
            title = "Popular period"
            entries = arrayOf("Today", "Week", "Month", "Year")
            entryValues = arrayOf("today", "week", "month", "year")
            setDefaultValue("week")
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_JAPANESE_TITLE
            title = "Prefer Japanese titles"
            summary = "Use the original Japanese title when available (refresh the entry to apply)"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ============================== Unused ===============================

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ============================== Json helpers =========================

    private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()

    /** Hitomi encodes booleans as "1", 1, "" or null. */
    private fun JsonObject.flag(key: String): Boolean = str(key).let { it == "1" || it == "true" }

    private fun JsonObject.names(key: String, field: String): List<String> =
        arr(key).mapNotNull { it.jsonObjectOrNull()?.str(field) }

    companion object {
        private const val PREF_IMAGE_FORMAT = "pref_image_format"
        private const val PREF_POPULAR = "pref_popular_period"
        private const val PREF_JAPANESE_TITLE = "pref_japanese_title"

        private val ID_REGEXES = listOf(
            Regex("""^(?:id:)?(\d+)$"""),
            Regex("""hitomi\.la/.*?(\d+)\.html"""),
        )

        private val DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US)
        }

        private val EXECUTOR = Executors.newFixedThreadPool(6) { r ->
            Thread(r, "hitomi-loader").apply { isDaemon = true }
        }
    }
}
