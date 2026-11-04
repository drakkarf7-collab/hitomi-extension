package eu.kanade.tachiyomi.extension.all.hitomi

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

/**
 * nhentai.net through its official JSON API (https://nhentai.net/api/v2/docs).
 * The old /api/gallery and /api/galleries/search endpoints now answer 403.
 */
class NHentai(
    override val lang: String,
    private val nhLang: String, // "" for all languages
) : HttpSource(), ConfigurableSource {

    // Not "NHentai", so the source id doesn't collide with other nhentai extensions.
    override val name = "nhentai.net"

    override val baseUrl = "https://nhentai.net"

    private val apiUrl = "$baseUrl/api/v2"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // cloudflareClient lets the app solve a Cloudflare challenge in a WebView if one ever shows up.
    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .addInterceptor(::apiRateLimit)
            .addInterceptor(::serverFailover)
            .build()
    }

    // ============================== Rate limit ===========================

    /**
     * The API allows 15 requests per minute (x-ratelimit-limit: 15, retry-after: 60).
     * Requests are spaced to stay under it, and a 429 is retried once after the advertised delay.
     * Image servers (i*.nhentai.net / t*.nhentai.net) are not limited.
     */
    private fun apiRateLimit(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "nhentai.net" || !request.url.encodedPath.startsWith("/api/")) {
            return chain.proceed(request)
        }
        RATE_LIMITER.acquire()
        val response = chain.proceed(request)
        if (response.code != 429) return response

        val wait = response.header("retry-after")?.toLongOrNull()?.coerceIn(1, 60) ?: 30
        response.close()
        Thread.sleep(wait * 1000)
        RATE_LIMITER.acquire()
        val retry = chain.proceed(request)
        if (retry.code == 429) {
            retry.close()
            throw IOException("nhentai limits its API to 15 requests per minute, try again in a minute")
        }
        return retry
    }

    /** Sliding window limiter shared by every nhentai source (the limit is per IP). */
    private class SlidingWindowLimiter(private val permits: Int, private val windowMs: Long) {
        private val calls = java.util.ArrayDeque<Long>()

        @Synchronized
        fun acquire() {
            while (true) {
                val now = System.currentTimeMillis()
                while (calls.isNotEmpty() && now - calls.peekFirst()!! >= windowMs) calls.pollFirst()
                if (calls.size < permits) {
                    calls.addLast(now)
                    return
                }
                Thread.sleep(windowMs - (now - calls.peekFirst()!!) + 50)
            }
        }
    }

    // ============================== Gallery cache ========================

    /** Details, chapter list and pages all need the same JSON: fetch it once (saves 2 of 15 calls/min). */
    private fun gallery(id: String): JsonObject {
        val now = System.currentTimeMillis()
        synchronized(GALLERY_CACHE) {
            GALLERY_CACHE[id]?.takeIf { now - it.first < CACHE_TTL_MS }?.let { return it.second }
        }
        val json = parseJson(client.newCall(GET("$apiUrl/galleries/$id", headers)).execute())
        synchronized(GALLERY_CACHE) {
            GALLERY_CACHE[id] = now to json
            if (GALLERY_CACHE.size > 100) GALLERY_CACHE.remove(GALLERY_CACHE.keys.first())
        }
        return json
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        galleryToManga(gallery(manga.galleryId())).apply { initialized = true }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        chaptersFrom(gallery(manga.galleryId()))
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        pagesFrom(gallery(chapter.url.trim('/').substringAfterLast('/')))
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Image servers ========================

    /**
     * Picks a stable server for a gallery, so cached URLs stay the same.
     * The list matches /api/v2/cdn; it is not fetched to save API quota, failover covers changes.
     */
    private fun pick(list: List<String>, mediaId: String): String =
        list[((mediaId.toLongOrNull() ?: 0L) % list.size).toInt()]

    private fun imageUrl(path: String, mediaId: String) = "${pick(IMAGE_SERVERS, mediaId)}/$path"
    private fun thumbUrl(path: String, mediaId: String) = "${pick(THUMB_SERVERS, mediaId)}/$path"

    /** If an image server is down or misses a file, retry the same path on the other servers. */
    private fun serverFailover(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (!SERVER_HOST.matches(host)) return chain.proceed(request)

        val prefix = host.first() // 'i' or 't'
        val others = (1..4).map { "$prefix$it.nhentai.net" }.filter { it != host }
        val hosts = listOf(host) + others

        var lastError: IOException? = null
        hosts.forEachIndexed { index, h ->
            val isLast = index == hosts.lastIndex
            val attempt = request.newBuilder().url(request.url.newBuilder().host(h).build()).build()
            try {
                val response = chain.proceed(attempt)
                if (response.isSuccessful || isLast || (response.code != 404 && response.code < 500)) return response
                response.close()
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("All nhentai image servers failed")
    }

    // ============================== Browse ===============================

    override fun popularMangaRequest(page: Int): Request {
        val sort = preferences.getString(PREF_POPULAR, "popular-week")!!
        return searchRequest(page, languageTerm(), sort)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        if (nhLang.isEmpty()) {
            GET("$apiUrl/galleries?page=$page&per_page=25", headers)
        } else {
            searchRequest(page, languageTerm(), "date")
        }

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    // ============================== Search ===============================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val id = ID_REGEXES.firstNotNullOfOrNull { it.find(query.trim())?.groupValues?.get(1) }
        if (id != null) {
            return Observable.fromCallable {
                val response = client.newCall(GET("$apiUrl/galleries/$id", headers)).execute()
                if (!response.isSuccessful) {
                    response.close()
                    MangasPage(emptyList(), false)
                } else {
                    MangasPage(listOf(galleryToManga(parseJson(response))), false)
                }
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sort = "date"
        val terms = mutableListOf<String>()
        if (query.isNotBlank()) terms.add(normalizeNhQuery(query.trim().replace(Regex("[,;]+"), " ")))
        filters.forEach { filter ->
            when (filter) {
                is NhSortFilter -> sort = filter.selected
                is NhCategoryFilter -> filter.selected.takeIf { it.isNotEmpty() }?.let { terms.add("category:$it") }
                is NhTermFilter -> terms.addAll(filter.terms())
                else -> {}
            }
        }
        if (terms.none { it.startsWith("language:") }) languageTerm().takeIf { it.isNotEmpty() }?.let { terms.add(it) }
        return searchRequest(page, terms.joinToString(" "), sort)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun languageTerm() = if (nhLang.isEmpty()) "" else "language:$nhLang"

    private fun searchRequest(page: Int, query: String, sort: String): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            // the API rejects an empty query, a single space means "everything"
            .addQueryParameter("query", query.ifBlank { " " })
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    private fun parseJson(response: Response): JsonObject {
        if (!response.isSuccessful) {
            response.close()
            throw Exception("nhentai: HTTP ${response.code}")
        }
        return Json.parseToJsonElement(response.body.string()).jsonObject
    }

    private fun parseList(response: Response): MangasPage {
        val json = parseJson(response)
        val mangas = json.arr("result").mapNotNull { it.jsonObjectOrNull() }.map { item ->
            SManga.create().apply {
                val id = item.str("id")!!
                val mediaId = item.str("media_id").orEmpty()
                url = "/g/$id/"
                title = pickTitle(item.str("english_title"), item.str("japanese_title"), null) ?: id
                item.str("thumbnail")?.let { thumbnail_url = thumbUrl(it, mediaId) }
            }
        }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val numPages = json.str("num_pages")?.toIntOrNull() ?: 0
        return MangasPage(mangas, page < numPages)
    }

    private fun pickTitle(english: String?, japanese: String?, pretty: String?): String? =
        when (preferences.getString(PREF_TITLE, "english")) {
            "japanese" -> japanese?.takeIf { it.isNotBlank() } ?: english
            "pretty" -> pretty?.takeIf { it.isNotBlank() } ?: english
            else -> english?.takeIf { it.isNotBlank() } ?: japanese
        }

    override fun getFilterList() = FilterList(
        Filter.Header("Search box: nhentai syntax, e.g. tag:big-breasts -tag:futanari character:hatsune-miku"),
        NhSortFilter(),
        NhCategoryFilter(),
        Filter.Separator(),
        Filter.Header("Separate with , or ; and prefix with - to exclude"),
        NhTermFilter("Tags", "tag"),
        NhTermFilter("Artists", "artist"),
        NhTermFilter("Groups", "group"),
        NhTermFilter("Parodies", "parody"),
        NhTermFilter("Characters", "character"),
    )

    // ============================== Details ==============================

    private fun SManga.galleryId() = url.trim('/').substringAfterLast('/')

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/galleries/${manga.galleryId()}", headers)

    override fun mangaDetailsParse(response: Response): SManga =
        galleryToManga(parseJson(response)).apply { initialized = true }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/g/${manga.galleryId()}/"

    private fun galleryToManga(g: JsonObject): SManga = SManga.create().apply {
        val id = g.str("id")!!
        val mediaId = g.str("media_id").orEmpty()
        val titles = g.obj("title")
        val english = titles?.str("english")
        val japanese = titles?.str("japanese")
        url = "/g/$id/"
        title = pickTitle(english, japanese, titles?.str("pretty")) ?: id
        (g.obj("cover") ?: g.obj("thumbnail"))?.str("path")?.let { thumbnail_url = thumbUrl(it, mediaId) }

        val tags = g.arr("tags").mapNotNull { it.jsonObjectOrNull() }
        fun ofType(type: String) = tags.filter { it.str("type") == type }.mapNotNull { it.str("name") }

        val artists = ofType("artist")
        val groups = ofType("group")
        artist = artists.joinToString().ifEmpty { null }
        author = groups.ifEmpty { artists }.joinToString().ifEmpty { null }
        genre = (ofType("category") + ofType("tag")).joinToString()

        description = buildString {
            listOf(english, japanese).filter { !it.isNullOrBlank() && it != title }
                .forEach { append(it, "\n") }
            ofType("parody").takeIf { it.isNotEmpty() }?.let { append("Parodies: ", it.joinToString(), "\n") }
            ofType("character").takeIf { it.isNotEmpty() }?.let { append("Characters: ", it.joinToString(), "\n") }
            ofType("language").takeIf { it.isNotEmpty() }?.let { append("Languages: ", it.joinToString(), "\n") }
            append("Pages: ${g.str("num_pages") ?: "?"}\n")
            g.str("num_favorites")?.let { append("Favorites: $it\n") }
            append("ID: $id")
        }

        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    // ============================== Chapters =============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = chaptersFrom(parseJson(response))

    private fun chaptersFrom(g: JsonObject): List<SChapter> {
        val id = g.str("id")!!
        val tags = g.arr("tags").mapNotNull { it.jsonObjectOrNull() }
        return listOf(
            SChapter.create().apply {
                url = "/g/$id/"
                name = "Chapter"
                chapter_number = 1f
                date_upload = (g.str("upload_date")?.toLongOrNull() ?: 0L) * 1000
                scanlator = g.str("scanlator")?.takeIf { it.isNotBlank() }
                    ?: tags.filter { it.str("type") == "group" }.mapNotNull { it.str("name") }.joinToString().ifEmpty { null }
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // ============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$apiUrl/galleries/${chapter.url.trim('/').substringAfterLast('/')}", headers)

    override fun pageListParse(response: Response): List<Page> = pagesFrom(parseJson(response))

    private fun pagesFrom(g: JsonObject): List<Page> {
        val mediaId = g.str("media_id").orEmpty()
        return g.arr("pages").mapNotNull { it.jsonObjectOrNull()?.str("path") }
            .mapIndexed { i, path -> Page(i, imageUrl = imageUrl(path, mediaId)) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Settings =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TITLE
            title = "Title display"
            entries = arrayOf("English (full)", "Japanese", "Short (pretty)")
            entryValues = arrayOf("english", "japanese", "pretty")
            setDefaultValue("english")
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_POPULAR
            title = "Popular period"
            entries = arrayOf("Today", "Week", "Month", "All time")
            entryValues = arrayOf("popular-today", "popular-week", "popular-month", "popular")
            setDefaultValue("popular-week")
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_TITLE = "pref_title_display"
        private const val PREF_POPULAR = "pref_popular_period"

        private val SERVER_HOST = Regex("""[it]\d\.nhentai\.net""")

        // 14 instead of 15 to keep a margin with the server's own clock
        private val RATE_LIMITER = SlidingWindowLimiter(14, 60_000L)
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private val GALLERY_CACHE = LinkedHashMap<String, Pair<Long, JsonObject>>()
        private val IMAGE_SERVERS = (1..4).map { "https://i$it.nhentai.net" }
        private val THUMB_SERVERS = (1..4).map { "https://t$it.nhentai.net" }

        private val ID_REGEXES = listOf(
            Regex("""^(?:id:)?(\d+)$"""),
            Regex("""nhentai\.net/g/(\d+)"""),
        )
    }
}

private class NhSortFilter : Filter.Select<String>("Sort by", SORTS.map { it.first }.toTypedArray()) {
    val selected get() = SORTS[state].second

    companion object {
        val SORTS = listOf(
            "Recent" to "date",
            "Popular: Today" to "popular-today",
            "Popular: Week" to "popular-week",
            "Popular: Month" to "popular-month",
            "Popular: All time" to "popular",
        )
    }
}

private class NhCategoryFilter : Filter.Select<String>("Category", CATEGORIES.map { it.first }.toTypedArray()) {
    val selected get() = CATEGORIES[state].second

    companion object {
        val CATEGORIES = listOf("Any" to "", "Doujinshi" to "doujinshi", "Manga" to "manga")
    }
}

/** "Hatsune Miku" -> "hatsune-miku": the only form that works for every namespace (quotes fail for characters). */
private fun nhSlug(value: String) = value.trim().lowercase().replace("\"", "").replace(Regex("""[\s_]+"""), "-")

/** Search box: `character:"hatsune miku"` -> `character:hatsune-miku` */
private fun normalizeNhQuery(query: String) =
    query.replace(Regex("""(-?\w+):"([^"]+)"""")) { "${it.groupValues[1]}:${nhSlug(it.groupValues[2])}" }

private class NhTermFilter(name: String, private val namespace: String) : Filter.Text(name) {
    /** "big breasts, -futanari" -> tag:big-breasts -tag:futanari */
    fun terms(): List<String> = state.split(',', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { value ->
            val exclude = value.startsWith("-")
            "${if (exclude) "-" else ""}$namespace:${nhSlug(value.removePrefix("-"))}"
        }
}
