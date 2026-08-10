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
class VideoProvider : MainAPI() {
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
    private val tmdbApiKey = "5622cafbfe8f8cfe358a29c53e19bba0"
    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbImgW500 = "https://image.tmdb.org/t/p/w500"
    private val tmdbImgOriginal = "https://image.tmdb.org/t/p/original"

    // Secondary source: dramacool (English/romanised titles work best — the TMDB title is tried
    // first, then the site title). Matched by title + episode number.
    private val dramacoolBase = "https://dramacool.co.at"

    private val json = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private inline fun <reified T : Any> parse(text: String): T? =
        try { json.readValue<T>(text) } catch (_: Exception) { null }

    // Reads the user's choice saved by VideoPlugin.openSettings (SharedPreferences).
    // Falls back to device locale if not yet set: Chinese → zh-CN, anything else → en-US.
    private fun getTmdbLang(): String {
        val ctx = com.lagradost.api.getContext() as? android.content.Context
        val saved = ctx?.getSharedPreferences(VideoPlugin.PREFS, 0)
            ?.getString(VideoPlugin.LANG_KEY, null)
        return saved ?: if (java.util.Locale.getDefault().language == "zh") "zh-CN" else "en-US"
    }

    // Strip 第XX季 (e.g. 第三季, 第2季) and return (cleanTitle, seasonNumber?).
    // Chinese ordinals 一–二十 are mapped to integers; digits handled directly.
    private val chineseOrdinals = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
        "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
        "十一" to 11, "十二" to 12, "十三" to 13, "十四" to 14, "十五" to 15,
        "十六" to 16, "十七" to 17, "十八" to 18, "十九" to 19, "二十" to 20,
    )
    private val seasonRegex = Regex("""第([一二三四五六七八九十]+|\d+)季""")

    private fun extractSeason(title: String): Pair<String, Int?> {
        val match = seasonRegex.find(title) ?: return title to null
        val raw = match.groupValues[1]
        val num = raw.toIntOrNull() ?: chineseOrdinals[raw]
        val clean = title.replace(match.value, "").trim()
        return clean to num
    }

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/1/page/"    to "电影",
        "$mainUrl/index.php/vod/show/id/202/page/"  to "国产剧",
        "$mainUrl/index.php/vod/show/id/201/page/"  to "欧美剧",
        "$mainUrl/index.php/vod/show/id/203/page/"  to "港台剧",
        "$mainUrl/index.php/vod/show/area/%E6%97%A5%E6%9C%AC/id/204/page/" to "日剧",
        "$mainUrl/index.php/vod/show/area/%E9%9F%A9%E5%9B%BD/id/204/page/" to "韩剧",
        "$mainUrl/index.php/vod/show/id/1207/page/" to "短剧",
        "$mainUrl/index.php/vod/show/id/3/page/"    to "综艺",
        "$mainUrl/index.php/vod/show/id/401/page/"  to "日本动漫",
        "$mainUrl/index.php/vod/show/id/402/page/"  to "国产动漫",
        "$mainUrl/index.php/vod/show/id/403/page/"  to "欧美动漫",
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
        val id: Int = 0,
        val name: String? = null,              // TV shows (localised title)
        val title: String? = null,             // movies (localised title)
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

    private data class TmdbSeasonResponse(
        val episodes: List<TmdbEpisodeDetail> = emptyList()
    )

    private data class TmdbEpisodeDetail(
        val episode_number: Int = 0,
        val name: String? = null,
        val overview: String? = null,
        val still_path: String? = null,
        val air_date: String? = null,
    )

    private data class TmdbFindResult(val details: TmdbDetails, val isTv: Boolean)

    // Searches TMDB by title, trying the primary content type first then the opposite.
    // Returns null silently if the API key is not configured or no match is found.
    private suspend fun fetchTmdb(title: String, isMovie: Boolean, lang: String): TmdbFindResult? {
        if (tmdbApiKey == "REPLACE_WITH_YOUR_TMDB_API_KEY") return null

        val types = if (isMovie) listOf("movie", "tv") else listOf("tv", "movie")
        for (type in types) {
            val firstId = parse<TmdbSearchResponse>(
                app.get(
                    "$tmdbBase/search/$type",
                    params = mapOf(
                        "api_key" to tmdbApiKey,
                        "query" to title,
                        "language" to lang,
                    ),
                ).text,
            )?.results?.firstOrNull()?.id ?: continue

            val details = parse<TmdbDetails>(
                app.get(
                    "$tmdbBase/$type/$firstId",
                    params = mapOf(
                        "api_key" to tmdbApiKey,
                        "language" to lang,
                        "append_to_response" to "credits",
                    ),
                ).text,
            ) ?: continue
            return TmdbFindResult(details, isTv = type == "tv")
        }
        return null
    }

    // Fetches per-episode metadata for one season. Returns a map of episode_number → detail.
    private suspend fun fetchTmdbSeason(
        tvId: Int,
        season: Int,
        lang: String,
    ): Map<Int, TmdbEpisodeDetail> {
        return parse<TmdbSeasonResponse>(
            app.get(
                "$tmdbBase/tv/$tvId/season/$season",
                params = mapOf("api_key" to tmdbApiKey, "language" to lang),
            ).text,
        )?.episodes?.associateBy { it.episode_number } ?: emptyMap()
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

        // Strip 第XX季 before searching TMDB (e.g. "末日地堡 第三季" → "末日地堡", season 3)
        val (cleanTitle, detectedSeason) = extractSeason(title)
        val lang = getTmdbLang()
        val tmdbResult = fetchTmdb(cleanTitle, isMovie, lang)
        val tmdb = tmdbResult?.details

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

        // Title used to look up the dramacool secondary source. Prefer the TMDB title (often the
        // English/romanised name dramacool uses), fall back to the cleaned site title.
        val dramacoolTitle = (tmdb?.name ?: tmdb?.title)?.takeIf { it.isNotBlank() }

        if (isMovie) {
            val playUrl = fixUrl(episodeLinks.first().attr("href"))
            val movieData = encodePlay(playUrl, dramacoolTitle, cleanTitle, 1)
            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
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

        // Fetch per-episode synopsis/poster from TMDB when we have a TV series match
        val tvSeason = detectedSeason ?: 1
        val tmdbEpisodeMap: Map<Int, TmdbEpisodeDetail> =
            if (tmdbResult != null && tmdbResult.isTv && tmdb != null && tmdb.id != 0) {
                fetchTmdbSeason(tmdb.id, tvSeason, lang)
            } else emptyMap()

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val episodes = episodeLinks.mapIndexed { index, ep ->
            val epNum = index + 1
            val tmdbEp = tmdbEpisodeMap[epNum]
            val epData = encodePlay(fixUrl(ep.attr("href")), dramacoolTitle, cleanTitle, epNum)
            // fix = false: the data is a JSON blob, not a URL, so it must not be run through fixUrl.
            // initializer passed by name so the trailing-lambda + named-arg combo stays unambiguous.
            newEpisode(
                epData,
                initializer = {
                    name = tmdbEp?.name?.takeIf { it.isNotBlank() } ?: ep.text().trim()
                    episode = epNum
                    season = detectedSeason
                    description = tmdbEp?.overview?.takeIf { it.isNotBlank() }
                    posterUrl = tmdbEp?.still_path?.let { "$tmdbImgW500$it" }
                    date = tmdbEp?.air_date?.let {
                        runCatching { sdf.parse(it)?.time }.getOrNull()
                    }
                },
                fix = false,
            )
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

    // Carries everything loadLinks needs: the olehdtv play page plus the title/episode used to
    // look up the dramacool secondary source.
    private data class PlayData(
        val u: String,           // olehdtv play-page url
        val t: String? = null,   // dramacool search title (TMDB name, if any)
        val o: String? = null,   // fallback search title (cleaned site title)
        val e: Int? = null,      // episode number
    )

    private fun encodePlay(url: String, title: String?, orig: String?, episode: Int?): String =
        json.writeValueAsString(PlayData(url, title, orig, episode))

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data is a PlayData JSON blob; fall back to treating it as a plain olehdtv url.
        val play = parse<PlayData>(data)
        val olehdtvUrl = play?.u ?: data

        var found = extractOlehdtv(olehdtvUrl, callback)

        // Secondary source (best effort): dramacool, matched by title + episode number.
        val titles = listOfNotNull(play?.t, play?.o).distinct()
        if (titles.isNotEmpty()) {
            try {
                found = addDramacoolLinks(titles, play?.e, subtitleCallback, callback) || found
            } catch (_: Exception) {
                // never let the secondary source break the primary one
            }
        }
        return found
    }

    /** Extracts the native olehdtv source from a play page (player_aaaa JS object). */
    private suspend fun extractOlehdtv(
        playUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = try {
            app.get(playUrl, referer = mainUrl).document
        } catch (_: Exception) {
            return false
        }

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

    // ── dramacool (secondary source) ────────────────────────────────────────────

    // Normalises a title for loose matching: drop a trailing "(year)", lowercase, keep alphanumerics.
    private fun normTitle(s: String): String =
        s.replace(Regex("""\(\d{4}\)"""), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun fixDcUrl(href: String): String? = when {
        href.isBlank() -> null
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$dramacoolBase$href"
        else -> "$dramacoolBase/$href"
    }

    /**
     * Searches dramacool for [titles] (first match wins), locates the given [episode], and hands
     * each embedded server to Cloudstream's extractor registry. Returns true if any server matched.
     */
    private suspend fun addDramacoolLinks(
        titles: List<String>,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ep = episode ?: 1
        for (rawTitle in titles) {
            val query = rawTitle.trim()
            if (query.isBlank()) continue

            val results = try {
                app.get("$dramacoolBase/search", params = mapOf("keyword" to query)).document
                    .select("ul.list-episode-item li a.img")
            } catch (_: Exception) {
                continue
            }
            if (results.isEmpty()) continue

            val qn = normTitle(query)
            val match = results.firstOrNull { a ->
                val t = normTitle(a.attr("title"))
                t.isNotBlank() && (t == qn || t.contains(qn) || qn.contains(t))
            } ?: continue

            val detailUrl = fixDcUrl(match.attr("href")) ?: continue
            val detailDoc = try { app.get(detailUrl).document } catch (_: Exception) { continue }

            // Find the "Episode N" anchor (0-padded ok, but not 10/11/… for N=1); fall back to first.
            val epRegex = Regex("""(?i)episode\s+0*$ep(\D|$)""")
            val epAnchor = detailDoc.select("ul.all-episode li a.img").firstOrNull { a ->
                epRegex.containsMatchIn(a.selectFirst("h3.title")?.text() ?: a.attr("alt"))
            } ?: detailDoc.selectFirst("ul.all-episode li a.img") ?: continue

            val epUrl = fixDcUrl(epAnchor.attr("href")) ?: continue
            val epDoc = try { app.get(epUrl).document } catch (_: Exception) { continue }

            // Collect all embed servers: data-video attributes plus the default iframe.
            val servers = LinkedHashSet<String>()
            epDoc.select("[data-video]").forEach { el ->
                fixDcUrl(el.attr("data-video"))?.let { servers.add(it) }
            }
            epDoc.selectFirst(".watch-iframe iframe, iframe[src*=\"//\"]")?.attr("src")
                ?.let { fixDcUrl(it) }?.let { servers.add(it) }

            var any = false
            for (server in servers) {
                any = try {
                    loadExtractor(server, epUrl, subtitleCallback, callback) || any
                } catch (_: Exception) {
                    any
                }
            }
            if (any) return true
        }
        return false
    }
}
