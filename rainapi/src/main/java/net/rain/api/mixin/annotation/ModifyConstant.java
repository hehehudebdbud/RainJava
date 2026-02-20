package net.rain.api.mixin.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyConstant {
    String method();
    String descriptor() default "";
    Constant[] constant();
    
    @interface Constant {
        int intValue() default Integer.MIN_VALUE;
        float floatValue() default Float.MIN_VALUE;
        long longValue() default Long.MIN_VALUE;
        double doubleValue() default Double.MIN_VALUE;
        String stringValue() default "";
        boolean nullValue() default false;
        int ordinal() default -1;
    }
}