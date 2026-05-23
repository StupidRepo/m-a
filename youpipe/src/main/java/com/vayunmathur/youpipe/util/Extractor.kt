package com.vayunmathur.youpipe.util
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64
import kotlin.time.toKotlinInstant

@Serializable
data class SponsorSegment(
    val category: String,
    val segment: List<Float>,
    val UUID: String
) {
    val start: Long get() = (segment[0] * 1000).toLong()
    val end: Long get() = (segment[1] * 1000).toLong()
}

fun videoURLtoID(url: String): Long {
    return ByteBuffer.wrap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(url.toUri().getQueryParameter("v")!!)).long
}

fun channelURLtoID(url: String): String {
    return url.substringAfterLast("/")
}

fun encodeVideoID(id: String): Long {
    return ByteBuffer.wrap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(id)).long
}

fun decodeVideoID(id: Long): String {
    val buffer = ByteBuffer.allocate(java.lang.Long.BYTES)
    buffer.putLong(id)
    return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(buffer.array())
}

fun videoIDtoURL(id: Long): String {
    val buffer = ByteBuffer.allocate(java.lang.Long.BYTES)
    buffer.putLong(id)
    return "https://www.youtube.com/watch?v="+Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(buffer.array())
}

fun channelIDtoURL(id: String): String {
    return "https://www.youtube.com/channel/$id"
}

suspend fun getVideoInfo(videoId: Long): VideoInfo = coroutineScope {
    val idString = decodeVideoID(videoId)
    val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$idString")
    ex.fetchPage()
    VideoInfo(
        HtmlCompat.fromHtml(ex.name, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
        videoId,
        ex.length,
        ex.viewCount,
        ex.uploadDate!!.instant.toKotlinInstant(),
        ex.thumbnails.first().url,
        HtmlCompat.fromHtml(ex.uploaderName, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    )
}

fun getChannelVideos(channelId: String): Sequence<VideoInfo> = sequence {
    val ex = ServiceList.YouTube.getChannelTabExtractorFromId(channelId, "videos")
    ex.fetchPage()
    var page = ex.initialPage
    while(true) {
        page.items.filterIsInstance<StreamInfoItem>().forEach {
            yield(
            VideoInfo(
                HtmlCompat.fromHtml(it.name, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
                videoURLtoID(it.url),
                it.duration,
                it.viewCount,
                it.uploadDate!!.instant.toKotlinInstant(),
                it.thumbnails.first().url,
                HtmlCompat.fromHtml(it.uploaderName, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            ))
        }
        if(page.hasNextPage()) {
            page = ex.getPage(page.nextPage!!)
        } else {
            break
        }
    }
}

suspend fun getChannelInfo(channelId: String): ChannelInfo = getChannelInfoFromURL(channelIDtoURL(channelId))

suspend fun getChannelInfoFromURL(url: String): ChannelInfo = coroutineScope {
    val ex = ServiceList.YouTube.getChannelExtractor(url)
    ex.fetchPage()
    ChannelInfo(
        HtmlCompat.fromHtml(ex.name, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
        ex.id,
        ex.subscriberCount,
        0,
        ex.avatars.firstOrNull()?.url ?: "",
    )
}

suspend fun getSponsorSegments(videoId: Long): List<SponsorSegment> {
    val idString = decodeVideoID(videoId)
    return try {
        NetworkClient.getJson("https://sponsor.ajay.app/api/skipSegments?videoID=$idString")
    } catch (e: Exception) {
        emptyList()
    }
}
