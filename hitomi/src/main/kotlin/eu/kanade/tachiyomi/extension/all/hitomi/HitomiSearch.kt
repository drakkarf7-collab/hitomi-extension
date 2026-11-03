package eu.kanade.tachiyomi.extension.all.hitomi

import eu.kanade.tachiyomi.extension.all.hitomi.HitomiImages.Companion.LTN
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Port of Hitomi's search (search.js / results.js / galleryblock.js).
 *
 * Gallery ids are stored in ".nozomi" files: a flat list of big-endian int32.
 * Free-text words are looked up in a B-tree index (galleriesindex) keyed by sha256(word)[0..4].
 */
class HitomiSearch(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    /** Search state, mirrors `state` in results.js. */
    data class State(
        val area: String = "all",
        val tag: String = "index",
        val language: String = "all",
        val orderBy: String = "date",
        val orderByKey: String = "added",
    )

    class IdPage(val ids: List<Int>, val total: Int)

    // ---- nozomi -------------------------------------------------------------

    fun nozomiUrl(state: State): HttpUrl {
        val builder = LTN.toHttpUrl().newBuilder().addPathSegment("n")
        if (state.orderBy != "date" || state.orderByKey == "published") {
            if (state.area == "all") {
                builder.addPathSegment(state.orderBy)
                    .addPathSegment("${state.orderByKey}-${state.language}.nozomi")
            } else {
                builder.addPathSegment(state.area)
                    .addPathSegment(state.orderBy)
                    .addPathSegment(state.orderByKey)
                    .addPathSegment("${state.tag}-${state.language}.nozomi")
            }
        } else {
            if (state.area != "all") builder.addPathSegment(state.area)
            builder.addPathSegment("${state.tag}-${state.language}.nozomi")
        }
        return builder.build()
    }

    /** One page of a nozomi file using a Range request (used for browse & single-term searches). */
    fun nozomiPage(state: State, page: Int): IdPage {
        val start = (page - 1) * PER_PAGE * 4
        val end = start + PER_PAGE * 4 - 1
        val reqHeaders = headers.newBuilder().set("Range", "bytes=$start-$end").set("Accept-Encoding", "identity").build()
        client.newCall(GET(nozomiUrl(state), reqHeaders)).execute().use { response ->
            if (response.code == 416 || response.code == 404) return IdPage(emptyList(), 0)
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val bytes = response.body.bytes()
            val total = response.header("Content-Range")
                ?.substringAfterLast('/')?.toIntOrNull()?.div(4)
                ?: (bytes.size / 4)
            return IdPage(bytes.toIntList(), total)
        }
    }

    private fun nozomiAll(state: State, attempt: Int = 1): List<Int> {
        val reqHeaders = headers.newBuilder().set("Accept-Encoding", "identity").build()
        val bytes = try {
            client.newCall(GET(nozomiUrl(state), reqHeaders)).execute().use { response ->
                if (response.code == 404) return emptyList()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                response.body.bytes()
            }
        } catch (e: java.io.IOException) {
            // Large files sometimes get cut by the CDN, retry a couple of times
            if (attempt < 3) return nozomiAll(state, attempt + 1) else throw e
        }
        return bytes.toIntList()
    }

    // ---- query parsing ------------------------------------------------------

    class ParsedQuery(
        val base: State,
        val positive: List<String>,
        val negative: List<String>,
        val orGroups: List<List<String>>,
        val ascending: Boolean,
        val random: Boolean,
    ) {
        /** True when the whole query maps to a single nozomi file, so paging can use Range requests. */
        val isSimple get() = negative.isEmpty() && orGroups.isEmpty() && !ascending && !random &&
            (positive.isEmpty() || (positive.size == 1 && positive[0].contains(':')))
    }

    fun parse(query: String, language: String, orderBy: String, orderByKey: String): ParsedQuery {
        val terms = query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val positive = mutableListOf<String>()
        val negative = mutableListOf<String>()
        val orGroups = mutableListOf(mutableListOf<String>())
        var ob = orderBy
        var obk: String? = orderByKey.ifEmpty { null }
        var ascending = false
        var random = false

        terms.forEachIndexed { i, raw ->
            val term = raw.replace('_', ' ')
            if (Regex("^(?:sort|order)by(?:key|direction)?:").containsMatchIn(term)) {
                val left = term.substringBefore(':')
                val right = term.substringAfter(':')
                when {
                    Regex("^(?:sort|order)(?:by)?key$").matches(left) -> obk = right.replace(Regex("[^0-9a-z]"), "")
                    right == "popular" || right == "popularity" -> ob = "popular"
                    right == "date" -> ob = "date"
                    right == "datepublished" -> { ob = "date"; obk = "published" }
                    right == "random" || right == "rand" -> random = true
                    right == "asc" || right == "ascending" -> ascending = true
                }
                return@forEachIndexed
            }
            if (term == "or") return@forEachIndexed
            val orPrev = i > 0 && terms[i - 1] == "or"
            val orNext = i + 1 < terms.size && terms[i + 1] == "or"
            if (orPrev || orNext) {
                orGroups.last().add(term)
                if (!orNext) orGroups.add(mutableListOf())
                return@forEachIndexed
            }
            if (term.startsWith("-")) negative.add(term.removePrefix("-")) else positive.add(term)
        }

        // Tags (with ':') first, like the site does.
        positive.sortBy { if (it.contains(':')) 0 else 1 }

        if (obk == null) obk = if (ob == "popular") "year" else "added"

        return ParsedQuery(
            base = State(language = language, orderBy = ob, orderByKey = obk!!),
            positive = positive,
            negative = negative,
            orGroups = orGroups.filter { it.isNotEmpty() },
            ascending = ascending,
            random = random,
        )
    }

    /** State for a single tag-like term ("female:big breasts", "artist:xyz", "language:french"...). */
    fun stateForTerm(term: String, base: State): State {
        val left = term.substringBefore(':')
        val right = term.substringAfter(':')
        return when (left) {
            "female", "male" -> base.copy(area = "tag", tag = term)
            "language" -> base.copy(language = right)
            else -> base.copy(area = left, tag = right)
        }
    }

    /** Full search, returns every matching gallery id in order. */
    fun search(q: ParsedQuery): List<Int> {
        val positive = q.positive.toMutableList()
        var results: List<Int> =
            if (positive.isEmpty() || (!positive[0].contains(':') && q.base.orderByKey != "added")) {
                nozomiAll(q.base)
            } else {
                idsForTerm(positive.removeAt(0), q.base)
            }

        if (q.ascending) results = results.reversed()
        if (q.random) results = results.shuffled()

        for (group in q.orGroups) {
            val set = HashSet<Int>()
            group.forEach { set.addAll(idsForTerm(it, q.base)) }
            results = results.filter { it in set }
        }
        for (term in positive) {
            val set = idsForTerm(term, q.base).toHashSet()
            results = results.filter { it in set }
        }
        for (term in q.negative) {
            val set = idsForTerm(term, q.base).toHashSet()
            results = results.filter { it !in set }
        }
        return results
    }

    private fun idsForTerm(term: String, base: State): List<Int> {
        val t = term.replace('_', ' ')
        if (t.contains(':')) return nozomiAll(stateForTerm(t, base))
        return galleryIdsForWord(t)
    }

    // ---- galleries B-tree index ---------------------------------------------

    private class Node(val keys: List<ByteArray>, val datas: List<Pair<Long, Int>>, val subNodes: List<Long>)

    @Volatile
    private var indexVersion: Pair<String, Long>? = null

    private fun galleriesIndexVersion(): String {
        val cached = indexVersion
        if (cached != null && System.currentTimeMillis() - cached.second < 10 * 60 * 1000L) return cached.first
        val version = client.newCall(GET("$LTN/galleriesindex/version?_=${System.currentTimeMillis()}", headers))
            .execute().use { it.body.string().trim() }
        indexVersion = version to System.currentTimeMillis()
        return version
    }

    private fun rangeBytes(url: String, start: Long, end: Long): ByteArray {
        val reqHeaders = headers.newBuilder().set("Range", "bytes=$start-$end").set("Accept-Encoding", "identity").build()
        client.newCall(GET(url, reqHeaders)).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body.bytes()
        }
    }

    private fun decodeNode(data: ByteArray): Node {
        val buf = ByteBuffer.wrap(data) // big-endian by default
        val keys = List(buf.int) {
            val size = buf.int
            require(size in 1..32) { "Invalid key size" }
            ByteArray(size).also { buf.get(it) }
        }
        val datas = List(buf.int) { buf.long to buf.int }
        val subNodes = List(B + 1) { buf.long }
        return Node(keys, datas, subNodes)
    }

    private fun nodeAt(version: String, address: Long): Node {
        val url = "$LTN/galleriesindex/galleries.$version.index"
        return decodeNode(rangeBytes(url, address, address + MAX_NODE_SIZE - 1))
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toInt() and 0xFF
            val y = b[i].toInt() and 0xFF
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    private fun bSearch(version: String, key: ByteArray, start: Node): Pair<Long, Int>? {
        var node = start
        while (true) {
            if (node.keys.isEmpty()) return null
            var cmp = -1
            var i = 0
            while (i < node.keys.size) {
                cmp = compare(key, node.keys[i])
                if (cmp <= 0) break
                i++
            }
            if (cmp == 0) return node.datas[i]
            if (node.subNodes.all { it == 0L }) return null
            val address = node.subNodes[i]
            if (address == 0L) return null
            node = nodeAt(version, address)
        }
    }

    private fun galleryIdsForWord(word: String): List<Int> {
        val version = galleriesIndexVersion()
        val key = MessageDigest.getInstance("SHA-256").digest(word.toByteArray()).copyOfRange(0, 4)
        val (offset, length) = bSearch(version, key, nodeAt(version, 0)) ?: return emptyList()
        if (length <= 0 || length > 100_000_000) return emptyList()

        val data = rangeBytes("$LTN/galleriesindex/galleries.$version.data", offset, offset + length - 1)
        val buf = ByteBuffer.wrap(data)
        val count = buf.int
        if (count <= 0 || data.size != count * 4 + 4) return emptyList()
        return List(count) { buf.int }
    }

    companion object {
        const val PER_PAGE = 25
        private const val B = 16
        private const val MAX_NODE_SIZE = 464

        private fun ByteArray.toIntList(): List<Int> {
            val buf = ByteBuffer.wrap(this)
            return List(size / 4) { buf.int }
        }
    }
}
