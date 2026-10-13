package benchmark.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The Spring Boot side of the benchmark: Spring Web MVC on Spring Boot's defaults, on embedded Tomcat. */
@SpringBootApplication
public class SpringBootBenchmark {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootBenchmark.class, args);
    }
}
