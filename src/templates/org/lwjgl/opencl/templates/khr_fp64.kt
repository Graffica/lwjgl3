/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.opencl.templates

import org.lwjgl.generator.*
import org.lwjgl.opencl.*

fun khr_fp64() = "KHRFP64".nativeClassCL("khr_fp64", AMD) {

	javaDoc("Native bindings to the ${link("http://www.khronos.org/registry/cl/extensions/khr/cl_$templateName.txt", templateName)}  extension.")

	IntConstant.block(
		"cl_device_info",

		"DEVICE_DOUBLE_FP_CONFIG" _ 0x1032
	)

}