/**
 * Copyright (C) 2006 Google Inc.
 * Copyright (C) 2014 John Leacox
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.leacox.dagger.servlet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * <p/>
 * Apply this filter in web.xml above all other filters (typically), to all requests where you plan
 * to use servlet scopes. This is also needed in order to dispatch requests to injectable filters
 * and servlets:
 * <pre>
 *  &lt;filter&gt;
 *    &lt;filter-name&gt;daggerFilter&lt;/filter-name&gt;
 *    &lt;filter-class&gt;<b>com.leacox.dagger.servlet.DaggerFilterr</b>&lt;/filter-class&gt;
 *  &lt;/filter&gt;
 *
 *  &lt;filter-mapping&gt;
 *    &lt;filter-name&gt;daggerFilter&lt;/filter-name&gt;
 *    &lt;url-pattern&gt;/*&lt;/url-pattern&gt;
 *  &lt;/filter-mapping&gt;
 *  </pre>
 *
 * This filter must appear before every filter that makes use of Dagger injection or servlet
 * scopes functionality. Typically, you will only register this filter in web.xml and register
 * any other filters (and servlets) using a {@link DaggerServletContextListener}.
 *
 * @author crazybob@google.com (Bob Lee)
 * @author dhanji@gmail.com (Dhanji R. Prasanna)
 * @author John Leacox
 */
@Singleton
public class DaggerFilter implements Filter {
    private static ThreadLocal<Context> localContext = new ThreadLocal<Context>();
    static volatile FilterPipeline pipeline = new DefaultFilterPipeline();

    private static volatile WeakReference<ServletContext> servletContext = new WeakReference<ServletContext>(null);

    private static final String MULTIPLE_INJECTORS_WARNING =
            "Multiple Servlet object graphs detected. This is a warning "
                    + "indicating that you have more than one "
                    + DaggerFilter.class.getSimpleName() + " running "
                    + "in your web application. If this is deliberate, you may safely "
                    + "ignore this message. If this is NOT deliberate however, "
                    + "your application may not work as expected.";

    // Default constructor needed for container managed construction
    public DaggerFilter() {
    }

    // We allow both the static and dynamic versions of the pipeline to exist.
    // TODO: I don't think this ever gets used. Injection happens via the constructor instead.
    @Inject
    FilterPipeline injectedPipeline;

    @Inject
    DaggerFilter(FilterPipeline pipeline) {
        // This can happen if you create many injectors and they all have their own
        // servlet module. This is legal, caveat a small warning.
        if (DaggerFilter.pipeline instanceof ManagedFilterPipeline) {
            Logger.getLogger(DaggerFilter.class.getName()).warning(MULTIPLE_INJECTORS_WARNING);
        }

        // We overwrite the default pipeline
        DaggerFilter.pipeline = pipeline;
    }

    @VisibleForTesting
    static void reset() {
        pipeline = new DefaultFilterPipeline();
        localContext.remove();
    }

    private FilterPipeline getPipeline() {
        return injectedPipeline != null ? injectedPipeline : pipeline;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        Context previousContext = localContext.get();

        // Prefer the injected pipeline, but fall back on the static one for web.xml users.
        final FilterPipeline filterPipeline = getPipeline();

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        HttpServletRequest originalHttpRequest = (previousContext != null) ?
                previousContext.getOriginalRequest() : httpRequest;

        try {
            new Context(originalHttpRequest, httpRequest, httpResponse).call(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    //dispatch across the servlet pipeline, ensuring web.xml's filterchain is honored
                    filterPipeline.dispatch(request, response, chain);
                    return null;
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (ServletException e) {
            throw e;
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    // TODO: When ScopingObjectGraph doesn't need to be in the dagger package anymore these methods can become package private

    /**
     * This method should not be used directly.
     * <p/>
     * This method is used by {@code ScopingObjectGraph} for provided scoped injections. Since
     * {@code ScopingObjectGraph} must be in the {@code dagger} package to work with Dagger, this method must be
     * publicly accessible. If {@code ScopingObjectGraph} can be moved to the {@code com.leacox.dagger.servlet} package
     * this method can become package private.
     */
    @VisibleForTesting
    public static HttpServletRequest getRequest() {
        Context context = getContext();

        if (context == null) {
            return null;
        }

        return context.getRequest();
    }

    /**
     * This method should not be used directly.
     * <p/>
     * This method is used by {@code ScopingObjectGraph} for provided scoped injections. Since
     * {@code ScopingObjectGraph} must be in the {@code dagger} package to work with Dagger, this method must be
     * publicly accessible. If {@code ScopingObjectGraph} can be moved to the {@code com.leacox.dagger.servlet} package
     * this method can become package private.
     */
    @VisibleForTesting
    public static HttpServletResponse getResponse() {
        Context context = getContext();

        if (context == null) {
            return null;
        }

        return context.getResponse();
    }

    /**
     * This method should not be used directly.
     * <p/>
     * This method is used by {@code ScopingObjectGraph} for provided scoped injections. Since
     * {@code ScopingObjectGraph} must be in the {@code dagger} package to work with Dagger, this method must be
     * publicly accessible. If {@code ScopingObjectGraph} can be moved to the {@code com.leacox.dagger.servlet} package
     * this method can become package private.
     */
    @VisibleForTesting
    public static ServletContext getServletContext() {
        return servletContext.get();
    }

    private static Context getContext() {
        return localContext.get();
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        ServletContext servletContext = filterConfig.getServletContext();

        // Store servlet context in a weakreference, for injection
        DaggerFilter.servletContext = new WeakReference<ServletContext>(servletContext);

        // In the default pipeline, this is a noop. However, if replaced
        // by a managed pipeline, a lazy init will be triggered the first time
        // dispatch occurs.
        FilterPipeline filterPipeline = getPipeline();
        filterPipeline.initPipeline(servletContext);
    }

    @Override
    public void destroy() {
        try {
            // Destroy all registered filters & servlets in that order
            FilterPipeline filterPipeline = getPipeline();
            filterPipeline.destroyPipeline();
        } finally {
            reset();
            servletContext.clear();
        }
    }

    private static class Context {
        private final HttpServletRequest originalRequest;
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        volatile Thread owner;

        Context(HttpServletRequest originalRequest, HttpServletRequest request, HttpServletResponse response) {
            this.originalRequest = originalRequest;
            this.request = request;
            this.response = response;
        }

        HttpServletRequest getOriginalRequest() {
            return originalRequest;
        }

        HttpServletRequest getRequest() {
            return request;
        }

        HttpServletResponse getResponse() {
            return response;
        }

        <T> T call(Callable<T> callable) throws Exception {
            Thread oldOwner = owner;
            owner = Thread.currentThread();

            Context previousContext = localContext.get();
            localContext.set(this);
            try {
                return callable.call();
            } finally {
                owner = oldOwner;
                localContext.set(previousContext);
            }
        }
    }
}
