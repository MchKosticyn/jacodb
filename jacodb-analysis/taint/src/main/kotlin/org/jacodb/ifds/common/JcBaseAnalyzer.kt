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

package org.jacodb.ifds.common

import org.jacodb.api.JcMethod
import org.jacodb.api.analysis.JcApplicationGraph
import org.jacodb.api.cfg.JcInst
import org.jacodb.api.ext.cfg.callExpr
import org.jacodb.ifds.domain.Analyzer
import org.jacodb.ifds.domain.Edge
import org.jacodb.ifds.domain.Reason
import org.jacodb.ifds.domain.RunnerId
import org.jacodb.ifds.domain.Vertex
import org.jacodb.ifds.messages.AnalyzerMessage
import org.jacodb.ifds.messages.EdgeMessage
import org.jacodb.ifds.messages.NewEdge
import org.jacodb.ifds.messages.NotificationOnStart
import org.jacodb.ifds.messages.ResolvedCall
import org.jacodb.ifds.messages.RunnerMessage
import org.jacodb.ifds.messages.SubscriptionOnStart
import org.jacodb.ifds.messages.UnresolvedCall

abstract class JcBaseAnalyzer<Fact>(
    protected val selfRunnerId: RunnerId,
    protected val graph: JcApplicationGraph,
) : Analyzer<JcInst, Fact> {
    abstract val flowFunctions: FlowFunctions<JcInst, Fact>

    override fun handle(message: AnalyzerMessage<JcInst, Fact>): Collection<RunnerMessage> = buildList {
        when (message) {
            is EdgeMessage<JcInst, Fact> -> {
                processEdge(message.edge)
            }

            is ResolvedCall<JcInst, Fact, *> -> {
                @Suppress("UNCHECKED_CAST")
                processResolvedCall(message as ResolvedCall<JcInst, Fact, JcMethod>)
            }

            is NotificationOnStart<JcInst, Fact> -> {
                processNotificationOnStart(message)
            }

            else -> {
                error("Unexpected message: $message")
            }
        }
    }

    private fun MutableList<RunnerMessage>.processEdge(edge: Edge<JcInst, Fact>) {
        val toStmt = edge.to.statement
        val method = graph.methodOf(toStmt)

        val callExpr = toStmt.callExpr
        val isExit = toStmt in graph.exitPoints(method)

        when {
            callExpr != null -> processCall(edge)
            !isExit -> processSequent(edge)
        }
    }

    private fun MutableList<RunnerMessage>.processCall(edge: Edge<JcInst, Fact>) {
        val callMessage = UnresolvedCall(selfRunnerId, edge)
        add(callMessage)

        val reason = Reason.CallToReturn(edge)

        val successors = graph.successors(edge.to.statement)

        for (successor in successors) {
            val facts = flowFunctions.callToReturn(
                callStatement = edge.to.statement,
                returnSite = successor,
                edge.to.fact
            )
            for (fact in facts) {
                val newEdge = Edge(edge.from, Vertex(successor, fact))
                processNewEdge(selfRunnerId, newEdge, reason)
            }
        }
    }

    private fun MutableList<RunnerMessage>.processSequent(edge: Edge<JcInst, Fact>) {
        val reason = Reason.Sequent(edge)

        val successors = graph.successors(edge.to.statement)

        for (successor in successors) {
            val facts = flowFunctions.sequent(current = edge.to.statement, next = successor, edge.to.fact)
            for (fact in facts) {
                val newEdge = Edge(edge.from, Vertex(successor, fact))
                processNewEdge(selfRunnerId, newEdge, reason)
            }
        }
    }


    private fun MutableList<RunnerMessage>.processResolvedCall(
        resolvedCall: ResolvedCall<JcInst, Fact, JcMethod>,
    ) {
        val edge = resolvedCall.edge
        val reason = Reason.CallToStart(edge)

        val entryPoints = graph.entryPoints(resolvedCall.method)

        for (entryPoint in entryPoints) {
            val facts = flowFunctions.callToStart(
                callStatement = edge.to.statement,
                calleeStart = entryPoint,
                edge.to.fact
            )
            for (fact in facts) {
                val vertex = Vertex(entryPoint, fact)

                val subscription = SubscriptionOnStart(selfRunnerId, vertex, selfRunnerId, edge)
                add(subscription)

                val newEdge = Edge(vertex, vertex)
                processNewEdge(selfRunnerId, newEdge, reason)
            }
        }
    }


    private fun MutableList<RunnerMessage>.processNotificationOnStart(
        message: NotificationOnStart<JcInst, Fact>
    ) {
        val callerEdge = message.subscribingEdge
        val returnSites = graph.successors(callerEdge.to.statement)

        val edge = message.summaryEdge
        val reason = Reason.ExitToReturnSite(callerEdge, edge)

        for (returnSite in returnSites) {
            val facts = flowFunctions.exitToReturnSite(
                callStatement = callerEdge.to.statement,
                returnSite = returnSite,
                exitStatement = edge.to.statement,
                edge.to.fact
            )
            for (fact in facts) {
                val newEdge = Edge(callerEdge.from, Vertex(returnSite, fact))
                processNewEdge(selfRunnerId, newEdge, reason)
            }
        }
    }

    private fun MutableList<RunnerMessage>.processNewEdge(
        runnerId: RunnerId,
        newEdge: Edge<JcInst, Fact>,
        reason: Reason<JcInst, Fact>,
    ) {
        val newEdgeMessage = NewEdge(runnerId, newEdge, reason)
        add(newEdgeMessage)

        onNewEdge(newEdge)
    }

    protected abstract fun MutableList<RunnerMessage>.onNewEdge(newEdge: Edge<JcInst, Fact>)

    /**
     * Method for obtaining initial domain facts at the method entrypoint.
     * Commonly, it is only `listOf(Zero)`.
     */
    abstract fun obtainPossibleStartFacts(method: JcMethod): Collection<Fact>
}
