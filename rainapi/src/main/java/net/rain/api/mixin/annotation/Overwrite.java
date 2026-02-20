package net.rain.api.mixin.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Overwrite {
    String method();
    String descriptor() default "";
}