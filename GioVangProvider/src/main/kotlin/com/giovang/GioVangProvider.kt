package com.giovang

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

/**
 * GioVangProvider — đọc giovang_iptv.json, hiển thị Live TV trong Cloudstream3
 */
class GioVangProvider : MainAPI() {
    override var lang = "vi"

    // !! Thay bằng URL raw.githubusercontent.com trỏ tới giovang_iptv.json
    // Ví dụ: "https://raw.githubusercontent.com/user/mon_giovang/main/giovang_iptv.json"
    override var mainUrl =
        "https://gist.githubusercontent.com/Aquadius13/491cfb7d2a2573f744d101a78e198275/raw/giovang_iptv.json"

    override var name             = "GioVang TV"
    override val hasMainPage      = true
    override val hasChromecastSupport = true
    override val supportedTypes   = setOf(TvType.Live)

    // ── Data classes ánh xạ JSON ──────────────────────────────────────────
    data class GVRoot(
        val id: String? = null,
        val name: String? = null,
        val groups: List<GVGroup> = emptyList(),
    )
    data class GVGroup(
        val id: String? = null,
        val name: String? = null,
        val channels: List<GVChannel> = emptyList(),
    )
    data class GVChannel(
        val id: String = "",
        val name: String = "",
        val description: String? = null,
        val image: GVImage? = null,
        val sources: List<GVSource> = emptyList(),
    )
    data class GVImage(val url: String? = null)
    data class GVSource(
        val name: String? = null,
        val contents: List<GVContent> = emptyList(),
    )
    data class GVContent(
        val name: String? = null,
        val streams: List<GVStream> = emptyList(),
    )
    data class GVStream(
        val name: String? = null,
        val stream_links: List<GVLink> = emptyList(),
    )
    data class GVLink(
        val name: String? = null,
        val type: String? = null,
        val url: String = "",
        val request_headers: List<GVHeader> = emptyList(),
    )
    data class GVHeader(val key: String = "", val value: String = "")
    data class LoadData(
        val channelId: String = "",
        val title: String = "",
        val poster: String? = null,
        val description: String? = null,
    )

    // ── Cache ─────────────────────────────────────────────────────────────
    private var cachedById: Map<String, GVChannel> = emptyMap()

    private suspend fun fetchRoot(): GVRoot {
        val text = app.get(mainUrl, timeout = 20).text
        val root = parseJson<GVRoot>(text)
        cachedById = root.groups.flatMap { it.channels }.associateBy { it.id }
        return root
    }

    private suspend fun ensureCache() {
        if (cachedById.isEmpty()) fetchRoot()
    }

    // ── Trang chính ───────────────────────────────────────────────────────
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val root = fetchRoot()
        val lists = root.groups.map { group ->
            HomePageList(
                name               = group.name ?: "Trận đấu",
                list               = group.channels.map { it.toSearch() },
                isHorizontalImages = false,
            )
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    // ── Tìm kiếm ─────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        ensureCache()
        val q = query.trim().lowercase()
        return cachedById.values
            .filter { it.name.lowercase().contains(q) }
            .map { it.toSearch() }
    }

    private fun GVChannel.toSearch(): LiveSearchResponse =
        newLiveSearchResponse(
            name      = name,
            url       = encodeData(LoadData(id, name, image?.url, description)),
            type      = TvType.Live,
        ) {
            posterUrl = image?.url
        }

    // ── Chi tiết trận đấu ─────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val d = decodeData(url)
        return newLiveStreamLoadResponse(
            name    = d.title,
            url     = url,
            dataUrl = url,
        ) {
            posterUrl = d.poster
            plot      = d.description ?: ""
        }
    }

    // ── Lấy link stream ───────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        ensureCache()
        val d  = decodeData(data)
        val ch = cachedById[d.channelId] ?: return false
        var found = false

        for (src in ch.sources) {
            for (content in src.contents) {
                for (stream in content.streams) {
                    val blv = stream.name ?: "Trực tiếp"
                    for (link in stream.stream_links) {
                        if (link.url.isBlank()) continue
                        val headers = link.request_headers.associate { it.key to it.value }
                        val referer = headers["Referer"] ?: mainUrl

                        // Xác định loại link: hls (m3u8) hoặc mp4
                        val linkType = when (link.type?.lowercase()) {
                            "hls", "m3u8" -> ExtractorLinkType.M3U8
                            "mp4"         -> ExtractorLinkType.VIDEO
                            else          -> ExtractorLinkType.M3U8  // mặc định HLS
                        }

                        callback(
                            ExtractorLink(
                                source  = name,
                                name    = "$blv – ${link.name ?: "HD"}",
                                url     = link.url,
                                referer = referer,
                                quality = Qualities.Unknown.value,
                                type    = linkType,
                                headers = headers,
                            )
                        )
                        found = true
                    }
                }
            }
        }
        return found
    }

    // ── Encode / decode LoadData ──────────────────────────────────────────
    private fun encodeData(d: LoadData): String {
        fun e(s: String?) = (s ?: "")
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        return """{"channelId":"${e(d.channelId)}","title":"${e(d.title)}","poster":"${e(d.poster)}","description":"${e(d.description)}"}"""
    }

    private fun decodeData(s: String): LoadData = try {
        parseJson<LoadData>(s)
    } catch (_: Exception) {
        LoadData(channelId = s, title = s)
    }
}
