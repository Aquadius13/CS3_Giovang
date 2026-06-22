package com.giovang

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

/**
 * GioVang TV Provider
 * ===================
 * Đọc file giovang_iptv.json (do crawler_giovang.py sinh ra mỗi 15 phút) và
 * hiển thị danh sách trận đấu trực tiếp trong app Cloudstream3.
 *
 * JSON schema (giovang_iptv.json):
 * {
 *   "groups": [
 *     {
 *       "name": "Hot Match",
 *       "channels": [
 *         {
 *           "id": "abc123",
 *           "name": "⚽ Mỹ vs Paraguay",
 *           "description": "FIFA World Cup | 08:00 | 13/06",
 *           "image": { "url": "https://...thumb.webp" },
 *           "sources": [
 *             {
 *               "name": "GioVang Live",
 *               "contents": [
 *                 {
 *                   "streams": [
 *                     {
 *                       "name": "BLV Quang Huy",
 *                       "stream_links": [
 *                         {
 *                           "name": "HD",
 *                           "type": "hls",
 *                           "url": "https://...m3u8",
 *                           "request_headers": [
 *                             { "key": "Referer", "value": "https://giovang.vin/" }
 *                           ]
 *                         }
 *                       ]
 *                     }
 *                   ]
 *                 }
 *               ]
 *             }
 *           ]
 *         }
 *       ]
 *     }
 *   ]
 * }
 */
class GioVangProvider : MainAPI() {
    override var lang             = "vi"
    override var name             = "GioVang TV"
    override val hasMainPage      = true
    override val hasChromecastSupport = true
    override val supportedTypes   = setOf(TvType.Live)

    // ─────────────────────────────────────────────────────────────────────────
    // URL trỏ tới giovang_iptv.json — được crawler cập nhật mỗi 15 phút.
    // Thay USER và REPO bằng thông tin repo GitHub chứa crawler của bạn.
    // Ví dụ: "https://raw.githubusercontent.com/anhba/mon_giovang/main/giovang_iptv.json"
    // ─────────────────────────────────────────────────────────────────────────
    override var mainUrl =
        "https://gist.githubusercontent.com/Aquadius13/491cfb7d2a2573f744d101a78e198275/raw/giovang_iptv.json"

    // ── Data classes ánh xạ JSON ─────────────────────────────────────────────
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

    // Dữ liệu truyền từ search/load → loadLinks (serialized JSON)
    data class GVLoadData(
        val channelId: String = "",
        val title: String = "",
        val poster: String? = null,
        val description: String? = null,
    )

    // ── Cache JSON trong phiên ───────────────────────────────────────────────
    // JSON được fetch mỗi lần getMainPage() gọi → luôn lấy dữ liệu mới nhất
    private var channelMap: Map<String, GVChannel> = emptyMap()

    private suspend fun fetchAndCache(): GVRoot {
        val text = app.get(
            mainUrl,
            timeout = 20,
            headers = mapOf("Cache-Control" to "no-cache"),
        ).text
        val root = parseJson<GVRoot>(text)
        channelMap = root.groups.flatMap { it.channels }.associateBy { it.id }
        return root
    }

    // ── Định nghĩa các trang chủ (mỗi group → 1 hàng) ──────────────────────
    // Theo docs CS3: mainPage phải được định nghĩa nếu hasMainPage = true
    override val mainPage = mainPageOf(
        mainUrl to "GioVang TV",
    )

    // ── Trang chính: mỗi group → 1 HomePageList ─────────────────────────────
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val root = fetchAndCache()

        val homeLists = root.groups.map { group ->
            val items = group.channels.map { ch -> ch.toSearchResult() }
            HomePageList(
                name               = group.name ?: "Trận đấu",
                list               = items,
                isHorizontalImages = false,
            )
        }

        return newHomePageResponse(homeLists, hasNext = false)
    }

    // ── Tìm kiếm theo tên trận/đội ──────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        if (channelMap.isEmpty()) fetchAndCache()
        val q = query.trim().lowercase()
        return channelMap.values
            .filter { it.name.lowercase().contains(q) }
            .map { it.toSearchResult() }
    }

    // Chuyển GVChannel → LiveSearchResponse (dùng API mới theo docs)
    private fun GVChannel.toSearchResult(): LiveSearchResponse {
        return newLiveSearchResponse(
            name = this.name,
            url  = serializeLoadData(GVLoadData(
                channelId   = this.id,
                title       = this.name,
                poster      = this.image?.url,
                description = this.description,
            )),
            type = TvType.Live,
        ) {
            posterUrl = this@toSearchResult.image?.url
        }
    }

    // ── Trang chi tiết trận đấu ─────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val data = deserializeLoadData(url)
        return newLiveStreamLoadResponse(
            name    = data.title,
            url     = url,
            dataUrl = url,
        ) {
            posterUrl = data.poster
            plot      = data.description ?: ""
        }
    }

    // ── Lấy link stream thực tế ─────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (channelMap.isEmpty()) fetchAndCache()

        val loadData = deserializeLoadData(data)
        val channel  = channelMap[loadData.channelId] ?: return false
        var found    = false

        for (source in channel.sources) {
            for (content in source.contents) {
                for (stream in content.streams) {
                    val blvName = stream.name?.takeIf { it.isNotBlank() } ?: "Trực tiếp"
                    for (link in stream.stream_links) {
                        if (link.url.isBlank()) continue

                        val headers = link.request_headers.associate { it.key to it.value }
                        val referer = headers["Referer"] ?: mainUrl

                        // Xác định loại stream
                        val linkType = when (link.type?.lowercase()) {
                            "hls", "m3u8"  -> ExtractorLinkType.M3U8
                            "mp4", "video" -> ExtractorLinkType.VIDEO
                            else           -> ExtractorLinkType.M3U8
                        }

                        callback(
                            ExtractorLink(
                                source  = name,
                                name    = "$blvName – ${link.name ?: "HD"}",
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

    // ── Serialize / Deserialize LoadData ────────────────────────────────────
    private fun serializeLoadData(d: GVLoadData): String {
        fun esc(s: String?) = (s ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
        return """{"channelId":"${esc(d.channelId)}","title":"${esc(d.title)}","poster":"${esc(d.poster)}","description":"${esc(d.description)}"}"""
    }

    private fun deserializeLoadData(s: String): GVLoadData = try {
        parseJson<GVLoadData>(s)
    } catch (_: Exception) {
        GVLoadData(channelId = s, title = s)
    }
}
