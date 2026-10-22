package dev.stratus.core.cast.dlna

import dev.stratus.core.cast.CastItem
import dev.stratus.core.cast.HLS_TYPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolInfoTest {

    private val hls = CastItem("http://s/files/f.mkv?k=t&hls=index.m3u8", HLS_TYPE, "f.mkv")
    private val file = CastItem("http://s/files/f.mkv?k=t", "video/x-matroska", "f.mkv")
    private val mp4 = CastItem("http://s/files/f.mp4?k=t", "video/mp4", "f.mp4")

    @Test
    fun aSinkIsReadLineByLineAndABrokenLineIsDropped() {
        val sink = ProtocolInfo.parseList(
            "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC_520;DLNA.ORG_OP=01, http-get:*:audio/mpeg:*,garbage,rtsp-rtp-udp:*:video/mp4:*",
        )
        assertEquals(3, sink.size)
        assertEquals(ProtocolInfo("http-get", "*", "video/mp4", "DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC_520;DLNA.ORG_OP=01"), sink[0])
        assertEquals("http-get:*:audio/mpeg:*", sink[1].toString())
    }

    @Test
    fun thePlaylistGoesWhereTheRendererTakesOne() {
        // Under any of its names: this is the one LG lists.
        val sink = ProtocolInfo.parseList("http-get:*:application/x-mpegURL:*,http-get:*:video/x-matroska:*")
        assertEquals(Pick(hls, "http-get:*:$HLS_TYPE:*"), pickFor(sink, listOf(hls, file)))
    }

    @Test
    fun gstreamersNameForAPlaylistIsAPlaylist() {
        // gmrender-resurrect lists GStreamer's caps, and this is what it calls one.
        assertEquals(hls, pickFor(ProtocolInfo.parseList("http-get:*:application/x-hls:*"), listOf(hls, file))?.item)
        val wav = CastItem("http://s/a.wav?k=t", "audio/wav", "a.wav")
        assertEquals(wav, pickFor(ProtocolInfo.parseList("http-get:*:audio/x-wav:*"), listOf(wav))?.item)
    }

    @Test
    fun theFileGoesWhereItDoesNot() {
        val sink = ProtocolInfo.parseList("http-get:*:video/x-matroska:DLNA.ORG_OP=01,http-get:*:audio/mpeg:*")
        assertEquals(Pick(file, "http-get:*:video/x-matroska:*"), pickFor(sink, listOf(hls, file)))
    }

    @Test
    fun nothingGoesWhereNothingFits() {
        val sink = ProtocolInfo.parseList("http-get:*:audio/mpeg:*,http-get:*:audio/flac:*")
        assertNull(pickFor(sink, listOf(hls, file)))
        // Fetched by some other protocol is not fetched over http.
        assertNull(pickFor(ProtocolInfo.parseList("rtsp-rtp-udp:*:video/mp4:*"), listOf(mp4)))
    }

    @Test
    fun wildcardsAndParametersAreHonoured() {
        assertEquals(mp4, pickFor(ProtocolInfo.parseList("http-get:*:*:*"), listOf(mp4))?.item)
        assertEquals(mp4, pickFor(ProtocolInfo.parseList("http-get:*:video/*:*"), listOf(mp4))?.item)
        val track = CastItem("http://s/a.mp3?k=t", "audio/mpeg; charset=binary", "a.mp3")
        assertEquals(track, pickFor(ProtocolInfo.parseList("http-get:*:AUDIO/MPEG:*"), listOf(track))?.item)
    }

    @Test
    fun aRendererThatWillNotSayGetsTheFirstChoice() {
        assertEquals(hls, pickFor(emptyList(), listOf(hls, file))?.item)
        assertNull(pickFor(emptyList(), emptyList()))
    }
}
