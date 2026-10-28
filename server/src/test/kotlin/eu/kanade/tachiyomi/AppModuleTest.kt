package eu.kanade.tachiyomi

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.fullType
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import kotlin.test.Test
import kotlin.test.assertSame

@OptIn(ExperimentalSerializationApi::class)
class AppModuleTest {
    // Sources inject it as `injectLazy<ProtoBuf>()`, so it must be found under
    // ProtoBuf itself: registered without the type parameter it is keyed as
    // ProtoBuf.Default, and MANGA Plus died in its static initialiser.
    @Test
    fun `provides ProtoBuf under the type protobuf sources inject`() {
        val scope = InjektScope(DefaultRegistrar())
        scope.importModule(AppModule(App()))

        assertSame(ProtoBuf, scope.getInstance<ProtoBuf>(fullType<ProtoBuf>().type))
    }
}
