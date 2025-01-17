/*
* Copyright contributors to the Galasa project 
*/
package dev.galasa.sem;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface CSDInput {
	
	public String file();
	public String cicsTag() default "";
	public String group() default "";
	public boolean startup() default true;
	public boolean continueLines() default false;
	public String minimumRelease() default "";
	public String maximumRelease() default "";
}
