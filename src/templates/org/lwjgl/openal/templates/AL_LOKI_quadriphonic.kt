/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.openal.templates

import org.lwjgl.generator.*
import org.lwjgl.openal.*

fun AL_LOKI_quadriphonic() = "LOKIQuadriphonic".nativeClassAL("LOKI_quadriphonic") {
	nativeImport (
		"OpenAL.h"
	)

	javaDoc("bindings to AL_LOKI_quadriphonic extension.")

	IntConstant.block(
    	"AL_LOKI_quadriphonic tokens.",

    	"FORMAT_QUAD8_LOKI" _ 0x10004,
    	"FORMAT_QUAD16_LOKI" _ 0x10005
    )
}