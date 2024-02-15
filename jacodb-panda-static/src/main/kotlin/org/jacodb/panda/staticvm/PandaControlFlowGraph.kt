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

package org.jacodb.panda.staticvm

import org.jacodb.api.core.cfg.ControlFlowGraph
import org.jacodb.api.core.cfg.Graph

class PandaControlFlowGraph private constructor(
    val method: PandaMethod,
    val instList: PandaInstList,
    private val graph: SimpleDirectedGraph<PandaInst>
) : ControlFlowGraph<PandaInst>, Graph<PandaInst> by graph {
    companion object {
        fun empty() = object : ControlFlowGraph<PandaInst> {
            override fun successors(node: PandaInst): Set<PandaInst> = emptySet()

            override fun predecessors(node: PandaInst): Set<PandaInst> = emptySet()

            override val entries: List<PandaInst>
                get() = emptyList()
            override val exits: List<PandaInst>
                get() = emptyList()
            override val instructions: List<PandaInst>
                get() = emptyList()

            override fun iterator(): Iterator<PandaInst> = emptyList<PandaInst>().iterator()
        }

        fun of(method: PandaMethod): PandaControlFlowGraph {
            val instList = PandaInstListBuilder(method).build()
            val graph = instList.flatMapIndexed { index, inst ->
                when (inst) {
                    is PandaBranchingInst -> inst.successors.map { instList[it.index] }
                    is PandaTerminatingInst -> emptyList()
                    else -> listOf(instList[index + 1])
                }.map { inst to it }
            }.applyFold(SimpleDirectedGraph<PandaInst>()) { (from, to) -> withEdge(from, to) }

            return PandaControlFlowGraph(method, instList, graph)
        }
    }

    override val entries: List<PandaInst> = listOfNotNull(instList.firstOrNull())

    override val exits: List<PandaInst> = setOfNotNull(instList.lastOrNull())
            .plus(instList.filterIsInstance<PandaTerminatingInst>())
            .toList()

    override val instructions: List<PandaInst> = instList.instructions

    override fun iterator(): Iterator<PandaInst> = instList.iterator()
}