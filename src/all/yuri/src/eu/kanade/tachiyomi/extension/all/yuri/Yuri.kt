package eu.kanade.tachiyomi.extension.all.yuri

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable
import java.util.regex.Pattern
import kotlin.math.min

class Yuri : HttpSource() {
    override val name = "Yuri"
    override val baseUrl = "https://yuri.grass.moe"
    override val supportsLatest = false
    override val lang = "all"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/api/all.json")
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return client.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { response ->
                val j = Json.parseToJsonElement(response.body.string()).jsonArray
                parseManga(j, page - 1)
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("${baseUrl}/api/all.json")
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response ->
                searchMangaParse(response, query, page - 1)
            }
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET("${baseUrl}/api${manga.url}")
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val j = Json.parseToJsonElement(response.body.string()).jsonObject

        return j["chapters"]?.jsonArray?.map {
            val chapter_j = it.jsonObject
            val url = chapter_j["url"]?.jsonPrimitive?.content ?: ""
            val title = chapter_j["title"]?.jsonPrimitive?.content ?: ""
            val date = chapter_j["date"]?.jsonPrimitive?.long ?: 0L
            val number = chapter_j["number"]?.jsonPrimitive?.float ?: 0f
            val scanlator = chapter_j["scanlator"]?.jsonPrimitive?.content ?: ""

            SChapter.create().apply {
                    this.name = title
                    this.url = url
                    this.date_upload = date
                    this.chapter_number = number
                    this.scanlator = scanlator
            }
        } ?: emptyList()
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map { response ->
                pageListParse(response, chapter)
            }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("${baseUrl}/api${chapter.url}")
    }

    override fun pageListParse(response: Response): List<Page> {
        throw Exception("Unused")
    }

    private fun pageListParse(response: Response, @Suppress("UNUSED_PARAMETER") chapter: SChapter): List<Page> {
        val j = Json.parseToJsonElement(response.body.string()).jsonObject
        return j["files"]?.jsonArray?.mapIndexed { index, e ->
            Page(index + 1, "", "${baseUrl}${e.jsonPrimitive.content}")
        } ?: emptyList()
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(GET("${baseUrl}/api${manga.url}", headers))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response)
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("${baseUrl}/api${manga.url}")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val j = Json.parseToJsonElement(response.body.string()).jsonObject

        val url =  j["url"]?.jsonPrimitive?.content ?: ""
        val title = j["title"]?.jsonPrimitive?.content ?: ""
        val artist = j["artist"]?.jsonPrimitive?.content ?: ""
        val author = j["author"]?.jsonPrimitive?.content ?: ""
        val description = j["description"]?.jsonPrimitive?.content ?: ""
        val genre = j["genre"]?.jsonPrimitive?.content ?: ""
        val status = j["status"]?.jsonPrimitive?.int ?: 0
        val thumbnail_url = j["thumbnail_url"]?.jsonPrimitive?.content ?: ""

        return SManga.create().apply {
            this.url = url
            this.title = title
            this.artist = artist
            this.author = author
            this.description = description
            this.genre = genre
            this.status = status
            this.thumbnail_url = "${baseUrl}$thumbnail_url"
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        throw Exception("Unused.")
    }

    fun match_query(str: String, query: ArrayList<String>): Boolean {
        for (tag in query) {
            if (!str.contains(tag)) {
                return false
            }
        }

        return true
    }

    fun searchMangaParse(response: Response, query: String, page: Int): MangasPage {
        val json = JSONArray(response.body.string())

        val include = ArrayList<String>()
        val exclude = ArrayList<String>()
        val matcher = Pattern.compile("""((?:-){0,1}(?:\"(?:\\(?:\\\\)*\")+(?:[^\\](?:\\(?:\\\\)*\")+|[^\"])*\"|\"(?:[^\\](?:\\(?:\\\\)*\")+|[^\"])*\"|[^ ]+))""")
            .matcher(query)
        while (matcher.find()) {
            var term = matcher.group().lowercase()
            var is_include = true
            if (term.startsWith("-")) {
                term = term.drop(1)
                is_include = false
            }

            if (term.length > 1 && term.startsWith("\"") && term.endsWith("\"")) {
                term = term.drop(1).dropLast(1)
            }

            term = term.replace("\\\"", "\"")

            if (term.length <= 0) continue

            if (is_include) {
                include.add(term)
            } else {
                exclude.add(term)
            }
        }

        val mangas = ArrayList<SManga>()
        val filtered = ArrayList<JSONObject>()

        for (i in 0 until json.length()) {
            val candidate = json.getString(i)
            if (include.size > 0 && !include.all({ term -> candidate.contains(term, true) }) ||
                exclude.size > 0 && exclude.any({ term -> candidate.contains(term, true) })
            ) {
                continue
            }

            filtered.add(JSONObject(candidate))
        }

        val dropped = filtered.drop(page * 32)
        dropped.take(32).forEach {
            val url = it.getString("url")
            val title = it.getString("title")
            val artist = it.getString("artist")
            val author = it.getString("author")
            val genre = it.getString("genre")
            val status = it.getInt("status")
            val thumbnail_url = it.getString("thumbnail_url")

            mangas.add(
                SManga.create().apply {
                    this.url = url
                    this.title = title
                    this.artist = artist
                    this.author = author
                    this.genre = genre
                    this.status = status
                    this.thumbnail_url = "${baseUrl}$thumbnail_url"
                }
            )
        }

        return MangasPage(mangas, dropped.size > 32)
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used.")
    }

    private fun parseManga(payload: JsonArray, page: Int): MangasPage {
        val mangas = ArrayList<SManga>()

        for (i in min(page * 32, payload.size) until min((page + 1) * 32, payload.size)) {
            val j = payload.get(i).jsonObject
            val url = j["url"]?.jsonPrimitive?.content ?: ""
            val title = j["title"]?.jsonPrimitive?.content ?: ""
            val author = j["author"]?.jsonPrimitive?.content ?: ""
            val artist = j["artist"]?.jsonPrimitive?.content ?: ""
            val thumbnail_url = j["thumbnail_url"]?.jsonPrimitive?.content ?: ""
            val genre = j["genre"]?.jsonPrimitive?.content ?: ""

            mangas.add(
                SManga.create().apply {
                    this.url = url
                    this.title = title
                    this.author = author
                    this.artist = artist
                    this.thumbnail_url = "${baseUrl}$thumbnail_url"
                    this.genre = genre
                }
            )
        }

        return MangasPage(mangas, (page + 1) * 32 <= payload.size)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return popularMangaRequest(page)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        throw Exception("Unused")
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        throw Exception("Unused")
    }
}
