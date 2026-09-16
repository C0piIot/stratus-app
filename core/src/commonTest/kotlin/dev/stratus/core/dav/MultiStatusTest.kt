package dev.stratus.core.dav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixture is not invented: it is the exact body a running stratus-backend
 * answered a `Depth: 1` allprop with, including its habit of redeclaring the
 * DAV namespace on every element and the doubly-encoded href.
 */
class MultiStatusTest {

    private val realListing = """<?xml version="1.0" encoding="UTF-8"?>
<multistatus xmlns="DAV:"><response xmlns="DAV:"><href>/dav/Photos/2026</href><propstat xmlns="DAV:"><prop xmlns="DAV:"><resourcetype xmlns="DAV:"><collection xmlns="DAV:"></collection></resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat></response><response xmlns="DAV:"><href>/dav/Photos/IMG%200001.jpg</href><propstat xmlns="DAV:"><prop xmlns="DAV:"><resourcetype xmlns="DAV:"></resourcetype><getcontentlength xmlns="DAV:">17</getcontentlength><getlastmodified xmlns="DAV:">Wed, 16 Sep 2026 05:58:21 GMT</getlastmodified><getcontenttype xmlns="DAV:">image/jpeg</getcontenttype><getetag xmlns="DAV:">&#34;21ac2586e213d1f490778a07bf0025a98fc57595863a282372bac594b398322b&#34;</getetag></prop><status>HTTP/1.1 200 OK</status></propstat></response><response xmlns="DAV:"><href>/dav/Photos/a&amp;b%20%22c%22.txt</href><propstat xmlns="DAV:"><prop xmlns="DAV:"><getlastmodified xmlns="DAV:">Wed, 16 Sep 2026 05:58:21 GMT</getlastmodified><getcontenttype xmlns="DAV:">text/plain; charset=utf-8</getcontenttype><getetag xmlns="DAV:">&#34;046a4bf5dc2bb0d72622bb542356a3cbb88ed42d17103d509f9fc30de3b880b6&#34;</getetag><resourcetype xmlns="DAV:"></resourcetype><getcontentlength xmlns="DAV:">13</getcontentlength></prop><status>HTTP/1.1 200 OK</status></propstat></response><response xmlns="DAV:"><href>/dav/Photos/ma%C3%B1ana.txt</href><propstat xmlns="DAV:"><prop xmlns="DAV:"><resourcetype xmlns="DAV:"></resourcetype><getcontentlength xmlns="DAV:">4</getcontentlength><getcontenttype xmlns="DAV:">text/plain; charset=utf-8</getcontenttype><getetag xmlns="DAV:">&#34;b221d9dbb083a7f33428d7c2a3c3198ae925614d70210e28716ccaa7cd4ddb79&#34;</getetag></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"""

    @Test
    fun readsARealListing() {
        val entries = MultiStatus.parse(realListing, "/dav")
        assertEquals(
            listOf("/Photos/2026", "/Photos/IMG 0001.jpg", "/Photos/a&b \"c\".txt", "/Photos/mañana.txt"),
            entries.map { it.path },
        )
    }

    @Test
    fun undoesXmlEscapingOverPercentEncodingInThatOrder() {
        // The wire carries a&amp;b%20%22c%22.txt. Undoing only one layer, or the
        // two in the wrong order, both produce a name nothing on the server has.
        val entry = MultiStatus.parse(realListing, "/dav").first { it.path.contains("&") }
        assertEquals("a&b \"c\".txt", entry.name)
    }

    @Test
    fun readsTheCollectionAsADirectoryWithNoSize() {
        val directory = MultiStatus.parse(realListing, "/dav").first()
        assertTrue(directory.isDirectory)
        assertNull(directory.size)
        assertNull(directory.etag)
    }

    @Test
    fun stripsTheQuotesAroundAnEtag() {
        val file = MultiStatus.parse(realListing, "/dav")[1]
        assertEquals("21ac2586e213d1f490778a07bf0025a98fc57595863a282372bac594b398322b", file.etag)
        assertEquals(17L, file.size)
        assertEquals("image/jpeg", file.contentType)
        assertTrue((file.lastModifiedEpochMs ?: 0) > 0)
    }

    @Test
    fun matchesNamespacesByUriRatherThanPrefix() {
        val prefixed = """<?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/a.txt</D:href>
                <D:propstat><D:prop><D:getcontentlength>5</D:getcontentlength></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>"""
        val entry = MultiStatus.parse(prefixed, "/dav").single()
        assertEquals("/a.txt", entry.path)
        assertEquals(5L, entry.size)
    }

    @Test
    fun acceptsNamespacesDeclaredOnInnerElements() {
        val late = """<multistatus xmlns="DAV:">
              <response><href>/dav/b.txt</href>
                <propstat><prop><x:getcontentlength xmlns:x="DAV:">9</x:getcontentlength></prop>
                <status>HTTP/1.1 200 OK</status></propstat>
              </response>
            </multistatus>"""
        assertEquals(9L, MultiStatus.parse(late, "/dav").single().size)
    }

    @Test
    fun ignoresPropertiesTheServerCouldNotSupply() {
        // The second propstat is the server saying "asked, could not answer".
        // Taking its properties would report a size of nothing as a real value.
        val partial = """<multistatus xmlns="DAV:">
              <response><href>/dav/c.txt</href>
                <propstat><prop><getcontentlength>42</getcontentlength></prop>
                  <status>HTTP/1.1 200 OK</status></propstat>
                <propstat><prop><getcontenttype/><displayname/></prop>
                  <status>HTTP/1.1 404 Not Found</status></propstat>
              </response>
            </multistatus>"""
        val entry = MultiStatus.parse(partial, "/dav").single()
        assertEquals(42L, entry.size)
        assertNull(entry.contentType)
    }

    @Test
    fun toleratesAnAbsoluteHref() {
        val absolute = """<multistatus xmlns="DAV:">
              <response><href>http://host:8080/dav/d.txt</href>
                <propstat><prop><resourcetype/></prop><status>HTTP/1.1 200 OK</status></propstat>
              </response>
            </multistatus>"""
        assertEquals("/d.txt", MultiStatus.parse(absolute, "/dav").single().path)
    }

    @Test
    fun readsAWeakEtag() {
        val weak = """<multistatus xmlns="DAV:">
              <response><href>/dav/e.txt</href>
                <propstat><prop><getetag>W/"abc"</getetag></prop><status>HTTP/1.1 200 OK</status></propstat>
              </response>
            </multistatus>"""
        assertEquals("abc", MultiStatus.parse(weak, "/dav").single().etag)
    }

    @Test
    fun reportsRubbishAsMalformedRatherThanThrowingSomethingElse() {
        val broken = "<multistatus xmlns=\"DAV:\"><response><href>/dav/f"
        val thrown = runCatching { MultiStatus.parse(broken, "/dav") }.exceptionOrNull()
        assertTrue(thrown is DavError.Malformed, "was $thrown")
    }
}
