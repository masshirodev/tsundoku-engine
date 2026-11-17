package mextensionserver.model

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeModelTest {
    @Test
    fun `carries the episode's own picture`() {
        val episode =
            SEpisode.create().apply {
                url = "/watch/ep-1"
                name = "Episode 1"
                preview_url = "https://example.com/ep-1.jpg"
            }

        assertEquals("https://example.com/ep-1.jpg", episode.toJEpisode().preview_url)
    }

    @Test
    fun `has no picture when the extension set none, or a blank one`() {
        val none = SEpisode.create().apply { url = "/watch/ep-2" }
        val blank =
            SEpisode.create().apply {
                url = "/watch/ep-3"
                preview_url = " "
            }

        assertNull(none.toJEpisode().preview_url)
        assertNull(blank.toJEpisode().preview_url)
    }
}
