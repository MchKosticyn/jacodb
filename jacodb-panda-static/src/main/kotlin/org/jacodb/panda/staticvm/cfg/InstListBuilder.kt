/*
 *  Copyright 2022 UnitTestBot contributors (utbot.org)
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.jacodb.panda.staticvm.cfg

import org.jacodb.panda.staticvm.classpath.*
import org.jacodb.panda.staticvm.ir.*
import org.jacodb.panda.staticvm.ir.PandaBasicBlockIr
import org.jacodb.panda.staticvm.ir.PandaInstIr
import org.jacodb.panda.staticvm.utils.OneDirectionGraph
import org.jacodb.panda.staticvm.utils.SCCs
import org.jacodb.panda.staticvm.utils.inTopsortOrder
import org.jacodb.panda.staticvm.utils.runDP
import java.lang.IllegalArgumentException
import java.nio.ByteBuffer
import java.nio.ByteOrder


sealed interface InstBuilder {
    val build: InstListBuilder.() -> PandaInst
}

class DefaultInstBuilder<T : PandaInst>(private val inst: T) : InstBuilder {
    override val build: InstListBuilder.() -> PandaInst
        get() = { inst }
}

class BranchingInstBuilder<T : PandaBranchingInst>(override val build: InstListBuilder.() -> T) : InstBuilder

private fun buildLocalVariables(
    methodNode: MethodNode,
    blocks: List<PandaBasicBlockIr>
) : Map<String, PandaLocalVar> {
    val classpath = methodNode.enclosingClass.classpath

    val localVarsIndex = hashMapOf<String, PandaLocalVar>()

    val outputVarBuilder = OutputVarBuilder(methodNode)

    val varNodes = blocks.flatMap { block ->
        block.insts.mapNotNull { it.accept(outputVarBuilder) }
    }.associateBy { it.name }

    val graph = OneDirectionGraph(varNodes.values) { node -> when (node) {
        is LeafVarNode -> emptySet()
        is DependentVarNode -> node.bounds.map(varNodes::get).also {
            if (it.contains(null))
                require(false)
        }.requireNoNulls().toSet()
        is LoadArrayNode -> setOf(requireNotNull(varNodes[node.array]))
    } }

    val sccs = graph.SCCs()
    require(sccs.inTopsortOrder() != null) {
        "wtf"
    }

    graph.SCCs().runDP { vars, inputTypes ->
        vars.map { lvar -> when (lvar) {
            is LeafVarNode -> lvar.type
            is DependentVarNode -> requireNotNull(classpath.commonType(inputTypes.values.flatten())) {
                "No common type for ${inputTypes.values}"
            }
            is LoadArrayNode -> {
                val arrayTypes = inputTypes.values.toList().flatten<TypeNode>()
                require(arrayTypes.all { it is ArrayNode })
                val elementTypes = arrayTypes.filterIsInstance<ArrayNode>()
                    .map(ArrayNode::elementType)
                requireNotNull(classpath.commonType(elementTypes))
            }
        }.also { localVarsIndex[lvar.name] = PandaLocalVar(lvar.name, it) } }
    }

    return localVarsIndex
}

data class IrInstLocation(val block: Int, val index: Int)



open class InstListBuilder(
    val method: MethodNode,
    val blocks: List<PandaBasicBlockIr>
) {
    val classpath = method.enclosingClass.classpath

    val localVars = buildLocalVariables(method, blocks)

    val locationMap = hashMapOf<IrInstLocation, Int>()

    private val blockIdMap = blocks.mapIndexed { index, block -> block.id to index }.toMap()

    fun indexOfBlock(id: Int) = requireNotNull(blockIdMap[id])

    fun linearRef(location: IrInstLocation) = PandaInstRef(requireNotNull(locationMap[location]) {
        "Not found location $location (method=${method.signature})"
    })

    fun local(name: String) = requireNotNull(localVars[name]) {
        "Not found local var $name (method=${method.signature})"
    }
    fun result(inst: PandaInstIr) = local(inst.id)

    val instBuildersList = mutableListOf<InstBuilder>()

    private fun push(value: InstListBuilder.(PandaInstLocation) -> InstBuilder) {
        instBuildersList.add(value(PandaInstLocation(method, instBuildersList.size)))
    }

    internal fun pushAssign(lhv: PandaValue, rhv: PandaExpr) = push { location ->
        DefaultInstBuilder(PandaAssignInst(location, lhv, rhv))
    }

    internal fun pushParameter(lhv: PandaValue, index: Int) = push { location ->
        DefaultInstBuilder(PandaParameterInst(location, lhv, index))
    }

    internal fun pushReturn(value: PandaValue?) = push { location ->
        DefaultInstBuilder(PandaReturnInst(location, value))
    }

    internal fun pushIf(conditionExpr: PandaConditionExpr, trueBranch: IrInstLocation, falseBranch: IrInstLocation) = push { location ->
        BranchingInstBuilder { PandaIfInst(location, conditionExpr, linearRef(trueBranch), linearRef(falseBranch)) }
    }

    internal fun pushGoto(target: IrInstLocation) = push { location ->
        BranchingInstBuilder { PandaGotoInst(location, linearRef(target)) }
    }

    private fun pushDoNothing(target: IrInstLocation) = push { location ->
        DefaultInstBuilder(PandaDoNothingInst(location))
    }

    init {
        val visitor = InstListBuilderVisitor()
        blocks.sortedBy { it.predecessors.size }.forEach { block ->
            block.insts.forEachIndexed { instIndex, inst ->
                visitor.location = IrInstLocation(block.id, instIndex)
                locationMap[visitor.location] = instBuildersList.size
                inst.accept(visitor)(this)
            }

            block.successors.singleOrNull()?.let {
                if (block.insts.lastOrNull() !is org.jacodb.panda.staticvm.ir.PandaTerminatingInstIr)
                    pushGoto(IrInstLocation(it, 0))
            }

            if (block.insts.isEmpty()) {
                locationMap[IrInstLocation(block.id, 0)] = instBuildersList.size
                pushDoNothing(IrInstLocation(block.id, 0))
            }
        }
    }

    val instList = instBuildersList.map { it.build(this) }

    fun build() = PandaInstList(instList)
}

class InstListBuilderVisitor() : PandaInstIrVisitor<InstListBuilder.() -> Unit> {
    lateinit var location: IrInstLocation

    private inline fun <reified T> convert(value: ULong, getter: ByteBuffer.() -> T) = ByteBuffer
        .allocate(16)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(value.toLong())
        .rewind()
        .let(getter)

    private fun getConstant(value: ULong, type: PandaPrimitiveTypeNode) = when (type) {
        PandaPrimitiveTypeNode.VOID -> throw IllegalArgumentException("cannot create void constant")
        PandaPrimitiveTypeNode.BOOL -> PandaBoolean(value != 0UL)
        PandaPrimitiveTypeNode.BYTE -> PandaByte(value.toByte())
        PandaPrimitiveTypeNode.UBYTE -> TODO()
        PandaPrimitiveTypeNode.SHORT -> PandaShort(value.toShort())
        PandaPrimitiveTypeNode.USHORT -> TODO()
        PandaPrimitiveTypeNode.INT -> PandaInt(value.toInt())
        PandaPrimitiveTypeNode.UINT -> TODO()
        PandaPrimitiveTypeNode.LONG -> PandaLong(value.toLong())
        PandaPrimitiveTypeNode.ULONG -> TODO()
        PandaPrimitiveTypeNode.FLOAT -> PandaFloat(convert(value, ByteBuffer::getFloat))
        PandaPrimitiveTypeNode.DOUBLE -> PandaDouble(convert(value, ByteBuffer::getDouble))
    }

    private fun getConditionType(operator: String) = when (operator) {
        "LE" -> ::PandaLeExpr
        "LT" -> ::PandaLtExpr
        "GE" -> ::PandaGeExpr
        "GT" -> ::PandaGtExpr
        "EQ" -> ::PandaEqExpr
        "NE" -> ::PandaNeExpr
        else -> throw AssertionError("Unknown operator: $operator")
    }

    private val skip: InstListBuilder.() -> Unit = {}

    private fun pushUnary(
        inst: PandaInstIr,
        exprConstructor: (TypeNode, PandaValue) -> PandaUnaryExpr
    ): InstListBuilder.() -> Unit = {
        val value = local(inst.inputs.first())
        pushAssign(result(inst), exprConstructor(classpath.findType(inst.type), value))
    }

    private fun pushBinary(
        inst: PandaInstIr,
        exprConstructor: (TypeNode, PandaValue, PandaValue) -> PandaBinaryExpr
    ): InstListBuilder.() -> Unit = {
        val (lhv, rhv) = inst.inputs.map(this::local)
        pushAssign(result(inst), exprConstructor(classpath.findType(inst.type), lhv, rhv))
    }

    override fun visitPandaConstantInstInfo(inst: PandaConstantInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), getConstant(inst.value, PandaPrimitives.find(inst.type)))
    }

    override fun visitPandaSafePointInstInfo(inst: PandaSafePointInstIr) = skip

    override fun visitPandaSaveStateInstInfo(inst: PandaSaveStateInstIr) = skip

    override fun visitPandaNewObjectInstInfo(inst: PandaNewObjectInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), PandaNewExpr(classpath.findClass(inst.objectClass)))
    }

    override fun visitPandaNewArrayInstInfo(inst: PandaNewArrayInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), PandaNewArrayExpr(classpath.getElementType(inst.arrayType), local(inst.inputs[1])))
    }

    override fun visitPandaCallStaticInstInfo(inst: PandaCallStaticInstIr): InstListBuilder.() -> Unit = {
        val callee = classpath.findMethod(inst.method)
        pushAssign(result(inst), PandaStaticCallExpr(
            callee,
            inst.inputs.take(callee.parameterTypes.size).map { local(it) }
        ))
    }

    override fun visitPandaNullCheckInstInfo(inst: PandaNullCheckInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), local(inst.inputs.first()))
    }

    override fun visitPandaZeroCheckInstInfo(inst: PandaZeroCheckInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), local(inst.inputs.first()))
    }

    override fun visitPandaLoadStringInstInfo(inst: PandaLoadStringInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), PandaString("", classpath.findClass("std.core.String")))
    }

    override fun visitPandaCallVirtualInstInfo(inst: PandaCallVirtualInstIr): InstListBuilder.() -> Unit = {
        val callee = classpath.findMethod(inst.method)
        val instance = local(inst.inputs.first())
        val args = inst.inputs.drop(1).take(callee.parameterTypes.size - 1).map(this::local)
        pushAssign(result(inst), PandaVirtualCallExpr(callee, instance, args))
    }

    override fun visitPandaLoadAndInitClassInstInfo(inst: PandaLoadAndInitClassInstIr) = skip

    override fun visitPandaLoadClassInstInfo(inst: PandaLoadClassInstIr) = skip

    override fun visitPandaInitClassInstInfo(inst: PandaInitClassInstIr) = skip

    override fun visitPandaReturnVoidInstInfo(inst: PandaReturnVoidInstIr): InstListBuilder.() -> Unit = {
        pushReturn(null)
    }

    override fun visitPandaReturnInstInfo(inst: PandaReturnInstIr): InstListBuilder.() -> Unit = {
        pushReturn(local(inst.inputs.first()))
    }

    override fun visitPandaParameterInstInfo(inst: PandaParameterInstIr): InstListBuilder.() -> Unit = {
        pushParameter(result(inst), inst.index)
    }

    override fun visitPandaLoadStaticInstInfo(inst: PandaLoadStaticInstIr): InstListBuilder.() -> Unit = {
        val enclosingClass = classpath.findClass(inst.enclosingClass)
        val field = enclosingClass.findField(inst.field)
        pushAssign(result(inst), PandaFieldRef(null, field, enclosingClass))
    }

    override fun visitPandaLoadObjectInstInfo(inst: PandaLoadObjectInstIr): InstListBuilder.() -> Unit = {
        val enclosingClass = classpath.findClass(inst.enclosingClass)
        val field = enclosingClass.findField(inst.field)
        pushAssign(result(inst), PandaFieldRef(local(inst.inputs.first()), field, enclosingClass))
    }

    override fun visitPandaStoreStaticInstInfo(inst: PandaStoreStaticInstIr): InstListBuilder.() -> Unit = {
        val enclosingClass = classpath.findClass(inst.enclosingClass)
        val field = enclosingClass.findField(inst.field)
        pushAssign(PandaFieldRef(null, field, enclosingClass), local(inst.inputs[1]))
    }

    override fun visitPandaStoreObjectInstInfo(inst: PandaStoreObjectInstIr): InstListBuilder.() -> Unit = {
        val enclosingClass = classpath.findClass(inst.enclosingClass)
        val field = enclosingClass.findField(inst.field)
        pushAssign(PandaFieldRef(local(inst.inputs[0]), field, enclosingClass), local(inst.inputs[1]))
    }

    override fun visitPandaLoadArrayInstInfo(inst: PandaLoadArrayInstIr): InstListBuilder.() -> Unit = {
        val (array, index) = inst.inputs.map(this::local)
        require(array.type is ArrayNode)
        pushAssign(result(inst), PandaArrayRef(array, index, array.type.elementType))
    }

    override fun visitPandaStoreArrayInstInfo(inst: PandaStoreArrayInstIr): InstListBuilder.() -> Unit = {
        val (array, index, value) = inst.inputs.map(this::local)
        require(array.type is ArrayNode)
        pushAssign(PandaArrayRef(array, index, array.type.elementType), value)
    }

    override fun visitPandaCastInstInfo(inst: PandaCastInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), PandaCastExpr(classpath.findType(inst.type), local(inst.inputs.first())))
    }

    override fun visitPandaIsInstanceInstInfo(inst: PandaIsInstanceInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), PandaIsInstanceExpr(
            classpath.findType(inst.type), 
            local(inst.inputs.first()),
            classpath.findClassOrInterface(inst.candidateType))
        )
    }

    override fun visitPandaCheckCastInstInfo(inst: PandaCheckCastInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), PandaCastExpr(
            classpath.findClassOrInterface(inst.candidateType),
            local(inst.inputs.first())
        )
        )
    }

    override fun visitPandaIfImmInstInfo(inst: PandaIfImmInstIr): InstListBuilder.() -> Unit = {
        val conditionExpr = getConditionType(inst.operator).invoke(
            classpath.findType(inst.type),
            local(inst.inputs.first()),
            getConstant(inst.immediate, PandaPrimitives.find(inst.operandsType))
        )
        val (trueBranch, falseBranch) = blocks.single { it.id == location.block }.successors
            .map { IrInstLocation(it, 0) }
        pushIf(conditionExpr, trueBranch, falseBranch)
    }

    override fun visitPandaCompareInstInfo(inst: PandaCompareInstIr): InstListBuilder.() -> Unit = {
        val conditionExpr = getConditionType(inst.operator).invoke(
            classpath.findType(inst.type),
            local(inst.inputs.component1()),
            local(inst.inputs.component2())
        )
        pushAssign(result(inst), conditionExpr)
    }

    override fun visitPandaPhiInstInfo(inst: PandaPhiInstIr): InstListBuilder.() -> Unit = {
        if (inst.users.isNotEmpty())
            pushAssign(result(inst), PandaPhiExpr(result(inst).type, inst.inputs.map(this::local)))
    }

    override fun visitPandaAddInstInfo(inst: PandaAddInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaAddExpr)

    override fun visitPandaSubInstInfo(inst: PandaSubInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaSubExpr)

    override fun visitPandaMulInstInfo(inst: PandaMulInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaMulExpr)

    override fun visitPandaDivInstInfo(inst: PandaDivInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaDivExpr)

    override fun visitPandaModInstInfo(inst: PandaModInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaModExpr)

    override fun visitPandaAndInstInfo(inst: PandaAndInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaAndExpr)

    override fun visitPandaOrInstInfo(inst: PandaOrInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaOrExpr)

    override fun visitPandaXorInstInfo(inst: PandaXorInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaXorExpr)

    override fun visitPandaShlInstInfo(inst: PandaShlInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaShlExpr)

    override fun visitPandaShrInstInfo(inst: PandaShrInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaShrExpr)

    override fun visitPandaAShlInstInfo(inst: org.jacodb.panda.staticvm.ir.PandaAShlInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaAshlExpr)

    override fun visitPandaAShrInstInfo(inst: org.jacodb.panda.staticvm.ir.PandaAShrInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaAshrExpr)

    override fun visitPandaCmpInstInfo(inst: PandaCmpInstIr): InstListBuilder.() -> Unit =
        pushBinary(inst, ::PandaCmpExpr)

    override fun visitPandaThrowInstInfo(inst: PandaThrowInstIr): InstListBuilder.() -> Unit = skip

    override fun visitPandaNegativeCheckInstInfo(inst: PandaNegativeCheckInstIr): InstListBuilder.() -> Unit = skip

    override fun visitPandaSaveStateDeoptimizeInstInfo(inst: org.jacodb.panda.staticvm.ir.PandaSaveStateDeoptimizeInstIr): InstListBuilder.() -> Unit = skip

    override fun visitPandaNegInstInfo(inst: PandaNegInstIr): InstListBuilder.() -> Unit =
        pushUnary(inst, ::PandaNegExpr)

    override fun visitPandaNotInstInfo(inst: PandaNotInstIr): InstListBuilder.() -> Unit =
        pushUnary(inst, ::PandaNotExpr)

    override fun visitPandaLenArrayInstInfo(inst: PandaLenArrayInstIr): InstListBuilder.() -> Unit =
        pushUnary(inst, ::PandaLenArrayExpr)

    override fun visitPandaBoundsCheckInstInfo(inst: PandaBoundsCheckInstIr): InstListBuilder.() -> Unit = skip

    override fun visitPandaNullPtrInstInfo(inst: PandaNullPtrInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), PandaNullPtr(classpath.findType("std.core.Object")))
    }

    override fun visitPandaLoadUndefinedInstInfo(inst: PandaLoadUndefinedInstIr): InstListBuilder.() -> Unit = {
        pushAssign(result(inst), PandaUndefined(classpath.findType("std.core.UndefinedType")))
    }

    override fun visitPandaRefTypeCheckInstInfo(inst: PandaRefTypeCheckInstIr): InstListBuilder.() -> Unit = skip

    override fun visitPandaTryInstInfo(inst: PandaTryInstIr): InstListBuilder.() -> Unit = skip

    override fun visitPandaCatchPhiInstInfo(inst: PandaCatchPhiInstIr): InstListBuilder.() -> Unit = {
        if (inst.users.isNotEmpty())
            pushAssign(result(inst), PandaPhiExpr(result(inst).type, inst.inputs.map(this::local)))
    }

}