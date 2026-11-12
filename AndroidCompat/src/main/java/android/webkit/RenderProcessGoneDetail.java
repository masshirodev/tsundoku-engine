/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webkit;

/**
 * This class provides more specific information about why the render process
 * exited. The application may use this to decide how to handle the situation.
 *
 * <p>Kept here rather than taken from the android-jar stub: the 1.0.0 artifact on
 * Suwayomi's android-jar branch was regenerated without this class (Suwayomi
 * ships its own copy), so a fresh dependency resolve could no longer compile
 * KcefWebViewProvider while a warm Gradle cache still could. android.net's
 * Network, NetworkCapabilities and NetworkRequest went the same way; they are
 * Suwayomi's own copies.
 */
public abstract class RenderProcessGoneDetail {
    /**
     * Indicates whether the render process was observed to crash, or whether
     * it was killed by the system.
     *
     * @return True if the render process crashed, otherwise it was killed by
     *     the system.
     */
    public abstract boolean didCrash();

    /**
     * Returns the renderer priority that was set at the time that the
     * renderer exited.
     *
     * @return the priority of the renderer at exit.
     */
    public abstract int rendererPriorityAtExit();
}
