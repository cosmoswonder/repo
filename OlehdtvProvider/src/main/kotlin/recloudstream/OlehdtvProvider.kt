package recloudstream

import android.util.Base64
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// olehdtv.com uses a custom maccms theme with obfuscated numeric CSS class names
// for styled elements. The structural selectors below were confirmed against live HTML.
class OlehdtvProvider : MainAPI() {
    override var mainUrl = "https://www.olehdtv.com"
    override var name = "OlehdTV"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Others,
    )
    override var lang = "zh"
    override val hasMainPage = true

    // Get your free key at https://www.themoviedb.org/settings/api → "API Key (v3 auth)"
    private val tmdbApiKey = "REPLACE_WITH_YOUR_TMDB_API_KEY"
    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbImgW500 = "https://image.tmdb.org/t/p/w500"
    private val tmdbImgOriginal = "https://image.tmdb.org/t/p/original"

    private val json = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private inline fun <reified T : Any> parse(text: String): T? =
        try { json.readValue<T>(text) } catch (_: Exception) { null }

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/1/page/" to "电影",
        "$mainUrl/index.php/vod/show/id/2/page/" to "连续剧",
        "$mainUrl/index.php/vod/show/id/3/page/" to "综艺",
        "$mainUrl/index.php/vod/show/id/4/page/" to "动漫",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}${page}.html").document
        val items = doc.select("li.vodlist_item").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = false)),
            hasNext = items.isNotEmpty(),
        )
    }

    // Shared between listing cards (li.vodlist_item) and search results (li.searchlist_item) —
    // both contain a.vodlist_thumb with title attr, href, and data-original for the poster.
    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = selectFirst("a.vodlist_thumb") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title = anchor.attr("title").trim().takeIf { it.isNotBlank() } ?: return null
        val poster = anchor.attr("data-original").takeIf { it.isNotBlank() }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query),
        ).document
        return doc.select("li.searchlist_item").mapNotNull { it.toSearchResponse() }
    }

    // ── TMDB ──────────────────────────────────────────────────────────────────

    private data class TmdbSearchResponse(val results: List<TmdbSearchItem> = emptyList())
    private data class TmdbSearchItem(val id: Int = 0)

    private data class TmdbDetails(
        val overview: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val release_date: String? = null,     // movies
        val first_air_date: String? = null,   // TV shows
        val vote_average: Double? = null,
        val genres: List<TmdbGenre>? = null,
        val credits: TmdbCredits? = null,
    )

    private data class TmdbGenre(val name: String = "")

    private data class TmdbCredits(
        val cast: List<TmdbCast>? = null,
        val crew: List<TmdbCrew>? = null,
    )

    private data class TmdbCast(
        val name: String = "",
        val character: String? = null,
        val profile_path: String? = null,
    )

    private data class TmdbCrew(val name: String = "", val job: String? = null)

    // Searches TMDB by title, trying the primary content type first then the opposite
    // (some films are listed as TV specials on TMDB and vice versa).
    // Returns null silently if the API key is not configured or no match is found.
    private suspend fun fetchTmdb(title: String, isMovie: Boolean): TmdbDetails? {
        if (tmdbApiKey == "REPLACE_WITH_YOUR_TMDB_API_KEY") return null

        val types = if (isMovie) listOf("movie", "tv") else listOf("tv", "movie")
        for (type in types) {
            val firstId = parse<TmdbSearchResponse>(
                app.get(
                    "$tmdbBase/search/$type",
                    params = mapOf(
                        "api_key" to tmdbApiKey,
                        "query" to title,
                        "language" to "en-US",
                    ),
                ).text,
            )?.results?.firstOrNull()?.id ?: continue

            return parse<TmdbDetails>(
                app.get(
                    "$tmdbBase/$type/$firstId",
                    params = mapOf(
                        "api_key" to tmdbApiKey,
                        "language" to "en-US",
                        "append_to_response" to "credits",
                    ),
                ).text,
            ) ?: continue
        }
        return null
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        // Title lives in h2.title.scookie which has an inline <script> child — strip it first
        val titleEl = doc.selectFirst("h2.title.scookie") ?: doc.selectFirst("h2.title")
        titleEl?.select("script")?.remove()
        val title = titleEl?.text()?.trim() ?: return null

        // Site-provided fallbacks (used when TMDB has no match)
        val sitePoster = doc.selectFirst(".content_thumb a.vodlist_thumb")
            ?.attr("data-original")?.takeIf { it.isNotBlank() }
        val siteDesc = doc.selectFirst(".content_desc.context span")?.text()
            ?: doc.selectFirst(".content_desc span")?.text()
        val siteYear = doc.select("p.vodlist_sub")
            .firstOrNull { it.text().contains("/") }
            ?.text()?.split("/")?.firstOrNull()?.trim()?.toIntOrNull()

        // Genre hint for TvType (span.info_right appears on detail pages and search cards)
        val genre = doc.selectFirst("span.info_right")?.text()?.trim() ?: ""

        // playlist_notfull = visible (non-collapsed) list; filter out /vod/play_vip/ premium links
        val episodeLinks = doc.select(".playlist_notfull ul.content_playlist li a")
            .filter { it.attr("href").contains("/vod/play/") }
        if (episodeLinks.isEmpty()) return null

        // A single episode labelled "立即播放" (not 第N集/话) is a movie
        val firstEpText = episodeLinks.first().text().trim()
        val isMovie = episodeLinks.size == 1 && !firstEpText.contains(Regex("第\\d+[集话]"))

        // Enrich with TMDB metadata; falls back to site data gracefully if unavailable
        val tmdb = fetchTmdb(title, isMovie)

        val mergedPlot = tmdb?.overview?.takeIf { it.isNotBlank() } ?: siteDesc
        val mergedPoster = tmdb?.poster_path?.let { "$tmdbImgW500$it" } ?: sitePoster
        val mergedBackdrop = tmdb?.backdrop_path?.let { "$tmdbImgOriginal$it" }
        val mergedYear = tmdb
            ?.let { (it.release_date ?: it.first_air_date)?.take(4)?.toIntOrNull() }
            ?: siteYear
        val mergedScore = tmdb?.vote_average
        val mergedTags = tmdb?.genres?.map { it.name }
        val mergedActors = tmdb?.credits?.cast?.take(15)?.map { cast ->
            ActorData(
                actor = Actor(cast.name, cast.profile_path?.let { "$tmdbImgW500$it" }),
                roleString = cast.character,
            )
        }

        if (isMovie) {
            val playUrl = fixUrl(episodeLinks.first().attr("href"))
            return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                posterUrl = mergedPoster
                backgroundPosterUrl = mergedBackdrop
                plot = mergedPlot
                this.year = mergedYear
                this.score = Score.from(mergedScore, 10)
                this.tags = mergedTags
                this.actors = mergedActors
            }
        }

        val tvType = when {
            genre.contains("动漫") -> TvType.Anime
            genre.contains("综艺") -> TvType.Others
            else -> TvType.TvSeries
        }

        val episodes = episodeLinks.mapIndexed { index, ep ->
            newEpisode(fixUrl(ep.attr("href"))) {
                name = ep.text().trim()
                episode = index + 1
            }
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            posterUrl = mergedPoster
            backgroundPosterUrl = mergedBackdrop
            plot = mergedPlot
            this.year = mergedYear
            this.score = Score.from(mergedScore, 10)
            this.tags = mergedTags
            this.actors = mergedActors
        }
    }

    // ── Play ──────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document

        // player_aaaa is a JS object on the play page: player_aaaa={"url":"…","url_next":"…",…}
        val scriptData = doc.select("script").map { it.data() }
            .firstOrNull { it.contains("player_aaaa") } ?: return false

        var videoUrl = Regex(""""url"\s*:\s*"([^"]+)"""")
            .find(scriptData)?.groupValues?.get(1) ?: return false

        // JSON escapes forward slashes as \/
        videoUrl = videoUrl.replace("\\/", "/")

        // Some maccms instances Base64-encode the URL to obscure it
        if (!videoUrl.startsWith("http")) {
            try {
                videoUrl = Base64.decode(videoUrl, Base64.DEFAULT).toString(Charsets.UTF_8)
            } catch (e: Exception) {
                return false
            }
        }

        if (videoUrl.isBlank() || !videoUrl.startsWith("http")) return false

        callback(newExtractorLink(name, name, videoUrl) {
            quality = Qualities.Unknown.value
            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            referer = mainUrl
        })
        return true
    }
}
