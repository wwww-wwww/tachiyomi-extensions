package eu.kanade.tachiyomi.extension.all.yuri

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
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

@Source
abstract class Yuri : HttpSource() {
//    override val name = "Yuri"
//    override val baseUrl = "https://yuri.grass.moe"
    override val supportsLatest = false
//    override val lang = "all"

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/all.json")

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = client.newCall(popularMangaRequest(page))
        .asObservableSuccess()
        .map { response ->
            val j = Json.parseToJsonElement(response.body.string()).jsonArray
            parseManga(j, page - 1)
        }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/api/all.json")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = client.newCall(searchMangaRequest(page, query, filters))
        .asObservableSuccess()
        .map { response ->
            searchMangaParse(response, query, page - 1)
        }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/api${manga.url}")

    override fun chapterListParse(response: Response): List<SChapter> {
        val j = Json.parseToJsonElement(response.body.string()).jsonObject

        return j["chapters"]?.jsonArray?.map {
            val chapterJ = it.jsonObject
            val url = chapterJ["url"]?.jsonPrimitive?.content ?: ""
            val title = chapterJ["title"]?.jsonPrimitive?.content ?: ""
            val date = chapterJ["date"]?.jsonPrimitive?.long ?: 0L
            val number = chapterJ["number"]?.jsonPrimitive?.float ?: 0f
            val scanlator = chapterJ["scanlator"]?.jsonPrimitive?.content ?: ""

            SChapter.create().apply {
                this.name = title
                this.url = url
                this.date_upload = date
                this.chapter_number = number
                this.scanlator = scanlator
            }
        } ?: emptyList()
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = client.newCall(pageListRequest(chapter))
        .asObservableSuccess()
        .map { response ->
            pageListParse(response, chapter)
        }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/api${chapter.url}")

    override fun pageListParse(response: Response): List<Page> = throw Exception("Unused")

    private fun pageListParse(response: Response, @Suppress("UNUSED_PARAMETER") chapter: SChapter): List<Page> {
        val j = Json.parseToJsonElement(response.body.string()).jsonObject
        return j["files"]?.jsonArray?.mapIndexed { index, e ->
            Page(index + 1, "", "${baseUrl}${e.jsonPrimitive.content}")
        } ?: emptyList()
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = client.newCall(GET("$baseUrl/api${manga.url}", headers))
        .asObservableSuccess()
        .map { response ->
            mangaDetailsParse(response)
        }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/api${manga.url}")

    @SuppressLint("DefaultLocale")
    override fun mangaDetailsParse(response: Response): SManga {
        val j = Json.parseToJsonElement(response.body.string()).jsonObject

        val url = j["url"]?.jsonPrimitive?.content ?: ""
        val title = j["title"]?.jsonPrimitive?.content ?: ""
        val artist = j["artist"]?.jsonPrimitive?.content ?: ""
        val author = j["author"]?.jsonPrimitive?.content ?: ""
        val description = j["description"]?.jsonPrimitive?.content ?: ""
        val genre = j["genre"]?.jsonPrimitive?.content ?: ""
        val status = j["status"]?.jsonPrimitive?.int ?: 0
        val thumbnailUrl = j["thumbnail_url"]?.jsonPrimitive?.content ?: ""

        return SManga.create().apply {
            this.url = url
            this.title = title
            this.artist = artist
            this.author = author
            this.description = description
            this.genre = genre
            this.status = status
            this.thumbnail_url = "${baseUrl}$thumbnailUrl"
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = throw Exception("Unused.")

    fun searchMangaParse(response: Response, query: String, page: Int): MangasPage {
        val json = JSONArray(response.body.string())

        val include = ArrayList<String>()
        val exclude = ArrayList<String>()
        val matcher = Pattern.compile("""((?:-){0,1}(?:\"(?:\\(?:\\\\)*\")+(?:[^\\](?:\\(?:\\\\)*\")+|[^\"])*\"|\"(?:[^\\](?:\\(?:\\\\)*\")+|[^\"])*\"|[^ ]+))""")
            .matcher(query)
        while (matcher.find()) {
            var term = matcher.group().lowercase()
            var isInclude = true
            if (term.startsWith("-")) {
                term = term.drop(1)
                isInclude = false
            }

            if (term.length > 1 && term.startsWith("\"") && term.endsWith("\"")) {
                term = term.drop(1).dropLast(1)
            }

            term = term.replace("\\\"", "\"")

            if (term.isEmpty()) continue

            if (isInclude) {
                include.add(term)
            } else {
                exclude.add(term)
            }
        }

        val mangas = ArrayList<SManga>()
        val filtered = ArrayList<JSONObject>()

        for (i in 0 until json.length()) {
            val candidate = json.getString(i)
            if ((include.isNotEmpty() && !include.all { term -> candidate.contains(term, true) }) ||
                (exclude.isNotEmpty() && exclude.any { term -> candidate.contains(term, true) })
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
            val thumbnailUrl = it.getString("thumbnail_url")

            mangas.add(
                SManga.create().apply {
                    this.url = url
                    this.title = title
                    this.artist = artist
                    this.author = author
                    this.genre = genre
                    this.status = status
                    this.thumbnail_url = "${baseUrl}$thumbnailUrl"
                },
            )
        }

        return MangasPage(mangas, dropped.size > 32)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used.")

    private fun parseManga(payload: JsonArray, page: Int): MangasPage {
        val mangas = ArrayList<SManga>()

        for (i in min(page * 32, payload.size) until min((page + 1) * 32, payload.size)) {
            val j = payload.get(i).jsonObject
            val url = j["url"]?.jsonPrimitive?.content ?: ""
            val title = j["title"]?.jsonPrimitive?.content ?: ""
            val author = j["author"]?.jsonPrimitive?.content ?: ""
            val artist = j["artist"]?.jsonPrimitive?.content ?: ""
            val thumbnailUrl = j["thumbnail_url"]?.jsonPrimitive?.content ?: ""
            val genre = j["genre"]?.jsonPrimitive?.content ?: ""

            mangas.add(
                SManga.create().apply {
                    this.url = url
                    this.title = title
                    this.author = author
                    this.artist = artist
                    this.thumbnail_url = "${baseUrl}$thumbnailUrl"
                    this.genre = genre
                },
            )
        }

        return MangasPage(mangas, (page + 1) * 32 <= payload.size)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = throw Exception("Unused")

    override fun latestUpdatesParse(response: Response): MangasPage = throw Exception("Unused")
}
