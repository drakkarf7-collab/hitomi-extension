package eu.kanade.tachiyomi.extension.all.hitomi

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Schale Network (niyaniya.moe), the site formerly known as anchira.to then koharu.to.
 *
 * Browsing (latest, popular, gallery details) is public. Searching and reading pages need the
 * "clearance" token the site gives after its Cloudflare Turnstile check. The extension does not
 * solve that check: the user passes it in their own browser and pastes the token in the settings.
 */
class SchaleNetwork : HttpSource(), ConfigurableSource {

    override val name = "Schale Network (Anchira)"

    override val lang = "all"

    override val baseUrl = "https://niyaniya.moe"

    private val apiUrl = "https://api.schale.network"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client by lazy { network.cloudflareClient }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private fun token(): String? = preferences.getString(PREF_TOKEN, null)
        ?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }

    private fun requireToken(what: String): String = token() ?: throw Exception(
        "$what needs your Schale clearance token: open niyaniya.moe in your browser, " +
            "pass the check, then paste the token in this source's settings (see README)",
    )

    // ============================== Browse ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/books/popular?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/books?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/books".toHttpUrl().newBuilder()
        val terms = mutableListOf<String>()
        if (query.isNotBlank()) terms.add(query.trim())
        var sort = ""
        filters.forEach { filter ->
            when (filter) {
                is SchaleSortFilter -> sort = filter.selected
                is SchaleTermFilter -> terms.addAll(filter.terms())
                else -> {}
            }
        }
        if (terms.isNotEmpty()) {
            url.addQueryParameter("s", terms.joinToString(" "))
            url.addQueryParameter("crt", requireToken("Searching"))
        }
        if (sort.isNotEmpty()) url.addQueryParameter("sort", sort)
        url.addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList() = FilterList(
        Filter.Header("Searching needs your clearance token (see settings)"),
        SchaleSortFilter(),
        Filter.Separator(),
        Filter.Header("Separate with , or ; and prefix with - to exclude"),
        SchaleTermFilter("Tags", "tag"),
        SchaleTermFilter("Female tags", "female"),
        SchaleTermFilter("Male tags", "male"),
        SchaleTermFilter("Artists", "artist"),
        SchaleTermFilter("Circles", "circle"),
        SchaleTermFilter("Parodies", "parody"),
        SchaleTermFilter("Characters", "character"),
    )

    private fun parseJson(response: Response): JsonObject {
        if (response.code == 401 || response.code == 403 || response.code == 400) {
            response.close()
            val hint = if (token() == null) "a clearance token" else "a new clearance token (yours expired)"
            throw Exception("Schale Network refused the request (HTTP ${response.code}): paste $hint in the settings")
        }
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Schale Network: HTTP ${response.code}")
        }
        return Json.parseToJsonElement(response.body.string()).jsonObject
    }

    private fun parseList(response: Response): MangasPage {
        val json = parseJson(response)
        val mangas = json.arr("entries").mapNotNull { it.jsonObjectOrNull() }.map { e ->
            SManga.create().apply {
                url = "/g/${e.str("id")}/${e.str("key")}"
                title = e.str("title") ?: e.str("id").orEmpty()
                thumbnail_url = e.obj("thumbnail")?.str("path")
            }
        }
        val page = json.str("page")?.toIntOrNull() ?: 1
        val limit = json.str("limit")?.toIntOrNull() ?: 25
        val total = json.str("total")?.toIntOrNull() ?: 0
        return MangasPage(mangas, page * limit < total)
    }

    // ============================== Details ==============================

    private fun SManga.idKey() = url.trim('/').split('/').let { it[1] to it[2] }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val (id, key) = manga.idKey()
        return GET("$apiUrl/books/detail/$id/$key", headers)
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val g = parseJson(response)
        url = "/g/${g.str("id")}/${g.str("key")}"
        title = g.str("title").orEmpty()
        g.obj("thumbnails")?.let { t ->
            val base = t.str("base").orEmpty()
            t.obj("main")?.str("path")?.let { thumbnail_url = base + it }
        }

        val tags = g.arr("tags").mapNotNull { it.jsonObjectOrNull() }
        fun ns(n: Int) = tags.filter { (it.str("namespace")?.toIntOrNull() ?: 0) == n }.mapNotNull { it.str("name") }

        artist = ns(NS_ARTIST).joinToString().ifEmpty { null }
        author = ns(NS_CIRCLE).ifEmpty { ns(NS_ARTIST) }.joinToString().ifEmpty { null }
        genre = (ns(NS_GENERAL) + ns(NS_FEMALE) + ns(NS_MALE) + ns(NS_MIXED) + ns(NS_OTHER)).joinToString()
        description = buildString {
            ns(NS_PARODY).takeIf { it.isNotEmpty() }?.let { append("Parodies: ", it.joinToString(), "\n") }
            ns(NS_CHARACTER).takeIf { it.isNotEmpty() }?.let { append("Characters: ", it.joinToString(), "\n") }
            ns(NS_MAGAZINE).takeIf { it.isNotEmpty() }?.let { append("Magazine: ", it.joinToString(), "\n") }
            ns(NS_LANGUAGE).takeIf { it.isNotEmpty() }?.let { append("Language: ", it.joinToString(), "\n") }
            g.obj("statistic")?.let { s -> append("Views: ${s.str("views") ?: "?"}, favorites: ${s.str("favorites") ?: "?"}\n") }
            append("ID: ${g.str("id")}")
        }
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
    }

    // ============================== Chapters =============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val g = parseJson(response)
        return listOf(
            SChapter.create().apply {
                url = "/g/${g.str("id")}/${g.str("key")}"
                name = "Chapter"
                chapter_number = 1f
                date_upload = g.str("created_at")?.toLongOrNull() ?: 0L
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // ============================== Pages ================================

    /**
     * Same two calls as the site's reader:
     *  POST /books/detail/{id}/{key}?crt=      -> available resolutions, each with its own id/key
     *  GET  /books/data/{id}/{key}/{dataId}/{dataKey}/{resolution}?crt= -> image base url + paths
     */
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val crt = requireToken("Reading")
        val (id, key) = chapter.url.trim('/').split('/').let { it[1] to it[2] }

        val extraRequest = Request.Builder()
            .url("$apiUrl/books/detail/$id/$key?crt=$crt")
            .headers(headers)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val resolutions = parseJson(client.newCall(extraRequest).execute()).obj("data")
            ?: throw Exception("Schale Network: no image data returned")

        val wanted = preferences.getString(PREF_QUALITY, "0")!!
        val resolution = when {
            resolutions.containsKey(wanted) -> wanted
            // highest available, original ("0") counts as best
            resolutions.containsKey("0") -> "0"
            else -> resolutions.keys.maxByOrNull { it.toIntOrNull() ?: 0 }
        } ?: throw Exception("Schale Network: no resolution available")
        val data = resolutions.obj(resolution)!!

        val images = parseJson(
            client.newCall(
                GET("$apiUrl/books/data/$id/$key/${data.str("id")}/${data.str("key")}/$resolution?crt=$crt", headers),
            ).execute(),
        )
        val base = images.str("base").orEmpty()
        images.arr("entries").mapNotNull { it.jsonObjectOrNull()?.str("path") }
            .mapIndexed { i, path -> Page(i, imageUrl = if (path.startsWith("http")) path else base + path) }
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Settings =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_TOKEN
            title = "Clearance token"
            summary = "Needed to search and read. Get it from niyaniya.moe in your browser (see the README). " +
                "Paste a new one when you get an \"expired\" error."
            dialogTitle = "Paste your clearance token"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "Image quality"
            entries = arrayOf("Original", "1600px", "1280px", "980px", "780px")
            entryValues = arrayOf("0", "1600", "1280", "980", "780")
            setDefaultValue("0")
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_TOKEN = "pref_clearance_token"
        private const val PREF_QUALITY = "pref_image_quality"

        // Namespace ids from the site's frontend
        private const val NS_GENERAL = 0
        private const val NS_ARTIST = 1
        private const val NS_CIRCLE = 2
        private const val NS_PARODY = 3
        private const val NS_MAGAZINE = 4
        private const val NS_CHARACTER = 5
        private const val NS_MALE = 8
        private const val NS_FEMALE = 9
        private const val NS_MIXED = 10
        private const val NS_LANGUAGE = 11
        private const val NS_OTHER = 12
    }
}

private class SchaleSortFilter : Filter.Select<String>("Sort by", SORTS.map { it.first }.toTypedArray()) {
    val selected get() = SORTS[state].second

    companion object {
        // Sort ids from the site's frontend (Title=2, Pages=3, Date=4, Views=8, Favorites=9)
        val SORTS = listOf(
            "Date" to "",
            "Views" to "8",
            "Favorites" to "9",
            "Title" to "2",
            "Pages" to "3",
        )
    }
}

private class SchaleTermFilter(name: String, private val namespace: String) : Filter.Text(name) {
    fun terms(): List<String> = state.split(',', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { value ->
            val exclude = value.startsWith("-")
            val clean = value.removePrefix("-").trim()
            "${if (exclude) "-" else ""}$namespace:\"$clean\""
        }
}
