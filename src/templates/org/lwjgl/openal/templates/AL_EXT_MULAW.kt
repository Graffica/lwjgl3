/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.openal.templates

import org.lwjgl.generator.*
import org.lwjgl.openal.*

fun AL_EXT_MULAW() = "EXTMulaw".nativeClassAL("EXT_MULAW") {

	nativeImport (
		"OpenAL.h"
	)

	javaDoc("bindings to AL_EXT_MULAW extension.")

	IntConstant.block(
    	"AL_EXT_MULAW tokens.",

    	"FORMAT_MONO_MULAW_EXT" _ 0x10014,
        "FORMAT_STEREO_MULAW_EXT" _ 0x10015
    )
}