package okhttp3.internal.connection;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Response;
import tsundoku.browser.BrowserTransport;

/**
 * Takes the place of OkHttp's own ConnectInterceptor, which opens the socket,
 * in the browser target only: first on its classpath, and in place of OkHttp's
 * in the slim jar. A page can't open a socket, so the chain ends here, after
 * the client's and the extension's interceptors have all run, and the request
 * goes to {@link BrowserTransport}. OkHttp's RealCall reaches this class only
 * through {@code INSTANCE}, which is all it needs to keep.
 */
public final class ConnectInterceptor implements Interceptor {
    public static final ConnectInterceptor INSTANCE = new ConnectInterceptor();

    private ConnectInterceptor() {}

    @Override
    public Response intercept(Interceptor.Chain chain) throws IOException {
        return BrowserTransport.execute(chain.request());
    }
}
