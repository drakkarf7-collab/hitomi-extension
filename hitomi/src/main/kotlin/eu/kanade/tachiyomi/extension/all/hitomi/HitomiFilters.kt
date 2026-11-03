package eu.kanade.tachiyomi.extension.all.hitomi

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class SortFilter : Filter.Select<String>("Sort by", SORTS.map { it.first }.toTypedArray()) {
    /** (orderBy, orderByKey) */
    val selected get() = SORTS[state].second

    companion object {
        val SORTS = listOf(
            "Date added" to ("date" to "added"),
            "Date published" to ("date" to "published"),
            "Popular: Today" to ("popular" to "today"),
            "Popular: Week" to ("popular" to "week"),
            "Popular: Month" to ("popular" to "month"),
            "Popular: Year" to ("popular" to "year"),
        )
    }
}

class TypeFilter : Filter.Select<String>("Type", TYPES.map { it.first }.toTypedArray()) {
    val selected get() = TYPES[state].second

    companion object {
        val TYPES = listOf(
            "Any" to "",
            "Doujinshi" to "doujinshi",
            "Manga" to "manga",
            "Artist CG" to "artistcg",
            "Game CG" to "gamecg",
            "Image Set" to "imageset",
            "Anime" to "anime",
        )
    }
}

open class TermFilter(name: String, val prefix: String) : Filter.Text(name) {
    /**
     * Values separated by "," or ";" (or spaces between "ns:value" tags), "-value" excludes.
     * Spaces inside a value become "_" like on the site.
     */
    fun terms(): List<String> = state.split(',', ';')
        .flatMap { it.trim().split(Regex("""\s+(?=-?[a-z]+:)""")) }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { value ->
            val exclude = value.startsWith("-")
            val clean = value.removePrefix("-").trim().replace(' ', '_')
            val term = if (prefix.isEmpty() || clean.contains(':')) clean else "$prefix:$clean"
            if (exclude) "-$term" else term
        }
}

class TagFilter : TermFilter("Tags (e.g. female:monster girl, male:glasses, -female:futanari)", "tag")
class ArtistFilter : TermFilter("Artists", "artist")
class GroupFilter : TermFilter("Groups", "group")
class SeriesFilter : TermFilter("Series", "series")
class CharacterFilter : TermFilter("Characters", "character")

fun hitomiFilters() = FilterList(
    Filter.Header("Search box uses the site syntax, _ for spaces: female:monster_girl -male:x language:french"),
    SortFilter(),
    TypeFilter(),
    Filter.Separator(),
    Filter.Header("Separate with , or ; and prefix with - to exclude"),
    TagFilter(),
    ArtistFilter(),
    GroupFilter(),
    SeriesFilter(),
    CharacterFilter(),
)
