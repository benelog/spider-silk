package net.benelog.spidersilk.opentelemetry.agent;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The instrumentation module {@code spider-silk}: the route of a Spider Silk request.
 *
 * <p>A Spider Silk application is one servlet mapped at {@code /*}, so the agent's servlet
 * instrumentation reports that mapping as the {@code http.route} of every request, and every
 * request becomes the one span {@code GET /*}. The agent instruments the router of Spring MVC, and
 * this module does the same for Spider Silk's.
 *
 * <p>It applies only where Spider Silk is on the class path, and
 * {@code -Dotel.instrumentation.spider-silk.enabled=false} switches it off. It declares no muzzle
 * references, because the advice calls nothing but {@code Route.path()}.
 */
public final class SpiderSilkInstrumentationModule extends InstrumentationModule {

    public SpiderSilkInstrumentationModule() {
        super("spider-silk");
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        return hasClassesNamed(RouteInstrumentation.WEB_REQUEST);
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new RouteInstrumentation());
    }
}
