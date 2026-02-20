package net.rain.api.mixin.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyArg {
    String method();
    String descriptor() default "";
    At at();
    int index() default 0;
    
    @interface At {
        String value();
        String target();
        int ordinal() default -1;
    }
}