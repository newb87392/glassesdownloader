package com.open.glassesdownloader

import org.junit.Assert.*
import org.junit.Test

class MediaIndexTest {
    @Test fun marksTSeriesListingSizesAsRoundedKib() {
        val index = MediaIndex.parse(
            """{"result":0,"info":[{"files":[{"name":"EVENT/photo.jpg","size":249,"createtimestr":"20260101010101"}]}]}""",
            sizeUnit = MediaIndex.SizeUnit.KIB_FLOOR)
        assertEquals(249L, index.items.single().expectedSize)
        assertEquals(MediaIndex.SizeUnit.KIB_FLOOR, index.items.single().sizeUnit)
    }

    @Test fun parsesTheTSeriesLayout() {
        val result = MediaIndex.parse(
            """
            {"result":0,"info":[
              {"folder":"EVENT","files":[
                {"name":"EVENT/20260101010101000.jpg","size":422,"createtimestr":"20260101010101","type":1}
              ],"count":1},
              {"folder":"LOOP","files":[
                {"name":"LOOP/20260101010202000.mp4","size":149932,"createtimestr":"20260101010202","type":2}
              ],"count":1}
            ]}
            """.trimIndent()
        )
        assertEquals(2, result.declared)
        assertTrue(result.rejected.isEmpty())
        assertEquals("EVENT/20260101010101000.jpg", result.items[0].remotePath)
        assertEquals("EVENT", result.items[0].localFolder)
        assertEquals("20260101010101000.jpg", result.items[0].localName)
        assertEquals("20260101010101", result.items[0].timestamp)
        assertEquals(422L, result.items[0].expectedSize)
        assertEquals(149932L, result.items[1].expectedSize)
        assertEquals("LOOP", result.items[1].localFolder)
    }

    // The older firmware reports bare file names; the downloader adds DCIM/100HUNTI/ itself, so
    // the folder for local storage has to come from the media type.
    @Test fun derivesLocalFoldersForBareNames() {
        val result = MediaIndex.parse(
            """{"result":0,"info":[{"folder":"100HUNTI","files":[
                 {"name":"100_0001.JPG","createtimestr":"20260101010101","type":1},
                 {"name":"100_0002.MP4","createtimestr":"20260101010202","type":2},
                 {"name":"V0001.WAV","createtimestr":"20260101010303","type":3}
               ]}]}"""
        )
        assertEquals(listOf("EVENT", "LOOP", "AAC"), result.items.map { it.localFolder })
        assertEquals(listOf("100_0001.JPG", "100_0002.MP4", "V0001.WAV"), result.items.map { it.localName })
    }

    @Test fun reportsUnsupportedAndUnsafeNamesInsteadOfDroppingThem() {
        val result = MediaIndex.parse(
            """{"result":0,"info":[{"files":[
                 {"name":"EVENT/ok.jpg"},
                 {"name":"EVENT/notes.txt"},
                 {"name":"../../etc/passwd.jpg"},
                 {"name":"EVENT/bad name.jpg"}
               ]}]}"""
        )
        assertEquals(1, result.items.size)
        assertEquals(4, result.declared)
        assertEquals(3, result.rejected.size)
    }

    @Test fun deduplicatesRepeatedEntries() {
        val result = MediaIndex.parse(
            """{"result":0,"info":[{"files":[
                 {"name":"EVENT/a.jpg"},{"name":"EVENT/a.jpg"}
               ]}]}"""
        )
        assertEquals(1, result.items.size)
    }

    @Test fun rejectsANonZeroResult() {
        try {
            MediaIndex.parse("""{"result":-1,"info":[]}""")
            fail("expected a rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("result=-1"))
        }
    }
}
