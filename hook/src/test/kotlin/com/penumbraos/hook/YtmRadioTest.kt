package com.penumbraos.hook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtmRadioTest {
    @Test
    fun searchUsesWebRemixSongsCatalog() {
        val body = YtmRadio.searchBody("Tightrope Electric Light Orchestra")
        val client = body.getJSONObject("context").getJSONObject("client")

        assertEquals("WEB_REMIX", client.getString("clientName"))
        assertEquals("EgWKAQIIAWoMEA4QChADEAQQCRAF", body.getString("params"))
        assertEquals("Tightrope Electric Light Orchestra", body.getString("query"))
    }

    @Test
    fun radioUsesRealYtmAutomixParameters() {
        val body = YtmRadio.radioBody("_Ynj_70xKqk")

        assertEquals("_Ynj_70xKqk", body.getString("videoId"))
        assertEquals("RDAMVM_Ynj_70xKqk", body.getString("playlistId"))
        assertEquals("wAEB", body.getString("params"))
        assertEquals("AUTOMIX_SETTING_NORMAL", body.getString("tunerSettingValue"))
        assertEquals("WEB_REMIX", body.getJSONObject("context").getJSONObject("client").getString("clientName"))
    }

    @Test
    fun parsesYtmSongSearchRenderer() {
        val root = JSONObject("""
          {"contents":{"musicShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Tightrope"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Song"},{"text":" • "},{"text":"Electric Light Orchestra"},{"text":" • "},{"text":"A New World Record"},{"text":" • "},{"text":"5:07"}
                ]}}}
              ],
              "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"_Ynj_70xKqk"}}}}}}
            }}
          ]}}}
        """.trimIndent())

        val tracks = YtmRadio.parseSearch(root, 5)
        assertEquals(1, tracks.size)
        assertEquals("_Ynj_70xKqk", tracks[0].videoId)
        assertEquals("Tightrope", tracks[0].title)
        assertEquals("Electric Light Orchestra", tracks[0].artist)
        assertEquals(307, tracks[0].durationSec)
        assertTrue(tracks[0].videoId.length == 11)
    }

    @Test
    fun liveAutomixReturnsARealCrossArtistQueue() {
        val tracks = YtmRadio.radioForVideo("_Ynj_70xKqk", 30)
        assertTrue("expected at least 25 YTM radio tracks, got ${tracks.size}", tracks.size >= 25)
        val artists = tracks.map { it.artist.lowercase() }.distinct()
        assertTrue("expected at least 10 artists, got ${artists.size}: $artists", artists.size >= 10)
    }
}
