package benchmark.springmvc;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** A page rendered by Thymeleaf: classpath:/templates/fortunes.html. */
@Controller
public class FortuneController {

    // The template reads Fortune's properties through SpEL, which the AOT
    // processing cannot see from the handler; the native image needs the hint.
    @RegisterReflectionForBinding(Fortune.class)
    @GetMapping("/fortunes")
    public String fortunes(Model model) {
        model.addAttribute("fortunes", Data.FORTUNES);
        return "fortunes";
    }
}
