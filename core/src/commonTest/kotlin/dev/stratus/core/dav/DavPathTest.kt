package dev.stratus.core.dav

import kotlin.test.Test
import kotlin.test.assertEquals

class DavPathTest {
    @Test
    fun leavesUnreservedCharactersAlone() {
        assertEquals("photo-01_final.v2~", DavPath.encodeSegment("photo-01_final.v2~"))
    }

    @Test
    fun encodesSpacesAsPercentTwenty() {
        // Not "+". That is form encoding, and a WebDAV server asked for "a+b"
        // would go looking for a file with a plus in its name.
        assertEquals("holiday%20photos", DavPath.encodeSegment("holiday photos"))
    }

    @Test
    fun encodesNonAsciiAsUtf8Bytes() {
        assertEquals("ma%C3%B1ana", DavPath.encodeSegment("mañana"))
        assertEquals("%F0%9F%93%B7", DavPath.encodeSegment("📷"))
    }

    @Test
    fun encodesCharactersThatWouldChangeTheUrl() {
        assertEquals("a%3Fb", DavPath.encodeSegment("a?b"))
        assertEquals("a%23b", DavPath.encodeSegment("a#b"))
        assertEquals("a%2Bb", DavPath.encodeSegment("a+b"))
        assertEquals("a%2Fb", DavPath.encodeSegment("a/b"))
    }

    @Test
    fun keepsSeparatorsWhenEncodingAWholePath() {
        assertEquals("/DCIM/Camera/IMG%200001.jpg", DavPath.encodePath("/DCIM/Camera/IMG 0001.jpg"))
    }

    @Test
    fun preservesTheShapeOfEmptySegments() {
        // A trailing slash marks a collection and has to survive the round trip.
        assertEquals("/photos/", DavPath.encodePath("/photos/"))
    }
}
