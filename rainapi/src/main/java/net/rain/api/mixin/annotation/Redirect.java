package net.rain.api.mixin.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Redirect {
    String method();
    String descriptor() default "";
    At at();
    
    @interface At {
        String value();
        String target();
        int ordinal() default -1;
    }
}