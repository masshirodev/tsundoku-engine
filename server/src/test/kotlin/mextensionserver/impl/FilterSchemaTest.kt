package mextensionserver.impl

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import mextensionserver.model.DataBody
import java.net.URLClassLoader
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

class FilterSchemaTest {
    // Shaped as extensions write them: subclasses with fields of their own,
    // which Jackson would dump beside the base ones.
    private class StatusSelect : Filter.Select<Pair<String, String>>(
        "Status",
        arrayOf("All" to "", "Ongoing" to "ongoing"),
        1,
    ) {
        val query = "status"
    }

    private class Genre(
        name: String,
        val id: String,
    ) : Filter.TriState(name)

    private class Genres : Filter.Group<Genre>("Genre", listOf(Genre("Action", "1"), Genre("Gore", "2").apply { state = Filter.TriState.STATE_EXCLUDE }))

    private class Author : Filter.Text("Author", "Oda")

    private class Chapters : Filter.CheckBox("Has chapters", true)

    private class SortBy : Filter.Sort("Sort by", arrayOf("Latest", "Popular"), Selection(1, false))

    private class Mode(
        name: String,
    ) : Filter.Select<String>(name, arrayOf("And", "Or"))

    private class Modes : Filter.Group<Any>("Tags mode", listOf(Mode("Included"), "not a filter"))

    @Test
    fun `each filter says its type, with labels as toString`() {
        val schema =
            FilterSchema.of(
                FilterList(
                    Filter.Header("Ignored when searching by text"),
                    Filter.Separator(),
                    StatusSelect(),
                    Author(),
                    Chapters(),
                    Genres(),
                    SortBy(),
                    Modes(),
                ),
            )

        assertEquals(
            listOf(
                FilterField("header", "Ignored when searching by text"),
                FilterField("separator", ""),
                FilterField("select", "Status", 1, listOf("(All, )", "(Ongoing, ongoing)")),
                FilterField("text", "Author", "Oda"),
                FilterField("checkbox", "Has chapters", true),
                FilterField(
                    "group",
                    "Genre",
                    children = listOf(FilterField("tristate", "Action", 0), FilterField("tristate", "Gore", 2)),
                ),
                FilterField("sort", "Sort by", SortState(1, false), listOf("Latest", "Popular")),
                FilterField("group", "Tags mode", children = listOf(FilterField("select", "Included", 0, listOf("And", "Or")))),
            ),
            schema,
        )
    }

    private class AnimeSort : AnimeFilter.Sort("Order", arrayOf("Rating"))

    private class AnimeType : AnimeFilter.Select<String>("Type", arrayOf("TV", "Movie"))

    @Test
    fun `anime filters come out the same way, and an unset sort has no state`() {
        assertEquals(
            listOf(
                FilterField("select", "Type", 0, listOf("TV", "Movie")),
                FilterField("sort", "Order", null, listOf("Rating")),
            ),
            FilterSchema.of(AnimeFilterList(AnimeType(), AnimeSort())),
        )
    }

    private class FilteredSource : CatalogueSource {
        override val id = 2L
        override val name = "Filtered"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

        override fun getFilterList() = FilterList(Chapters())
    }

    @Test
    fun `filterSchemaManga answers the schema of the source's filters`() {
        val extension =
            MExtensionServerLoader.LoadedExtension(
                initialSources = listOf(FilteredSource()),
                packageInfo =
                    PackageInfo().apply {
                        packageName = "eu.kanade.tachiyomi.extension.en.filtered"
                        versionName = "1.0.0"
                        applicationInfo = ApplicationInfo()
                    },
                jarFile = createTempFile(prefix = "mextensionserver-test-", suffix = ".jar").toFile(),
                classLoader = URLClassLoader(emptyArray()),
            )

        assertEquals(
            listOf(FilterField("checkbox", "Has chapters", true)),
            MihonInvoker.invokeMethod(extension, DataBody(method = "filterSchemaManga")),
        )
    }
}
