package dev.stratus.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteLayoutTest {

    private val layout = RemoteLayout()

    private fun asset(
        localId: String = "local-1",
        at: Long = utcMillis(2026, 9, 17, 14, 30, 22),
        name: String = "IMG_0001.HEIC",
        size: Long = 4_012_345,
        motion: MotionPart? = null,
    ) = Asset(localId, at, name, size, motion)

    @Test
    fun putsAPhotographWhereSomebodyWouldLookForIt() {
        // Pinned exactly, and that is the point of this one: changing how a path
        // is derived orphans every library already uploaded, which would show up
        // as the whole camera roll going up a second time. It should take a
        // failing test and a deliberate answer, never a tidy-up.
        assertEquals(
            "/Photos/2026/09/2026-09-17_143022_IMG_0001.474bfe2d.heic",
            layout.pathFor(asset()),
        )
    }

    @Test
    fun givesTheSameAnswerEveryTimeForTheSamePhotograph() {
        // The whole point. If this is ever false, a reinstall re-uploads the roll.
        assertEquals(layout.pathFor(asset()), layout.pathFor(asset()))
    }

    @Test
    fun doesNotCareWhatThePlatformCallsTheAssetToday() {
        // MediaStore ids change on a rescan and PHAsset identifiers do not survive
        // a restore, which is exactly when the path must not move.
        assertEquals(
            layout.pathFor(asset(localId = "before-the-restore")),
            layout.pathFor(asset(localId = "completely-different-afterwards")),
        )
    }

    @Test
    fun separatesTwoCamerasThatBothCalledItImg0001() {
        // The classic collision: two phones, the same counter, the same second.
        // Only the byte count differs, and that is enough.
        val one = asset(size = 4_012_345)
        val other = asset(size = 3_998_112)
        assertNotEquals(layout.pathFor(one), layout.pathFor(other))
    }

    @Test
    fun separatesTheSameNameTakenInDifferentSeconds() {
        val morning = asset(at = utcMillis(2026, 9, 17, 9, 0, 1))
        val evening = asset(at = utcMillis(2026, 9, 17, 21, 0, 1))
        assertNotEquals(layout.pathFor(morning), layout.pathFor(evening))
        assertEquals(layout.directoryFor(morning), layout.directoryFor(evening))
    }

    @Test
    fun treatsTheSamePhotographImportedTwiceAsOne() {
        // Same second, same name, same size. Storing it once is the right answer
        // rather than a collision worth engineering around.
        assertEquals(layout.pathFor(asset(localId = "a")), layout.pathFor(asset(localId = "b")))
    }

    @Test
    fun padsTheDateSoFoldersAndNamesSort() {
        val january = asset(at = utcMillis(2026, 1, 2, 3, 4, 5))
        assertTrue(layout.directoryFor(january).endsWith("/2026/01/"), layout.directoryFor(january))
        assertTrue(layout.pathFor(january).contains("2026-01-02_030405_"), layout.pathFor(january))
    }

    @Test
    fun keepsALivePhotoTogether() {
        val live = asset(motion = MotionPart("IMG_0001.MOV", 1_200_000))
        val still = layout.pathFor(live)
        val motion = layout.motionPathFor(live)!!

        // Same folder and the same stamp, so the pair cannot drift apart even if
        // the movie carries a timestamp of its own.
        assertEquals(still.substringBeforeLast('/'), motion.substringBeforeLast('/'))
        assertTrue(motion.endsWith(".mov"), motion)
        assertTrue(still.endsWith(".heic"), still)
        assertNull(layout.motionPathFor(asset()))
    }

    @Test
    fun copesWithNamesAServerGaveUp() {
        val bare = layout.pathFor(asset(name = "no-extension"))
        assertTrue(bare.matches(Regex(".*no-extension\\.[0-9a-f]{8}$")), bare)
        assertTrue(layout.pathFor(asset(name = "SHOUTING.JPG")).endsWith(".jpg"))
    }

    @Test
    fun rootsItselfWhereverItIsTold() {
        // Stored with the session rather than hardcoded, because moving it later
        // relocates somebody's whole library.
        val elsewhere = RemoteLayout("Camera Backup")
        assertTrue(elsewhere.pathFor(asset()).startsWith("/Camera Backup/2026/09/"))
        assertTrue(RemoteLayout("/nested/root/").pathFor(asset()).startsWith("/nested/root/2026/"))
    }

    @Test
    fun namesEveryFolderARebuildHasToWalk() {
        val assets = listOf(
            asset(at = utcMillis(2026, 9, 1, 0, 0, 0)),
            asset(at = utcMillis(2026, 9, 30, 0, 0, 0)),
            asset(at = utcMillis(2025, 12, 25, 0, 0, 0)),
        )
        // One request per month rather than one per photograph: ten years of
        // pictures is a hundred and twenty listings, paid once on a reinstall.
        assertEquals(listOf("/Photos/2025/12/", "/Photos/2026/09/"), layout.directoriesFor(assets))
    }
}
