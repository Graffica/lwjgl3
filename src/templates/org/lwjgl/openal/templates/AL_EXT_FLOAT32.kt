/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.openal.templates

import org.lwjgl.generator.*
import org.lwjgl.openal.*

fun AL_EXT_FLOAT32() = "EXTFloat32".nativeClass(
    packageName = "org.lwjgl.openal",
    templateName = "EXT_FLOAT32",
    prefix = "AL",
    prefixTemplate = "AL",
    functionProvider = FunctionProviderAL
)   {

	nativeImport (
		"OpenAL.h"
	)

	javaDoc("bindings to AL_EXT_FLOAT32 extension.")

	IntConstant.block(
    	"AL_EXT_FLOAT32 tokens.",

    	"FORMAT_MONO_FLOAT32" _ 0x10010,
        "FORMAT_STEREO_FLOAT32" _ 0x10011
    )
}