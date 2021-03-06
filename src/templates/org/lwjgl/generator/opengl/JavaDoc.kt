/* 
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.generator.opengl

import java.io.PrintWriter
import org.lwjgl.generator.*

/** Specifies an alternative name for the OpenGL SDK Reference url. */
public class ReferenceGL(val function: String): FunctionModifier() {
	class object: ModifierObject<ReferenceGL> {
		override val key = javaClass<ReferenceGL>()
	}

	override val isSpecial: Boolean = false
	override protected fun validate(func: NativeClassFunction) {
		if ( !func.nativeClass.postfix.isEmpty() )
			throw IllegalArgumentException("The ReferenceGL modifier can only be applied on core functionality.")
	}
}

public fun PrintWriter.printOpenGLJavaDoc(documentation: String, function: String, deprecated: Boolean) {
	val link = link("http://www.opengl.org/sdk/docs/man${if ( deprecated ) "2" else ""}/xhtml/$function.xml", "OpenGL SDK Reference")
	val injectedJavaDoc =
		if ( deprecated )
			"$link <em>- This function is deprecated and unavailable in the Core profile -</em>"
		else
			link

	if ( documentation.indexOf('\n') == -1 ) {
		println("\t/**")
		println("\t * $injectedJavaDoc")
		println("\t * <p/>")
		print("\t * ")
		println(documentation.substring("\t/** ".size, documentation.size - 3))
		println("\t */")
	} else {
		println("\t/**")
		println("\t * $injectedJavaDoc")
		println("\t * <p/>")
		println(documentation.substring("\t/**\n".size))
	}
}