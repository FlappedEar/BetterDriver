package com.flappedear.betterdriver.gopro

import kotlin.test.Test
import kotlin.test.assertEquals

class GoProMediaTest {
    private val start = 1_760_180_000_000L

    private val list = """
        {"id":"1","media":[{"d":"100GOPRO","fs":[
          {"n":"GX010041.MP4","cre":"${start / 1000 - 3600}","mod":"0","s":"100"},
          {"n":"GX010042.MP4","cre":"${start / 1000 + 7200 + 2}","mod":"0","s":"4000000000","glrv":"1","ls":"-1"},
          {"n":"GX020042.MP4","cre":"${start / 1000 + 7200 + 2}","mod":"0","s":"2000"},
          {"n":"GOPR0043.JPG","cre":"${start / 1000 + 7200 + 60}","mod":"0","s":"5"},
          {"n":"GH010044.MP4","cre":"${start / 1000 + 7200 + 4000}","mod":"0","s":"5"}
        ]}]}
    """.trimIndent()

    @Test
    fun parsesTheMediaListWithStringNumbers() {
        val files = GoProMedia.parseList(list)
        assertEquals(5, files.size)
        assertEquals("100GOPRO/GX010042.MP4", files[1].path)
        assertEquals(4_000_000_000, files[1].size)
        assertEquals(2 to 42, files[2].chapterAndNumber)
        assertEquals(null, files[3].chapterAndNumber)
    }

    @Test
    fun picksEveryChapterOfTheRecordingMadeDuringTheSessionWithTheCameraClockInLocalTime() {
        val recording = GoProRecording("g", startAckSessionUs = 0, startAckUtcMillis = start,
            stopAckUtcMillis = start + 1_200_000, cameraUtcOffsetMinutes = 120)
        val picked = GoProMedia.filesFor(recording, GoProMedia.parseList(list))
        assertEquals(listOf("GX010042.MP4", "GX020042.MP4"), picked.map { it.name })
    }

    @Test
    fun alsoMatchesACameraClockInUtc() {
        val utcList = list.replace("${start / 1000 + 7200 + 2}", "${start / 1000 + 2}")
        val recording = GoProRecording("g", startAckSessionUs = 0, startAckUtcMillis = start,
            stopAckUtcMillis = start + 60_000, cameraUtcOffsetMinutes = 120)
        assertEquals(2, GoProMedia.filesFor(recording, GoProMedia.parseList(utcList)).size)
    }
}
