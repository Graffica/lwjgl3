/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.openal.templates

import org.lwjgl.generator.*
import org.lwjgl.openal.*

fun AL_EXT_ALAW() = "EXTAlaw".nativeClassAL("EXT_ALAW")  {
	nativeImport (
		"OpenAL.h"
	)

	javaDoc("bindings to AL_EXT_ALAW extension.")

	IntConstant.block(
    	"AL_EXT_ALAW tokens.",

    	"FORMAT_MONO_ALAW_EXT" _ 0x10016,
        "FORMAT_STEREO_ALAW_EXT" _ 0x10017
    )
}