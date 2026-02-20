package net.rain.api.mixin.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Inject {
    String method();
    String descriptor() default "";
    At at();
    boolean cancellable() default false;
    boolean remap() default true;
    boolean require() default true;
    int expect() default 0;
    
    @interface At {
        String value();
        String target() default "";
        int ordinal() default -1;
        int shift() default 0;
        boolean before() default true;
    }
}