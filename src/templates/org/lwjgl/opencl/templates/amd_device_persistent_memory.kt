/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.opencl.templates

import org.lwjgl.generator.*
import org.lwjgl.opencl.*

fun amd_device_persistent_memory() = "AMDDevicePersistentMemory".nativeClassCL("amd_device_persistent_memory", AMD) {

	javaDoc("Native bindings to the <strong>$templateName</strong> extension.")

	IntConstant.block(
		"""
		{@code cl_mem_flags} bit. Buffers and images allocated with this flag reside in host-visible device memory. This flag is mutually exclusive with the
		flags {@link CL10#CL_MEM_ALLOC_HOST_PTR} and {@link Cl10#CL_MEM_USE_HOST_PTR}.
		""",

		"MEM_USE_PERSISTENT_MEM_AMD".expr<Int>("1 << 6")
	)

}