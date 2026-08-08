package recloudstream

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// olehdtv.com runs maccms v10. The public JSON API is disabled ("closed"),
// so all data is scraped from HTML. Video URLs live in the player_aaaa JS object
// on each play page; they may be plain URLs or Base64-encoded.
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

    // maccms paginated listing pattern: /index.php/vod/show/id/{TYPE}/page/{N}.html
    // Type IDs: 1=电影, 2=连续剧, 3=综艺, 4=动漫
    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/1/page/" to "电影",
        "$mainUrl/index.php/vod/show/id/2/page/" to "连续剧",
        "$mainUrl/index.php/vod/show/id/3/page/" to "综艺",
        "$mainUrl/index.php/vod/show/id/4/page/" to "动漫",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}${page}.html").document
        val items = doc.select("ul.module-list li.module-item").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = false)),
            hasNext = items.isNotEmpty(),
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = selectFirst("a.module-item-link")?.attr("href")
            ?.let { fixUrl(it) } ?: return null
        val title = selectFirst(".module-item-title")?.text()?.trim()
            .takeIf { !it.isNullOrBlank() } ?: return null
        // Images are lazy-loaded; real URL is in data-src
        val poster = selectFirst("img.lazyload")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query),
        ).document
        return doc.select("ul.module-list li.module-item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst(".module-info-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = doc.selectFirst(".module-info-pic img.lazyload")?.attr("data-src")
            ?: doc.selectFirst(".module-info-pic img")?.attr("src")

        val desc = doc.selectFirst(".module-info-dese-content")?.text()
            ?: doc.selectFirst(".module-info-dese")?.text()

        // Parse dt/dd meta pairs: 年份, 地区, 类型, 导演, 主演 …
        var year: Int? = null
        var genreRaw = ""
        val dts = doc.select(".module-info-items dt")
        val dds = doc.select(".module-info-items dd")
        dts.forEachIndexed { i, dt ->
            val value = dds.getOrNull(i)?.text()?.trim() ?: return@forEachIndexed
            when {
                dt.text().contains("年份") -> year = value.toIntOrNull()
                dt.text().contains("类型") -> genreRaw = value
            }
        }
        val tags = genreRaw.split(Regex("\\s+")).filter { it.isNotBlank() }

        val episodeLinks = doc.select(".module-play-list a.module-play-list-link")
        if (episodeLinks.isEmpty()) return null

        // Detect movie vs series: a single episode whose label isn't numbered ("第N集/话")
        // is almost always a movie presented as a single play button.
        val singleEp = episodeLinks.size == 1
        val firstEp = episodeLinks.firstOrNull()
        val epText = firstEp?.text()?.trim() ?: ""
        val isMovie = singleEp && !epText.contains(Regex("第\\d+[集话]"))

        if (isMovie) {
            val playUrl = fixUrl(firstEp?.attr("href") ?: return null)
            return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                posterUrl = poster
                plot = desc
                this.year = year
                this.tags = tags
            }
        }

        val tvType = when {
            genreRaw.contains("动漫") -> TvType.Anime
            genreRaw.contains("综艺") -> TvType.Others
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
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document

        // The play page embeds a player_aaaa JS object with the stream URL.
        // Example: var player_aaaa = {"url":"https://…/video.m3u8","url_next":…}
        val scriptData = doc.select("script").map { it.data() }
            .firstOrNull { it.contains("player_aaaa") } ?: return false

        var videoUrl = Regex(""""url"\s*:\s*"([^"]+)"""")
            .find(scriptData)?.groupValues?.get(1) ?: return false

        // JSON strings often escape forward slashes as \/
        videoUrl = videoUrl.replace("\\/", "/")

        // maccms sometimes Base64-encodes the URL to hide it from scrapers
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
