package eu.kanade.tachiyomi

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

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
    // ProtoBuf.Default, and a protobuf source died in its static initialiser.
    @Test
    fun `provides ProtoBuf under the type protobuf sources inject`() {
        val scope = InjektScope(DefaultRegistrar())
        scope.importModule(AppModule(App()))

        assertSame(ProtoBuf, scope.getInstance<ProtoBuf>(fullType<ProtoBuf>().type))
    }
}
