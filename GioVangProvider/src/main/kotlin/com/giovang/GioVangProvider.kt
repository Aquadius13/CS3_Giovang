package com.giovang

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities

/**
 * GioVangProvider — đọc giovang_iptv.json và hiển thị trận đấu Live
 * Schema JSON: { groups: [ { name, channels: [ { id, name, image, sources } ] } ] }
 */
class GioVangProvider : MainAPI() {
    override var lang = "vi"

    // !! Thay bằng URL thực tế nơi crawler_giovang.py publish giovang_iptv.json
    // Ví dụ: https://raw.githubusercontent.com/<user>/<repo>/main/giovang_iptv.json
    override var mainUrl =
        "https://gist.githubusercontent.com/Aquadius13/491cfb7d2a2573f744d101a78e198275/raw/giovang_iptv.json"

    override var name = "GioVang TV"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    // ── Data classes ánh xạ JSON ──────────────────────────────
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

    // LoadData: truyền giữa search → load → loadLinks
    data class LoadData(
        val channelId: String,
        val title: String,
        val poster: String?,
        val description: String?,
    )

    // ── Cache trong phiên ─────────────────────────────────────
    private var cachedById: Map<String, GVChannel> = emptyMap()

    private suspend fun fetchRoot(): GVRoot {
        if (cachedById.isNotEmpty()) return GVRoot() // đã cache
        val text = app.get(mainUrl, timeout = 20).text
        val root = parseJson<GVRoot>(text)
        cachedById = root.groups.flatMap { it.channels }.associateBy { it.id }
        return root
    }

    private suspend fun getChannelById(id: String): GVChannel? {
        if (cachedById.isEmpty()) fetchRoot()
        return cachedById[id]
    }

    // ── Trang chính: mỗi group → 1 hàng HomePageList ─────────
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val root = fetchRoot()
        val lists = root.groups.map { group ->
            HomePageList(
                name  = group.name ?: "Trận đấu",
                list  = group.channels.map { it.toSearch() },
                isHorizontalImages = false,
            )
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    // ── Tìm kiếm ─────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val root = fetchRoot()
        val q = query.trim().lowercase()
        return root.groups
            .flatMap { it.channels }
            .filter { it.name.lowercase().contains(q) }
            .map { it.toSearch() }
    }

    private fun GVChannel.toSearch(): LiveSearchResponse {
        val data = encodeLoadData(
            LoadData(id, name, image?.url, description)
        )
        return LiveSearchResponse(
            name      = name,
            url       = data,
            apiName   = this@GioVangProvider.name,
            type      = TvType.Live,
            posterUrl = image?.url,
        )
    }

    // ── Chi tiết trận đấu ─────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val d = decodeLoadData(url)
        return newLiveStreamLoadResponse(
            name    = d.title,
            url     = url,
            dataUrl = url,
        ) {
            posterUrl = d.poster
            plot      = d.description
        }
    }

    // ── Lấy link stream thực tế ───────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val d = decodeLoadData(data)
        val ch = getChannelById(d.channelId) ?: return false
        var found = false

        for (src in ch.sources) {
            for (content in src.contents) {
                for (stream in content.streams) {
                    val blv = stream.name ?: "Trực tiếp"
                    for (link in stream.stream_links) {
                        if (link.url.isBlank()) continue
                        val headers = link.request_headers
                            .associate { it.key to it.value }
                        val referer = headers["Referer"] ?: mainUrl

                        callback(
                            ExtractorLink(
                                source   = name,
                                name     = "$blv – ${link.name ?: "HD"}",
                                url      = link.url,
                                referer  = referer,
                                quality  = Qualities.Unknown.value,
                                type     = INFER_TYPE,
                                headers  = headers,
                            )
                        )
                        found = true
                    }
                }
            }
        }
        return found
    }

    // ── Encode/decode LoadData ────────────────────────────────
    private fun encodeLoadData(d: LoadData): String {
        fun esc(s: String?) = (s ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
        return """{"channelId":"${esc(d.channelId)}","title":"${esc(d.title)}","poster":"${esc(d.poster)}","description":"${esc(d.description)}"}"""
    }

    private fun decodeLoadData(s: String): LoadData = try {
        parseJson<LoadData>(s)
    } catch (e: Exception) {
        LoadData(channelId = s, title = s, poster = null, description = null)
    }
}
