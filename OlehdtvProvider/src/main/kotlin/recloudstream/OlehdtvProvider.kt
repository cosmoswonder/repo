package recloudstream

import android.util.Base64
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

    // Works for both listing cards (li.vodlist_item) and search results (li.searchlist_item)
    // Both contain a.vodlist_thumb with title attr, href, and data-original for the poster.
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

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        // h2.title.scookie holds the title but has an inline <script> child — remove it first
        val titleEl = doc.selectFirst("h2.title.scookie") ?: doc.selectFirst("h2.title")
        titleEl?.select("script")?.remove()
        val title = titleEl?.text()?.trim() ?: return null

        val poster = doc.selectFirst(".content_thumb a.vodlist_thumb")
            ?.attr("data-original")?.takeIf { it.isNotBlank() }

        val desc = doc.selectFirst(".content_desc.context span")?.text()
            ?: doc.selectFirst(".content_desc span")?.text()

        // Meta line: "2026 / 美国 / 未知"  — first vodlist_sub that contains "/"
        val metaParts = doc.select("p.vodlist_sub")
            .firstOrNull { it.text().contains("/") }
            ?.text()?.split("/")?.map { it.trim() }
        val year = metaParts?.getOrNull(0)?.toIntOrNull()

        // Genre tag shown in search results and sometimes in detail (span.info_right)
        val genre = doc.selectFirst("span.info_right")?.text()?.trim() ?: ""

        // playlist_notfull is the visible (non-collapsed) episode list.
        // Filter to /vod/play/ only — excludes /vod/play_vip/ premium links.
        val episodeLinks = doc.select(".playlist_notfull ul.content_playlist li a")
            .filter { it.attr("href").contains("/vod/play/") }

        if (episodeLinks.isEmpty()) return null

        // A single episode labelled "立即播放" (or anything without 第N集/话) is a movie
        val firstEpText = episodeLinks.first().text().trim()
        val isMovie = episodeLinks.size == 1 && !firstEpText.contains(Regex("第\\d+[集话]"))

        if (isMovie) {
            val playUrl = fixUrl(episodeLinks.first().attr("href"))
            return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                posterUrl = poster
                plot = desc
                this.year = year
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
            posterUrl = poster
            plot = desc
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document

        // player_aaaa is a JS object on the play page containing the stream URL.
        // Example: player_aaaa={"url":"https:\/\/cdn.example.com\/video.m3u8",...}
        val scriptData = doc.select("script").map { it.data() }
            .firstOrNull { it.contains("player_aaaa") } ?: return false

        var videoUrl = Regex(""""url"\s*:\s*"([^"]+)"""")
            .find(scriptData)?.groupValues?.get(1) ?: return false

        // JSON escapes forward slashes as \/
        videoUrl = videoUrl.replace("\\/", "/")

        // Some maccms instances Base64-encode the URL
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
