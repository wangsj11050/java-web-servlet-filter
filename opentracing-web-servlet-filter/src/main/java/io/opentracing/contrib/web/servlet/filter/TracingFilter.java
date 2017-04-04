package io.opentracing.contrib.web.servlet.filter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;

/**
 * Tracing servlet filter.
 *
 * Filter can be programmatically added to {@link ServletContext} or initialized via web.xml.
 *
 * Following code examples show possible initialization.
 *
 * <pre>
 * {@code
  * TracingFilter filter = new TracingFilter(tracer);
 *  servletContext.addFilter("tracingFilter", filter);
  * }
 * </pre>
 *
 * Or include filter in web.xml and:
 * <pre>
 * {@code
 *  servletContext.setAttribute({@link TracingFilter#TRACER}, tracer);
 *  servletContext.setAttribute({@link TracingFilter#SPAN_DECORATORS}, decorators); // optional, if no present
 *  ServletFilterSpanDecorator.STANDARD_TAGS is applied
 * }
 * </pre>
 *
 * Current server span is accessible via {@link HttpServletRequest#getAttribute(String)} with name
 * {@link TracingFilter#SERVER_SPAN_CONTEXT}.
 *
 * @author Pavol Loffay
 */
public class TracingFilter implements Filter {

    private static final Logger log = Logger.getLogger(TracingFilter.class.getName());

    /**
     * Use as a key of {@link ServletContext#setAttribute(String, Object)} to set Tracer
     */
    public static final String TRACER = TracingFilter.class.getName() + ".tracer";
    /**
     * Use as a key of {@link ServletContext#setAttribute(String, Object)} to set span decorators
     */
    public static final String SPAN_DECORATORS = TracingFilter.class.getName() + ".spanDecorators";
    /**
    /**
     * Used as a key of {@link HttpServletRequest#setAttribute(String, Object)} to inject server span context
     */
    public static final String SERVER_SPAN_CONTEXT = TracingFilter.class.getName() + ".activeSpanContext";
    /**
     * Key of {@link HttpServletRequest#setAttribute(String, Object)} with injected span wrapper.
     *
     * <p>This is meant to be used only in higher layers like Spring interceptor to add more data to the span.
     * <p>Do not use this as local span to trace business logic, instead use {@link #SERVER_SPAN_CONTEXT}.
     */
    public static final String SERVER_SPAN_WRAPPER = TracingFilter.class.getName() + ".activeServerSpan";

    private FilterConfig filterConfig;
    private boolean skipFilter;

    private Tracer tracer;
    private List<ServletFilterSpanDecorator> spanDecorators;

    /**
     * When using this constructor one has to provide required ({@link TracingFilter#TRACER}
     * attribute in {@link ServletContext#setAttribute(String, Object)}.
     */
    public TracingFilter() {}

    /**
     * @param tracer
     */
    public TracingFilter(Tracer tracer) {
        this(tracer, Collections.singletonList(ServletFilterSpanDecorator.STANDARD_TAGS));
    }

    /**
     *
     * @param tracer tracer
     * @param spanDecorators decorators
     */
    public TracingFilter(Tracer tracer, List<ServletFilterSpanDecorator> spanDecorators) {
        this.tracer = tracer;
        this.spanDecorators = new ArrayList<>(spanDecorators);
        this.spanDecorators.removeAll(Collections.singleton(null));
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig;

        // init if the tracer is null
        if (tracer == null) {
            ServletContext servletContext = filterConfig.getServletContext();

            Object contextAttribute = servletContext.getAttribute(TRACER);
            if (contextAttribute == null) {
                log.severe("Tracer was not found in `ServletContext.getAttribute(TRACER)`, skipping tracing filter");
                this.skipFilter = true;
                return;
            }
            if (!(contextAttribute instanceof Tracer)) {
                log.severe("Tracer from `ServletContext.getAttribute(TRACER)`, is not an instance of " +
                        "io.opentracing.Tracer, skipping tracing filter");
                this.skipFilter = true;
                return;
            }
            this.tracer = (Tracer)contextAttribute;

            this.spanDecorators = (List<ServletFilterSpanDecorator>)servletContext.getAttribute(SPAN_DECORATORS);
            if (this.spanDecorators == null) {
                this.spanDecorators = Arrays.asList(ServletFilterSpanDecorator.STANDARD_TAGS);
            }
            this.spanDecorators.removeAll(Collections.singleton(null));
        }
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        if (skipFilter) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
        HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;

        /**
         * If request is traced then do not start new span.
         */
        if (servletRequest.getAttribute(SERVER_SPAN_CONTEXT) != null) {
            chain.doFilter(servletRequest, servletResponse);
        } else if (isTraced(httpRequest, httpResponse)){
            SpanContext extractedContext = tracer.extract(Format.Builtin.HTTP_HEADERS,
                    new HttpServletRequestExtractAdapter(httpRequest));

            final Span span = tracer.buildSpan(httpRequest.getMethod())
                    .asChildOf(extractedContext)
                    .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                    .start();

            final SpanWrapper spanWrapper = new SpanWrapper(span);
            httpRequest.setAttribute(SERVER_SPAN_WRAPPER, spanWrapper);
            httpRequest.setAttribute(SERVER_SPAN_CONTEXT, span.context());

            for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
                spanDecorator.onRequest(httpRequest, span);
            }

            try {
                chain.doFilter(servletRequest, servletResponse);
                if (!httpRequest.isAsyncStarted()) {
                    for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
                        spanDecorator.onResponse(httpRequest, httpResponse, span);
                    }
                }
                // catch all exceptions (e.g. RuntimeException, ServletException...)
            } catch (Throwable ex) {
                for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
                    spanDecorator.onError(httpRequest, httpResponse, ex, span);
                }
                throw ex;
            } finally {
                if (httpRequest.isAsyncStarted()) {
                    // what if async is already finished? This would not be called
                    httpRequest.getAsyncContext()
                            .addListener(new AsyncListener() {
                        @Override
                        public void onComplete(AsyncEvent event) throws IOException {
                            for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
                                spanDecorator.onResponse((HttpServletRequest) event.getSuppliedRequest(),
                                        (HttpServletResponse) event.getSuppliedResponse(), span);
                            }
                            spanWrapper.finish();
                        }

                        @Override
                        public void onTimeout(AsyncEvent event) throws IOException {
                            for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
                                spanDecorator.onTimeout((HttpServletRequest) event.getSuppliedRequest(),
                                        (HttpServletResponse) event.getSuppliedResponse(),
                                        event.getAsyncContext().getTimeout(), span);
                            }
                            spanWrapper.finish();
                        }

                        @Override
                        public void onError(AsyncEvent event) throws IOException {
                            for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
                                spanDecorator.onError((HttpServletRequest) event.getSuppliedRequest(),
                                        (HttpServletResponse) event.getSuppliedResponse(), event.getThrowable(), span);
                            }
                            spanWrapper.finish();
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event) throws IOException {
                        }
                    });
                } else {
                    spanWrapper.finish();
                }
            }
        }
    }

    @Override
    public void destroy() {
        this.filterConfig = null;
    }

    /**
     * It checks whether a request should be traced or not.
     *
     * @param httpServletRequest request
     * @param httpServletResponse response
     * @return whether request should be traced or not
     */
    protected boolean isTraced(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        return true;
    }

    /**
     * Get context of server span.
     *
     * @param servletRequest request
     * @return server span context
     */
    public static SpanContext serverSpanContext(ServletRequest servletRequest) {
        return (SpanContext) servletRequest.getAttribute(SERVER_SPAN_CONTEXT);
    }
}
