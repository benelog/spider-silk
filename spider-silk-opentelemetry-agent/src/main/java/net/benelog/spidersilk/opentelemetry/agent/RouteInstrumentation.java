package net.benelog.spidersilk.opentelemetry.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRoute;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRouteSource;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.benelog.spidersilk.Route;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The one method the module instruments: {@code WebRequest.withRoute(Route, Map)}.
 *
 * <p>Spider Silk calls it once per request, on the request thread, when the router has matched a
 * route and before any filter or handler of the route runs. The method is internal to core, and
 * this module is the reason its name and signature hold: the test of this module fails when they
 * change.
 *
 * <p>Its argument is the route as registered, with group prefixes resolved, such as
 * {@code /decks/{deckId}}, which is already the path-template syntax of {@code http.route}. The
 * advice hands it to {@link HttpServerRoute#update} with the controller as its source, which
 * outranks the servlet mapping, and the agent renames the server span {@code GET /decks/{deckId}}.
 * A request no route matched, such as a static file, a 404, or a 405, never reaches the method and
 * keeps {@code /*}.
 */
public final class RouteInstrumentation implements TypeInstrumentation {

    static final String WEB_REQUEST = "net.benelog.spidersilk.WebRequest";

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(WEB_REQUEST);
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                named("withRoute").and(takesArguments(2)),
                WithRouteAdvice.class.getName());
    }

    /** Inlined into {@code WebRequest}, so {@link Route} resolves in the application's loader. */
    @SuppressWarnings("unused")
    public static final class WithRouteAdvice {

        private WithRouteAdvice() {
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Argument(0) Route route) {
            HttpServerRoute.update(Context.current(), HttpServerRouteSource.CONTROLLER, route.path());
        }
    }
}
