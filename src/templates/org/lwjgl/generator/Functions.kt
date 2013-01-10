/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.generator

import java.io.PrintWriter
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.regex.Pattern
import org.lwjgl.generator.opengl.*

/*
	****
	The code below implements the more complex parts of LWJGL's code generation.
	Please only modify if you fully understand what's going on and you know
	what you're doing.
	****

	The basic generation is relatively straightforward. It's an almost 1-to-1 mapping
	of the native function signature to the proper Java -> JNI -> native function code.

	We then try to generate additional Java methods that make the user's life easier. We
	use the TemplateModifiers on the function signature parameters and return values to figure
	out what kind of FunctionTransforms we should apply. Depending on the modifiers, we
	may generate one or more additional methods.
*/

// Global definitions

val RESULT = "__result"
val POINTER_POSTFIX = "Address"
val FUNCTION_ADDRESS = "__functionAddress"

private val API_BUFFER = "__buffer"
private val JNIENV = "__env"

private val JNI_UNDERSCORE_ESCAPE_PATTERN = Pattern.compile("_")

public abstract class Function(
	val returns: ReturnValue,
	val name: String,
	val documentation: String,
	vararg params: Parameter
): TemplateElement() {

	protected val parameters: MutableMap<String, Parameter> = LinkedHashMap<String, Parameter>(); // Maintain order

	{
		for ( param in params )
			parameters.put(param.name, param)
	}

}

// DSL extensions

public fun NativeType.IN(name: String, javadoc: String, links: String = ""): Parameter = Parameter(this, name, ParameterType.IN, javadoc, links)
public fun NativeType.OUT(name: String, javadoc: String, links: String = ""): Parameter = Parameter(this, name, ParameterType.OUT, javadoc, links)
public fun NativeType.INOUT(name: String, javadoc: String, links: String = ""): Parameter = Parameter(this, name, ParameterType.INOUT, javadoc, links)

private fun <T> PrintWriter.printList(items: Map<*, T>, itemPrint: (item: T) -> String?): Unit = printList(items.values(), itemPrint)
private fun <T> PrintWriter.printList(items: Iterable<T>, itemPrint: (item: T) -> String?) {
	val iter = items.iterator()

	var first = true
	while ( iter.hasNext() ) {
		val item = itemPrint(iter.next())
		if ( item != null ) {
			if ( !first )
				print(", ")
			else
				first = false

			print(item)
		}
	}
}

// --- [ Native class functions ] ---

public class NativeClassFunction(
	returns: ReturnValue,
	name: String,
	documentation: String,
	val nativeClass: NativeClass,
	vararg parameters: Parameter
): Function(returns, name, documentation, *parameters) {

	{
		validate();
	}

	private val strippedName = stripPostfix()

	private fun stripPostfix(val stripType: Boolean = false): String {
		if ( parameters.isEmpty() )
			return name

		val param = parameters.values().last()
		if ( !param.isBufferPointer )
			return name

		var name = this.name
		if ( !nativeClass.postfix.isEmpty() && name.endsWith(nativeClass.postfix) )
			name = name.substring(0, name.size - nativeClass.postfix.size)

		var cutCount = if ( name.endsWith("v") ) 1 else 0

		if ( stripType ) {
			val pointerMapping = param.nativeType.mapping as PointerMapping
			val typeChar = when ( pointerMapping ) {
				PointerMapping.DATA_SHORT -> 's'
				PointerMapping.DATA_INT -> 'i'
				PointerMapping.DATA_LONG -> 'l'
				PointerMapping.DATA_FLOAT -> 'f'
				PointerMapping.DATA_DOUBLE -> 'd'
				else -> 0.toChar()
			}

			if ( typeChar != 0.toChar() && name.charAt(name.size - cutCount - 1) == typeChar )
				cutCount++
		}

		return name.substring(0, name.size - cutCount) + nativeClass.postfix
	}

	public val javaDocLink: String
		get() {
			val builder = StringBuilder()

			builder append "{@link #"
			builder append strippedName
			builder append '('

			var first = true
			parameters.values().filter { !it.has(CallbackData.CLASS) } forEach {
				if ( first )
					first = false
				else
					builder append ", "

				builder append if ( it.isBufferPointer )
					"ByteBuffer"
				else
					it.javaMethodType
			}
			builder append ")}"

			return builder.toString()
		}

	private fun getParams(predicate: (Parameter) -> Boolean): Iterator<Parameter> = parameters.values().iterator().filter(predicate)
	private fun getParam(predicate: (Parameter) -> Boolean): Parameter {
		val params = getParams(predicate)
		val param = params.next()
		if ( params.hasNext() )
			throw IllegalStateException("More than one parameter found.")
		return param
	}
	private fun hasParam(predicate: (Parameter) -> Boolean): Boolean = getParams(predicate).hasNext()

	/** Returns a parameter that has the specified ReferenceModifier with the specified reference. Returns null if no such parameter exists. */
	private fun getReferenceParam(modifier: Class<out ReferenceModifier>, reference: String): Parameter? {
		// Assumes at most 1 parameter will be found that references the specified parameter
		val iter = getParams {
			it.hasRef(modifier, reference)
		}
		return if ( iter.hasNext() )
			iter.next()
		else
			null
	}

	private fun hasSimpleParamsOnly(): Boolean {
		if ( returns.isBufferPointer || returns.hasSpecialModifier() )
			return false

		return parameters.values().find { it.isBufferPointer || it.hasSpecialModifier() } == null
	}

	val isSimpleFunction: Boolean
		get() = nativeClass.functionProvider == null && hasSimpleParamsOnly()

	private fun Parameter.error(msg: String) {
		throw IllegalArgumentException("$msg [${nativeClass.className}.${this@NativeClassFunction.name}, parameter: ${this.name}]")
	}

	/** Validates parameters with modifiers that reference other parameters. */
	private fun validate() {
		var returnCount = 0
		parameters.values().forEach {
			if ( it has AutoSize.CLASS ) {
				val bufferParamName = it[AutoSize.CLASS].reference
				val bufferParam = parameters[bufferParamName]
				when {
					bufferParam == null -> it.error("Buffer reference does not exist: AutoSize($bufferParamName)")
					!(bufferParam!!.nativeType is PointerType) -> it.error("Buffer reference must be a pointer type: AutoSize($bufferParamName)")
					!bufferParam!!.isBufferPointer -> it.error("Buffer reference must not be a naked pointer: AutoSize($bufferParamName)")
					else -> {
					}
				}
			}

			if ( it has AutoType.CLASS ) {
				val bufferParamName = it[AutoType.CLASS].reference
				val bufferParam = parameters[bufferParamName]
				when {
					bufferParam == null -> it.error("Buffer reference does not exist: AutoType($bufferParamName)")
					!(bufferParam!!.nativeType is PointerType) -> it.error("Buffer reference must be a pointer type: AutoType($bufferParamName)")
					bufferParam!!.nativeType.mapping != PointerMapping.DATA -> it.error("Pointer reference must have a DATA mapping: AutoType($bufferParamName)")
					else -> {
					}
				}
			}

			if ( it has CallbackData.CLASS ) {
				val functionParamName = it[CallbackData.CLASS].reference
				val functionParam = parameters[functionParamName]
				when {
					functionParam == null -> it.error("Function reference does not exist: CallbackParam($functionParamName)")
					!(functionParam!!.nativeType is CallbackType) -> it.error("Function reference must be a callback type: CallbackParam($functionParamName)")
					else -> {
					}
				}
			}

			if ( it has Return.CLASS ) {
				if ( !returns.isVoid )
					it.error("A return value can only be specified for functions with void return type.")

				returnCount++
				if ( 1 < returnCount )
					it.error("More than one return value found.")

				val returnMod = it[Return.CLASS]
				if ( returnMod != returnValue ) {
					val maxLengthParam = parameters[returnMod.maxLengthParam]
					val lengthParam = parameters[returnMod.lengthParam]
					when {
						maxLengthParam == null -> it.error("The maxLength parameter does not exist: Return(${returnMod.maxLengthParam})")
						maxLengthParam!!.nativeType.mapping != PrimitiveMapping.INT -> it.error("The maxLength parameter must be an integer type: Return(${returnMod.maxLengthParam})")
						lengthParam == null -> it.error("The length parameter does not exist: Return(${returnMod.lengthParam})")
						lengthParam!!.nativeType.mapping != PointerMapping.DATA_INT -> it.error("The length parameter must be an integer pointer type: Return(${returnMod.lengthParam})")
						else -> {
						}
					}
				}
			}

			if ( it has PointerArray.CLASS ) {
				val countParamName = it[PointerArray.CLASS].countParam
				val countParam = parameters[countParamName]
				val lengthsParamName = it[PointerArray.CLASS].lengthsParam
				val lengthsParam = parameters[lengthsParamName]
				when {
					countParam == null -> it.error("Count reference does not exist: PointerArray($countParamName)")
					countParam!!.nativeType.mapping != PrimitiveMapping.INT -> it.error("Count reference must be an integer type: PointerArray($countParamName)")
					lengthsParam != null && lengthsParam.nativeType.mapping != PointerMapping.DATA_INT -> it.error("Lengths reference must be an integer pointer type: PointerArray($lengthsParamName)")
					else -> {
					}
				}
			}
		}
	}

	private fun PrintWriter.generateChecks(mode: GenerationMode, customChecks: List<String>? = null, transforms: Map<QualifiedType, FunctionTransform<out QualifiedType>>? = null) {
		val checks = ArrayList<String>()

		// Validate function address
		if ( nativeClass.functionProvider != null )
			checks add "checkFunctionAddress($FUNCTION_ADDRESS);"

		// We convert multi-byte-per-element buffers to ByteBuffer for NORMAL generation.
		// So we need to scale the length check by the number of bytes per element.
		fun bufferShift(expression: String, param: String, shift: String, transform: FunctionTransform<out QualifiedType>?): String {
			val mapping =
				if ( transform != null && javaClass<AutoTypeTargetTransform>().isAssignableFrom(transform.javaClass) ) {
					(transform as AutoTypeTargetTransform).autoType
				} else
					parameters[param]!!.nativeType.mapping as PointerMapping

			if ( mapping.byteShift == null || mapping.byteShift == "0" )
				return expression

			val builder = StringBuilder(expression.size + 8)

			if ( expression.indexOf(' ') != -1 ) {
				builder append '('
				builder append expression
				builder append ')'
			} else
				builder append expression

			builder append ' '
			builder append shift
			builder append ' '
			builder append mapping.byteShift

			return builder.toString()
		}

		parameters.values().forEach {
			var prefix =
				if ( it has Nullable.CLASS )
					"if ( ${it.name} != null ) "
				else {
					if ( it.nativeType.mapping == PointerMapping.NAKED_POINTER && (!it.has(CallbackData.CLASS) && it.nativeType !is CallbackType) )
						checks add "checkPointer(${it.name});"
					""
				}

			if ( mode == GenerationMode.NORMAL && it.paramType == ParameterType.IN && it.nativeType is CharSequenceType ) {
				val charSeqType = it.nativeType as CharSequenceType
				if ( charSeqType.nullTerminated )
					checks add "${prefix}checkNT${charSeqType.charMapping.bytes}(${it.name});"
			}

			if ( it.paramType == ParameterType.IN && it has nullTerminated ) {
				if ( mode == GenerationMode.NORMAL ) {
					val ntBytes = when ( it.nativeType.mapping ) {
						PointerMapping.DATA_SHORT -> 2
						PointerMapping.DATA_INT -> 4
						PointerMapping.DATA_LONG -> 8
						PointerMapping.DATA_FLOAT -> 4
						PointerMapping.DATA_DOUBLE -> 8
						else -> 1
					}
					checks add "${prefix}checkNT$ntBytes(${it.name});"
				} else
					checks add "${prefix}checkNT(${it.name});"
			}

			if ( it.nativeType is StructType ) {
				val structType = it.nativeType as StructType
				checks add "${prefix}checkBuffer(${it.name}, ${structType.definition.className}.SIZEOF);"
			}

			if ( it has Check.CLASS ) {
				val transform = transforms?.get(it)
				if ( transform !is SkipCheckFunctionTransform ) {
					val check = it[Check.CLASS]

					if ( check.debug ) prefix = "if ( LWJGLUtil.DEBUG )\n\t\t\t\t$prefix"

					if ( check.bytes )
						checks add "${prefix}checkBuffer(${it.name}, ${bufferShift(check.expression, it.name, ">>", transform)});"
					else if ( mode == GenerationMode.NORMAL )
						checks add "${prefix}checkBuffer(${it.name}, ${bufferShift(check.expression, it.name, "<<", transform)});"
					else
						checks add "${prefix}checkBuffer(${it.name}, ${check.expression});"
				}
			}

			if ( mode == GenerationMode.NORMAL && it has BufferObject.CLASS ) {
				checks add "GLChecks.ensureBufferObject(${it[BufferObject.CLASS].binding}, false);"
			}

			if ( it has AutoSize.CLASS ) {
				val autoSize = it[AutoSize.CLASS]
				if ( mode == GenerationMode.NORMAL ) {
					var length = it.name
					if ( autoSize.expression != null )
						length += autoSize.expression
					if ( it.nativeType.mapping == PrimitiveMapping.LONG )
						length = "(int)$length"

					prefix = if ( parameters[autoSize.reference]!! has Nullable.CLASS ) "if ( ${autoSize.reference} != null ) " else ""
					checks add "${prefix}checkBuffer(${autoSize.reference}, ${bufferShift(length, autoSize.reference, "<<", null)});"
					for ( d in autoSize.dependent ) {
						prefix = if ( parameters[d]!! has Nullable.CLASS ) "if ( $d != null ) " else ""
						checks add "${prefix}checkBuffer($d, ${bufferShift(length, d, "<<", null)});"
					}
				} else {
					val expression = if ( transforms?.get(parameters[autoSize.reference])?.javaClass == javaClass<SingleValueTransform>() )
						"1"
					else
						"${autoSize.reference}.remaining()"

					for ( d in autoSize.dependent ) {
						val param = parameters[d]!!
						val transform = transforms?.get(param)
						if ( transform !is SkipCheckFunctionTransform ) {
							prefix = if ( param has Nullable.CLASS ) "if ( $d != null ) " else ""
							checks add "${prefix}checkBuffer($d, $expression);"
						}
					}
				}
			}
		}

		if ( customChecks != null )
			checks addAll customChecks

		if ( !checks.isEmpty() ) {
			print("\t\tif ( LWJGLUtil.CHECKS )")
			if ( checks.size == 1 )
				println()
			else
				println(" {")
			checks.forEach {
				print("\t\t\t")
				println(it)
			}
			if ( 1 < checks.size )
				println("\t\t}")
		}
	}

	/** This is where we start generating java code. */
	fun generateMethods(writer: PrintWriter) {
		val simpleFunction = isSimpleFunction

		writer.generateNativeMethod(simpleFunction)

		if ( !simpleFunction ) {
			// This the only special case where we don't generate a "normal" Java method. If we did,
			// we'd need to add a postfix to either this or the alternative method, since we're
			// changing the return type. It looks ugly and LWJGL didn't do it pre-3.0 either.
			if ( !(returns.nativeType is CharSequenceType) )
				writer.generateJavaMethod()

			writer.generateAlternativeMethods()
		}
	}

	// --[ JAVA METHODS ]--

	private fun PrintWriter.generateJavaDocLink(description: String, function: NativeClassFunction) {
		println("\t/** $description ${function.javaDocLink} */")
	}

	private fun PrintWriter.generateNativeMethod(nativeOnly: Boolean) {
		if ( nativeOnly )
			println(documentation)
		else
			generateJavaDocLink("JNI method for", this@NativeClassFunction)

		print("\tpublic static native ${returns.nativeMethodType} ")
		if ( !nativeOnly ) print('n')
		print(
			if ( name.indexOf('_') == -1 )
				name
			else
				name.replaceAll(JNI_UNDERSCORE_ESCAPE_PATTERN, "_1")
		)
		print("(")
		printList(parameters) {
			it.asNativeMethodParam(parameters)
		}
		if ( nativeClass.functionProvider != null ) {
			if ( !parameters.isEmpty() )
				print(", ")
			print("long $FUNCTION_ADDRESS")
		}
		println(");\n")
	}

	private fun PrintWriter.generateJavaMethod() {
		// Step 0: JavaDoc

		if ( nativeClass.className.startsWith("GL") )
			printOpenGLJavaDoc(documentation, stripPostfix(true), this@NativeClassFunction has deprecatedGL)
		else
			println(documentation)

		// Step 1: Method signature

		print("\tpublic static ${returns.javaMethodType} $strippedName(")
		printList(parameters) {
			if ( it has CallbackData.CLASS )
				null
			else if ( it.isBufferPointer )
				"ByteBuffer ${it.name}" // Convert multi-byte-per-element buffers to ByteBuffer
			else
				it.asJavaMethodParam
		}
		println(") {")

		// Step 2: Get function address

		if ( nativeClass.functionProvider != null )
			nativeClass.functionProvider.generateFunctionAddress(this, this@NativeClassFunction)

		// Step 3: Generate checks
		generateChecks(GenerationMode.NORMAL);

		// Step 4: Call the native method

		print("\t\t")
		if ( !returns.isVoid ) {
			if ( returns.isBufferPointer )
				print("long $RESULT = ")
			else
				print("return ")
		}
		generateNativeMethodCall()
		println(";")

		if ( !returns.isVoid && returns.isBufferPointer ) {
			print("\t\treturn memByteBuffer")
			if ( returns.nativeType is CharSequenceType && returns.nativeType.nullTerminated )
				print("NT${returns.nativeType.charMapping.bytes}")
			print("($RESULT")
			if ( returns has MapPointer.CLASS )
				print(", ${returns[MapPointer.CLASS].sizeExpression}")
			else
				throw IllegalStateException()
			println(");")
		}

		println("\t}\n")
	}

	private fun PrintWriter.generateNativeMethodCall() {
		print("n$name(")
		printList(parameters) {
			it.asNativeMethodCallParam(GenerationMode.NORMAL)
		}
		if ( nativeClass.functionProvider != null ) {
			if ( !parameters.isEmpty() )
				print(", ")
			print("$FUNCTION_ADDRESS")
		}
		print(")")
	}

	/** Alternative methods are generated by applying one or more transformations. */
	private fun PrintWriter.generateAlternativeMethods() {
		val transforms = HashMap<QualifiedType, FunctionTransform<out QualifiedType>>()
		val customChecks = ArrayList<String>()

		if ( returns.nativeType is CharSequenceType )
			transforms[returns] = StringReturnTransform
		else if ( returns has MapPointer.CLASS )
			transforms[returns] = MapPointerTransform

		getParams { it has BufferObject.CLASS } forEach {
			transforms[it] = BufferOffsetTransform
			customChecks add ("GLChecks.ensureBufferObject(${it[BufferObject.CLASS].binding}, true);")
			generateAlternativeMethod(strippedName, "Buffer object offset version of:", transforms, customChecks, "") // TODO: "" is there because of a Kotlin bug
			transforms.remove(it)
		}

		// Step 1: Apply basic transformations
		parameters.values() forEach {
			if ( it.paramType == ParameterType.IN && it.nativeType is CharSequenceType )
				transforms[it] = CharSequenceTransform
			else if ( it has AutoSize.CLASS ) {
				val autoSize = it[AutoSize.CLASS]
				val param = parameters[autoSize.reference]!!
				// Check if there's also an AutoType or MultiType on the referenced parameter. Skip if so.
				if ( !(param has AutoSize.CLASS || param has MultiType.CLASS) )
					transforms[it] = AutoSizeTransform(param)
			} else if ( it has Expression.CLASS ) {
				val expression = it[Expression.CLASS]
				transforms[it] = ExpressionTransform(expression.value, expression.keepParam)
			} else if ( it has optional )
				transforms[it] = ExpressionTransform("0L")
		}

		// Step 2: Check if we have any basic transformation to apply or if we have a multi-byte-per-element buffer parameter
		if ( !transforms.isEmpty() || parameters.values().any { it.isBufferPointer && (it.nativeType.mapping as PointerMapping).isMultiByte } )
			generateAlternativeMethod(stripPostfix(true), "Alternative version of:", transforms, customChecks)

		// Step 3: Generate more complex alternatives if necessary
		if ( returns has MapPointer.CLASS ) {
			transforms[returns] = MapPointerExplicitTransform
			generateAlternativeMethod(stripPostfix(true), "Explicit size alternative version of:", transforms, customChecks)
		}

		parameters.values() forEach {
			val param = it

			if ( it has Return.CLASS ) {
				val returnMod = it[Return.CLASS]

				if ( returnMod == returnValue ) {
					// Generate Return alternative

					// Transform void to the proper type
					transforms[returns] = BufferValueReturnTransform(PointerMapping.primitiveMap[it.nativeType.mapping]!!, param.name)

					// Transform the AutoSize parameter, if there is one
					getParams { it has AutoSize.CLASS && it.get(AutoSize.CLASS).hasReference(param.name) }.forEach {
						transforms[it] = BufferValueSizeTransform
					}

					// Transform the returnValue parameter
					transforms[it] = BufferValueParameterTransform

					generateAlternativeMethod(strippedName, "Single return value version of:", transforms, customChecks, "") // TODO: "" is there because of a Kotlin bug
				} else {
					// Generate String return alternative

					// Remove any transform from the maxLength parameter (e.g. AutoSize)
					transforms.remove(parameters[returnMod.maxLengthParam])

					// Hide length parameter and use APIBuffer
					transforms[parameters[returnMod.lengthParam]!!] = StringLengthTransform

					// Hide char pointer parameter and use APIBuffer
					transforms[it] = StringParamTransform

					// Transform void to String type
					transforms[returns] = StringParamReturnTransform(param.name, returnMod.lengthParam, (it.nativeType as CharSequenceType).charMapping.charset)

					generateAlternativeMethod(strippedName, "String return version of:", transforms, customChecks, "") // TODO: "" is there because of a Kotlin bug

					if ( returnMod.maxLengthExpression != null ) {
						// Transform maxLength parameter and generate an additional alternative
						transforms[parameters[returnMod.maxLengthParam]!!] = ExpressionLocalTransform(returnMod.maxLengthExpression)
						generateAlternativeMethod(strippedName, "String return (w/ implicit max length) version of:", transforms, customChecks, "") // TODO: "" is there because of a Kotlin bug
					}
				}
			} else if ( it has SingleValue.CLASS ) {
				// Generate SingleValue alternative

				// Transform the AutoSize parameter, if there is one
				getParams { it has AutoSize.CLASS && it.get(AutoSize.CLASS).hasReference(param.name) }.forEach {
					transforms[it] = BufferValueSizeTransform
				}
				transforms[it] = SingleValueTransform(PointerMapping.primitiveMap[param.nativeType.mapping]!!, param.name, param[SingleValue.CLASS].newName)
				generateAlternativeMethod(strippedName, "Single value version of:", transforms, customChecks, "") // TODO: "" is there because of a Kotlin bug
			} else if ( it has MultiType.CLASS ) {
				// Generate MultiType alternatives
				customChecks.clear()

				// Add the AutoSize transformation if we skipped it above
				getParams { it has AutoSize.CLASS } forEach {
					transforms[it] = AutoSizeTransform(parameters[it[AutoSize.CLASS].reference]!!)
				}

				val multiTypes = it[MultiType.CLASS]
				if ( it has BufferObject.CLASS )
					customChecks add ("GLChecks.ensureBufferObject(${it[BufferObject.CLASS].binding}, false);")

				for ( autoType in multiTypes.types ) {
					// Transform the AutoSize parameter, if there is one and it's expressed in bytes
					getParams {
						if ( it has AutoSize.CLASS ) {
							val autoSize = it.get(AutoSize.CLASS)
							autoSize.hasReference(param.name) && autoSize.toBytes
						} else
							false
					}.forEach {
						transforms[it] = AutoSizeTransform(param, autoType.byteShift!!)
					}

					transforms[it] = AutoTypeTargetTransform(autoType)
					generateAlternativeMethod(strippedName, "${autoType.javaMethodType.getSimpleName()} version of:", transforms, customChecks, "") // TODO: "" is there because of a Kotlin bug
				}
			} else if ( it has AutoType.CLASS ) {
				// Generate AutoType alternatives
				customChecks.clear()

				// Add the AutoSize transformation if we skipped it above
				getParams { it has AutoSize.CLASS } forEach {
					transforms[it] = AutoSizeTransform(parameters[it[AutoSize.CLASS].reference]!!)
				}

				val autoTypes = it[AutoType.CLASS]
				val bufferParam = parameters[autoTypes.reference]!!
				if ( bufferParam has BufferObject.CLASS )
					customChecks add ("GLChecks.ensureBufferObject(${bufferParam[BufferObject.CLASS].binding}, false);")

				val types = ArrayList<BufferType>(autoTypes.types.size)
				autoTypes.types.forEach { types add it }

				for ( autoType in autoTypes.types ) {
					val unsignedType = when ( autoType ) {
						BufferType.GL_BYTE -> BufferType.GL_UNSIGNED_BYTE
						BufferType.GL_SHORT -> BufferType.GL_UNSIGNED_SHORT
						BufferType.GL_INT -> BufferType.GL_UNSIGNED_INT
						BufferType.GL_LONG -> BufferType.GL_UNSIGNED_LONG

						else -> null
					}

					if ( unsignedType == null || !types.contains(unsignedType) )
						continue

					transforms[it] = AutoTypeParamWithSignTransform("GL11.${unsignedType.name()}", "GL11.${autoType.name()}")
					transforms[bufferParam] = AutoTypeTargetTransform(autoType.mapping)
					generateAlternativeMethod(strippedName, "${unsignedType.name()} / ${autoType.name()} version of:", transforms, customChecks, "") // TODO: "" is there because of a Kotlin bug

					types.remove(autoType)
					types.remove(unsignedType)
				}

				for ( autoType in types ) {
					transforms[it] = AutoTypeParamTransform("GL11.${autoType.name()}")
					transforms[bufferParam] = AutoTypeTargetTransform(autoType.mapping)
					generateAlternativeMethod(strippedName, "${autoType.name()} version of:", transforms, customChecks, "") // TODO: "" is there because of a Kotlin bug
				}
			} else if ( it has PointerArray.CLASS ) {
				val pointerArray = it[PointerArray.CLASS]

				val lengthsParam = parameters[pointerArray.lengthsParam]
				if ( lengthsParam != null )
					transforms[lengthsParam!!] = ExpressionTransform("0L") // TODO: !! -> Kotlin bug

				val countParam = parameters[pointerArray.countParam]!!

				transforms[countParam] = ExpressionTransform("1")
				transforms[it] = PointerArrayTransformSingle
				generateAlternativeMethod(strippedName, "Single ${it.name} version of:", transforms, customChecks, "") // TODO: "" is there because of a Kotlin bug

				transforms[countParam] = ExpressionTransform("${it.name}.length")
				transforms[it] = PointerArrayTransformMulti
				generateAlternativeMethod(strippedName, "Array version of:", transforms, customChecks, "") // TODO: "" is there because of a Kotlin bug
			}
		}
	}

	private fun PrintWriter.generateAlternativeMethod(
		name: String,
		description: String,
		transforms: Map<QualifiedType, FunctionTransform<out QualifiedType>>,
		customChecks: List<String>,
		postFix: String = ""
	) {
		// Step 0: JavaDoc

		if ( transforms[returns] == StringReturnTransform ) // Special-case, we skipped the normal method
			println(documentation)
		else
			generateJavaDocLink(description, this@NativeClassFunction)

		// Step 1: Method signature

		val retType = returns.transformDeclarationOrElse(transforms, returns.javaMethodType)

		print("\tpublic static $retType $name$postFix(")
		printList(parameters) {
			if ( it has CallbackData.CLASS )
				null
			else
				it.transformDeclarationOrElse(transforms, it.asJavaMethodParam)
		}
		if ( transforms[returns] == MapPointerTransform ) {
			if ( !parameters.isEmpty() )
				print(", ")
			print("ByteBuffer old_buffer")
		} else if ( transforms[returns] == MapPointerExplicitTransform ) {
			if ( !parameters.isEmpty() )
				print(", ")
			print("int size, ByteBuffer old_buffer")
		}
		println(") {")

		// Step 2: Get function address

		if ( nativeClass.functionProvider != null )
			nativeClass.functionProvider.generateFunctionAddress(this, this@NativeClassFunction)

		// Step 3.A: Generate checks

		generateChecks(GenerationMode.ALTERNATIVE, customChecks, transforms);

		// Step 3.B: Transform pre-processing.

		for ( (qualifiedType, transform) in transforms ) {
			if ( transform is PreFunctionTransform )
				transform.preprocess(qualifiedType, this)
		}

		// Step 3.C: Prepare APIBuffer parameters.

		var apiBufferSet = false
		for ( (qualifiedType, transform) in transforms ) {
			if ( transform is APIBufferFunctionTransform ) {
				if ( !apiBufferSet ) {
					println("\t\tAPIBuffer $API_BUFFER = apiBuffer();")
					apiBufferSet = true
				}
				transform.setupAPIBuffer(qualifiedType, this)
			}
		}

		// Step 4: Call the native method

		print("\t\t")
		if ( !returns.isVoid ) {
			if ( returns.isBufferPointer )
				print("long $RESULT = ")
			else
				print("return ")
		}
		generateAlternativeNativeMethodCall(transforms)
		println(";")

		if ( returns.isVoid ) {
			val result = returns.transformCallOrElse(transforms, "")
			if ( !result.isEmpty() )
				println(result)
		} else {
			if ( returns.isBufferPointer ) {
				print("\t\t")

				val builder = StringBuilder()
				builder append "memByteBuffer"
				if ( returns.nativeType is CharSequenceType && returns.nativeType.nullTerminated )
					builder append "NT${returns.nativeType.charMapping.bytes}"
				builder append "($RESULT)"

				val returnExpression = returns.transformCallOrElse(transforms, builder.toString())
				if ( returnExpression.indexOf('\n') == -1 )
					println("return $returnExpression;")
				else // Multiple statements, assumes the transformation includes the return statement.
					println(returnExpression)
			}
		}

		println("\t}\n")
	}

	private fun PrintWriter.generateAlternativeNativeMethodCall(transforms: Map<QualifiedType, FunctionTransform<out QualifiedType>>) {
		print("n$name(")
		printList(parameters) {
			it.transformCallOrElse(transforms, it.asNativeMethodCallParam(GenerationMode.ALTERNATIVE))
		}
		if ( nativeClass.functionProvider != null ) {
			if ( !parameters.isEmpty() )
				print(", ")
			print("$FUNCTION_ADDRESS")
		}
		print(")")
	}

	// --[ JNI FUNCTIONS ]--

	fun generateFunctionDefinition(writer: PrintWriter): Unit = writer.generateFunctionDefinitionImpl()
	private fun PrintWriter.generateFunctionDefinitionImpl() {
		print("typedef ${returns.toNativeType} (APIENTRY *${name}PROC) (")
		printList(parameters) {
			it.toNativeType
		}
		println(");")
	}

	fun generateFunction(writer: PrintWriter): Unit = writer.generateFunctionImpl()
	private fun PrintWriter.generateFunctionImpl() {
		// Step 0: Function signature

		print("JNIEXPORT ${returns.jniFunctionType} JNICALL Java_${nativeClass.nativeFileName}_")
		if ( !isSimpleFunction )
			print('n')
		print("$name(")
		print("JNIEnv *$JNIENV, jclass clazz")
		parameters.values().forEach {
			print(", ${it.asJNIFunctionParam}")
		}
		if ( nativeClass.functionProvider != null )
			print(", jlong $FUNCTION_ADDRESS")
		println(") {")

		// Step 1: Cast addresses to pointers

		parameters.values().iterator().filter { it.nativeType is PointerType }.forEach {
			val pointerType = it.toNativeType
			val ws = if ( pointerType[pointerType.size - 1] == '*' ) "" else " "
			println("\t$pointerType$ws${it.name} = ($pointerType)(intptr_t)${it.name}$POINTER_POSTFIX;")
		}

		// Step 2: Cast function address to pointer

		if ( nativeClass.functionProvider != null )
			println("\t${name}PROC $name = (${name}PROC)(intptr_t)$FUNCTION_ADDRESS;")

		// Step 3: Call native function

		print('\t')
		if ( !returns.isVoid ) {
			print("return (${returns.jniFunctionType})")
			if ( returns.nativeType is PointerType )
				print("(intptr_t)")
		}
		print("$name(")
		printList(parameters) {
			it.name
		}
		println(");")

		print("}")
	}

}

enum class GenerationMode {
	NORMAL
	ALTERNATIVE
}

// --- [ MODIFIERS ]---

public class DependsOn(reference: String): ReferenceModifier(reference) {
	class object {
		val CLASS = javaClass<DependsOn>()
	}

	override fun validate(ttype: TemplateElement) {
		if ( ttype !is NativeClassFunction )
			throw IllegalArgumentException("The DependsOn modifier can only be applied on functions.")
	}
}

// --- [ ALTERNATIVE FUNCTION TRANSFORMS ] ---

private trait FunctionTransform<T: QualifiedType> {
	fun transformDeclaration(param: T, original: String): String?
	fun transformCall(param: T, original: String): String
}

/** A function transform that must perform some pre-processing. */
private trait PreFunctionTransform {
	fun preprocess(qualifiedType: QualifiedType, writer: PrintWriter)
}

/** A function transform that makes use of the APIBuffer. */
private trait APIBufferFunctionTransform {
	fun setupAPIBuffer(qualifiedType: QualifiedType, writer: PrintWriter)
}

/** Marker trait to indicate that buffer checks should be skipped. */
private trait SkipCheckFunctionTransform

private fun <T: QualifiedType> T.transformDeclarationOrElse(transforms: Map<QualifiedType, FunctionTransform<out QualifiedType>>, original: String): String? {
	val transform = transforms[this]
	if ( transform == null )
		return original
	else
		return (transform as FunctionTransform<T>).transformDeclaration(this, original)
}

private fun <T: QualifiedType> T.transformCallOrElse(transforms: Map<QualifiedType, FunctionTransform<out QualifiedType>>, original: String): String {
	val transform = transforms[this]
	if ( transform == null )
		return original
	else
		return (transform as FunctionTransform<T>).transformCall(this, original)
}

private open class AutoSizeTransform(val bufferParam: Parameter): FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String): String? = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String): String {
		// Replace with expression
		return if ( bufferParam has nullable )
			"${bufferParam.name} == null ? 0 : ${bufferParam.name}.remaining()"
		else
			"${bufferParam.name}.remaining()"
	}
}

private fun AutoSizeTransform(bufferParam: Parameter, byteShift: String) =
	if ( byteShift == "0" ) AutoSizeTransform(bufferParam) else AutoSizeBytesTransform(bufferParam, byteShift)

private class AutoSizeBytesTransform(bufferParam: Parameter, val byteShift: String): AutoSizeTransform(bufferParam) {
	override fun transformCall(param: Parameter, original: String): String {
		// Replace with expression
		return if ( bufferParam has nullable )
			"(${bufferParam.name} == null ? 0 : ${bufferParam.name}.remaining()) << $byteShift"
		else
			"${bufferParam.name}.remaining() << $byteShift"
	}
}

private class AutoTypeParamTransform(val autoType: String): FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String): String? = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String): String = autoType // Replace with hard-coded type
}

private class AutoTypeParamWithSignTransform(val unsignedType: String, val signedType: String): FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String): String? = "boolean unsigned" // Replace with unsigned flag
	override fun transformCall(param: Parameter, original: String): String = "unsigned ? $unsignedType : $signedType" // Replace with unsigned check
}

private class AutoTypeTargetTransform(val autoType: PointerMapping): FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String): String? = "${autoType.javaMethodType.getSimpleName()} ${param.name}"
	override fun transformCall(param: Parameter, original: String): String = original
}

private val BufferOffsetTransform = object : FunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String): String? = "long ${param.name}Offset"
	override fun transformCall(param: Parameter, original: String): String = "${param.name}Offset"
}

private open class ExpressionTransform(val expression: String, val keepParam: Boolean = false): FunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String): String? = if ( keepParam ) original else null
	override fun transformCall(param: Parameter, original: String): String = expression
}

private class ExpressionLocalTransform(expression: String, keepParam: Boolean = false): ExpressionTransform(expression, keepParam), PreFunctionTransform, SkipCheckFunctionTransform {
	override fun transformCall(param: Parameter, original: String): String = original
	override fun preprocess(qualifiedType: QualifiedType, writer: PrintWriter): Unit = writer.println("\t\t${(qualifiedType as Parameter).asJavaMethodParam} = $expression;")
}

private val CharSequenceTransform = object : FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String): String? = "CharSequence ${param.name}"
	override fun transformCall(param: Parameter, original: String): String = "memAddress(memEncode${(param.nativeType as CharSequenceType).charMapping.charset}(${param.name}))"
}

private val StringReturnTransform = object : FunctionTransform<ReturnValue> {
	override fun transformDeclaration(param: ReturnValue, original: String): String? = "String"
	override fun transformCall(param: ReturnValue, original: String): String = "memDecode${(param.nativeType as CharSequenceType).charMapping.charset}($original)";
}

private class BufferValueReturnTransform(val bufferType: String, val paramName: String): FunctionTransform<ReturnValue>, APIBufferFunctionTransform {
	override fun transformDeclaration(param: ReturnValue, original: String): String? = if ( bufferType == "pointer" ) "long" else bufferType // Replace void with the buffer value type
	override fun transformCall(param: ReturnValue, original: String): String = "\t\treturn $API_BUFFER.${bufferType}Value($paramName);" // Replace with value from APIBuffer
	override fun setupAPIBuffer(qualifiedType: QualifiedType, writer: PrintWriter) : Unit = writer.println("\t\tint $paramName = $API_BUFFER.${bufferType}Param();")
}

private val BufferValueParameterTransform = object : FunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String): String? = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String): String = "$API_BUFFER.address() + ${param.name}" // Replace with APIBuffer address + offset
}

private val BufferValueSizeTransform = object : FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String): String? = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String): String = "1" // Replace with 1
}

private class SingleValueTransform(
	val primitiveType: String,
	val paramName: String,
	val newName: String
) : FunctionTransform<Parameter>, APIBufferFunctionTransform, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String): String? = "$primitiveType $newName" // Replace with primitive type + new name
	override fun transformCall(param: Parameter, original: String): String = "$API_BUFFER.address() + $paramName" // Replace with APIBuffer address + offset
	override fun setupAPIBuffer(qualifiedType: QualifiedType, writer: PrintWriter) {
		writer.println("\t\tint $paramName = $API_BUFFER.${primitiveType}Param();")
		writer.println("\t\t$API_BUFFER.${primitiveType}Value($paramName, $newName);")
	}
}

private val MapPointerTransform = object : FunctionTransform<ReturnValue> {
	override fun transformDeclaration(param: ReturnValue, original: String): String? = "ByteBuffer" // Return a ByteBuffer
	override fun transformCall(param: ReturnValue, original: String): String = """int size = ${param.get(MapPointer.CLASS).sizeExpression};
		return __result == memAddress0(old_buffer) && old_buffer.capacity() == size ? old_buffer : memByteBuffer(__result, size);"""
}

private val MapPointerExplicitTransform = object : FunctionTransform<ReturnValue> {
	override fun transformDeclaration(param: ReturnValue, original: String): String? = "ByteBuffer" // Return a ByteBuffer
	override fun transformCall(param: ReturnValue, original: String): String =
		"__result == memAddress0(old_buffer) && old_buffer.capacity() == size ? old_buffer : memByteBuffer(__result, size)"
}

private val StringLengthTransform = object : FunctionTransform<Parameter>, APIBufferFunctionTransform, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String): String? = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String): String = "$API_BUFFER.address() + ${param.name}" // Replace with APIBuffer address + offset
	override fun setupAPIBuffer(qualifiedType: QualifiedType, writer: PrintWriter): Unit = writer.println("\t\tint ${(qualifiedType as Parameter).name} = $API_BUFFER.intParam();")
}

private val StringParamTransform = object : FunctionTransform<Parameter>, APIBufferFunctionTransform, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String): String? = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String): String = "$API_BUFFER.address() + ${param.name}" // Replace with APIBuffer address + offset
	override fun setupAPIBuffer(qualifiedType: QualifiedType, writer: PrintWriter): Unit =
		writer.println("\t\tint ${(qualifiedType as Parameter).name} = $API_BUFFER.bufferParam(${qualifiedType[Return.CLASS].maxLengthParam});")
}

private class StringParamReturnTransform(
	val paramName: String,
    val lengthParam: String,
    val encoding: String
): FunctionTransform<ReturnValue> {
	override fun transformDeclaration(param: ReturnValue, original: String): String? = "String" // Replace void with String
	override fun transformCall(param: ReturnValue, original: String): String = "\t\treturn memDecode$encoding(memByteBuffer($API_BUFFER.address() + $paramName, $API_BUFFER.intValue($lengthParam)));" // Replace with String decode
}

private class PointerArrayTransform(val multi: Boolean): FunctionTransform<Parameter>, APIBufferFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String): String? = if ( multi ) "CharSequence[] ${param.name}" else "CharSequence ${param.name}" // Replace with CharSequence
	override fun transformCall(param: Parameter, original: String): String = "$API_BUFFER.address() + ${param.name}Address" // Replace with APIBuffer address + offset
	override fun setupAPIBuffer(qualifiedType: QualifiedType, writer: PrintWriter): Unit = writer.setupAPIBufferImpl(qualifiedType as Parameter)

	private fun PrintWriter.setupAPIBufferImpl(param: Parameter) {
		val elementType = param[PointerArray.CLASS].elementType

		if ( multi ) {
			println("\t\tint ${param.name}$POINTER_POSTFIX = $API_BUFFER.bufferParam(${param.name}.length << PointerBuffer.getPointerSizeShift());")

			// Create a local array that will hold the encoded CharSequences. We need this to avoid premature GC of the passed buffers.
			if ( elementType is CharSequenceType )
				println("\t\tByteBuffer[] ${param.name}Buffers = new ByteBuffer[${param.name}.length];")

			println("\t\tfor ( int i = 0; i < ${param.name}.length; i++ )")
			print("\t\t\t$API_BUFFER.pointerValue(${param.name}$POINTER_POSTFIX + (i << PointerBuffer.getPointerSizeShift()), memAddress(")
			if ( elementType is CharSequenceType )
				print("${param.name}Buffers[i] = memEncode${elementType.charMapping.charset}(") // Encode and store
			print("${param.name}[i]")
			if ( elementType is CharSequenceType )
				print(")")
			println("));")
		} else {
			println("\t\tint ${param.name}$POINTER_POSTFIX = $API_BUFFER.pointerParam();")

			// Store the encoded CharSequence buffer in a local var to avoid premature GC.
			if ( elementType is CharSequenceType )
				println("\t\tByteBuffer ${param.name}Buffer = memEncode${elementType.charMapping.charset}(${param.name});") // Encode and store

			print("\t\t$API_BUFFER.pointerValue(${param.name}$POINTER_POSTFIX, memAddress(${param.name}")
			if ( elementType is CharSequenceType )
				print("Buffer")
			println("));")
		}
	}
}
private val PointerArrayTransformSingle = PointerArrayTransform(false)
private val PointerArrayTransformMulti = PointerArrayTransform(true)