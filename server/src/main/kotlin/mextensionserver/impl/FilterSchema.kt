package mextensionserver.impl

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * A source's filters as a client can draw them. `filtersManga` returns the
 * FilterList as Jackson serialises it: no type, and each extension's own
 * fields (minified to `a`, `b`, ...) beside the base ones, so a tri-state, a
 * header and a select can only be told apart by guessing, and a Select's
 * values may be objects whose label is their toString. Here the type comes
 * from the base class and every label is the toString Mihon's own UI draws.
 *
 * The way back in is unchanged: `getSearchManga`'s filterList, matched by name.
 */
data class FilterField(
    val type: String,
    val name: String,
    val state: Any? = null,
    val values: List<String>? = null,
    val children: List<FilterField>? = null,
)

data class SortState(
    val index: Int,
    val ascending: Boolean,
)

object FilterSchema {
    fun of(filters: FilterList): List<FilterField> = filters.mapNotNull(::field)

    fun of(filters: AnimeFilterList): List<FilterField> = filters.mapNotNull(::animeField)

    private fun field(filter: Any?): FilterField? =
        when (filter) {
            is Filter.Header -> FilterField("header", filter.name)
            is Filter.Separator -> FilterField("separator", filter.name)
            is Filter.Select<*> -> FilterField("select", filter.name, filter.state, filter.values.map(::label))
            is Filter.Text -> FilterField("text", filter.name, filter.state)
            is Filter.CheckBox -> FilterField("checkbox", filter.name, filter.state)
            is Filter.TriState -> FilterField("tristate", filter.name, filter.state)
            is Filter.Group<*> -> FilterField("group", filter.name, children = filter.state.mapNotNull(::field))
            is Filter.Sort ->
                FilterField(
                    "sort",
                    filter.name,
                    filter.state?.let { SortState(it.index, it.ascending) },
                    filter.values.toList(),
                )
            // A group may hold something that isn't a filter; there's nothing to draw.
            else -> null
        }

    private fun animeField(filter: Any?): FilterField? =
        when (filter) {
            is AnimeFilter.Header -> FilterField("header", filter.name)
            is AnimeFilter.Separator -> FilterField("separator", filter.name)
            is AnimeFilter.Select<*> -> FilterField("select", filter.name, filter.state, filter.values.map(::label))
            is AnimeFilter.Text -> FilterField("text", filter.name, filter.state)
            is AnimeFilter.CheckBox -> FilterField("checkbox", filter.name, filter.state)
            is AnimeFilter.TriState -> FilterField("tristate", filter.name, filter.state)
            is AnimeFilter.Group<*> -> FilterField("group", filter.name, children = filter.state.mapNotNull(::animeField))
            is AnimeFilter.Sort ->
                FilterField(
                    "sort",
                    filter.name,
                    filter.state?.let { SortState(it.index, it.ascending) },
                    filter.values.toList(),
                )
            else -> null
        }

    private fun label(value: Any?): String = value?.toString() ?: ""
}
