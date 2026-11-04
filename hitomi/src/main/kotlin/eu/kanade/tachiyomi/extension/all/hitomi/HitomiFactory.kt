package eu.kanade.tachiyomi.extension.all.hitomi

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class HitomiFactory : SourceFactory {
    override fun createSources(): List<Source> =
        LANGUAGES.map { (lang, hitomiLang) -> Hitomi(lang, hitomiLang) } +
            NHENTAI_LANGUAGES.map { (lang, nhLang) -> NHentai(lang, nhLang) } +
            SchaleNetwork()

    companion object {
        /** App language code to Hitomi language name. */
        val LANGUAGES = listOf(
            "all" to "all",
            "en" to "english",
            "ja" to "japanese",
            "zh" to "chinese",
            "ko" to "korean",
            "fr" to "french",
            "es" to "spanish",
            "de" to "german",
            "it" to "italian",
            "pt-BR" to "portuguese",
            "ru" to "russian",
            "pl" to "polish",
            "id" to "indonesian",
            "vi" to "vietnamese",
            "th" to "thai",
            "tr" to "turkish",
            "uk" to "ukrainian",
            "ar" to "arabic",
            "hu" to "hungarian",
            "cs" to "czech",
            "nl" to "dutch",
            "tl" to "tagalog",
        )

        /** nhentai only has these three languages. */
        val NHENTAI_LANGUAGES = listOf(
            "all" to "",
            "en" to "english",
            "ja" to "japanese",
            "zh" to "chinese",
        )
    }
}
