package net.rain.api.mixin.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyVariable {
    String method();
    String descriptor() default "";
    At at();
    int index() default -1;
    String name() default "";
    int ordinal() default -1;
    
    @interface At {
        String value();
        int ordinal() default -1;
        int shift() default 0;
    }
}