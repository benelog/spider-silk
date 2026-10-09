package benchmark.springmvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The Spring MVC side of the benchmark: Spring Boot's defaults, on embedded Tomcat. */
@SpringBootApplication
public class SpringMvcBenchmark {

    public static void main(String[] args) {
        SpringApplication.run(SpringMvcBenchmark.class, args);
    }
}
