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

package org.jacodb.analysis
import kotlinx.serialization.Serializable
import mu.KLogging
import org.jacodb.analysis.analyzers.AliasAnalyzerFactory
import org.jacodb.analysis.analyzers.NpeAnalyzerFactory
import org.jacodb.analysis.analyzers.NpePrecalcBackwardAnalyzerFactory
import org.jacodb.analysis.analyzers.SqlInjectionAnalyzerFactory
import org.jacodb.analysis.analyzers.SqlInjectionBackwardAnalyzerFactory
import org.jacodb.analysis.analyzers.TaintAnalysisNode
import org.jacodb.analysis.analyzers.TaintAnalyzerFactory
import org.jacodb.analysis.analyzers.TaintBackwardAnalyzerFactory
import org.jacodb.analysis.analyzers.TaintNode
import org.jacodb.analysis.analyzers.UnusedVariableAnalyzerFactory
import org.jacodb.analysis.engine.IfdsBaseUnitRunner
import org.jacodb.analysis.engine.SequentialBidiIfdsUnitRunner
import org.jacodb.analysis.engine.TraceGraph
import org.jacodb.api.JcMethod
import org.jacodb.api.cfg.JcExpr
import org.jacodb.api.cfg.JcInst

@Serializable
data class DumpableVulnerabilityInstance(
    val vulnerabilityType: String,
    val sources: List<String>,
    val sink: String,
    val traces: List<List<String>>
)

@Serializable
data class DumpableAnalysisResult(val foundVulnerabilities: List<DumpableVulnerabilityInstance>)

data class VulnerabilityInstance(
    val vulnerabilityType: String,
    val traceGraph: TraceGraph
) {
    private fun JcInst.prettyPrint(): String {
        return "${toString()} (${location.method}:${location.lineNumber})"
    }

    fun toDumpable(maxPathsCount: Int): DumpableVulnerabilityInstance {
        return DumpableVulnerabilityInstance(
            vulnerabilityType,
            traceGraph.sources.map { it.statement.prettyPrint() },
            traceGraph.sink.statement.prettyPrint(),
            traceGraph.getAllTraces().take(maxPathsCount).map { intermediatePoints ->
                intermediatePoints.map { it.statement.prettyPrint() }
            }.toList()
        )
    }
}

fun List<VulnerabilityInstance>.toDumpable(maxPathsCount: Int = 3): DumpableAnalysisResult {
    return DumpableAnalysisResult(map { it.toDumpable(maxPathsCount) })
}

typealias AnalysesOptions = Map<String, String>

@Serializable
data class AnalysisConfig(val analyses: Map<String, AnalysesOptions>)

val UnusedVariableRunner = IfdsBaseUnitRunner(UnusedVariableAnalyzerFactory)

fun newSqlInjectionRunner(maxPathLength: Int = 5) = SequentialBidiIfdsUnitRunner(
    IfdsBaseUnitRunner(SqlInjectionAnalyzerFactory(maxPathLength)),
    IfdsBaseUnitRunner(SqlInjectionBackwardAnalyzerFactory(maxPathLength)),
)

fun newNpeRunner(maxPathLength: Int = 5) = SequentialBidiIfdsUnitRunner(
    IfdsBaseUnitRunner(NpeAnalyzerFactory(maxPathLength)),
    IfdsBaseUnitRunner(NpePrecalcBackwardAnalyzerFactory(maxPathLength)),
)

fun newAliasRunner(
    generates: (JcInst) -> List<TaintAnalysisNode>,
    sanitizes: (JcExpr, TaintNode) -> Boolean,
    sinks: (JcInst) -> List<TaintAnalysisNode>,
    maxPathLength: Int = 5
) = IfdsBaseUnitRunner(AliasAnalyzerFactory(generates, sanitizes, sinks, maxPathLength))

fun newTaintRunner(
    isSourceMethod: (JcMethod) -> Boolean,
    isSanitizeMethod: (JcMethod) -> Boolean,
    isSinkMethod: (JcMethod) -> Boolean,
    maxPathLength: Int = 5
) = SequentialBidiIfdsUnitRunner(
    IfdsBaseUnitRunner(TaintAnalyzerFactory(isSourceMethod, isSanitizeMethod, isSinkMethod, maxPathLength)),
    IfdsBaseUnitRunner(TaintBackwardAnalyzerFactory(isSourceMethod, isSinkMethod, maxPathLength))
)

fun newTaintRunner(
    sourceMethodMatchers: List<String>,
    sanitizeMethodMatchers: List<String>,
    sinkMethodMatchers: List<String>,
    maxPathLength: Int = 5
) = SequentialBidiIfdsUnitRunner(
    IfdsBaseUnitRunner(TaintAnalyzerFactory(sourceMethodMatchers, sanitizeMethodMatchers, sinkMethodMatchers, maxPathLength)),
    IfdsBaseUnitRunner(TaintBackwardAnalyzerFactory(sourceMethodMatchers, sinkMethodMatchers, maxPathLength))
)

internal val logger = object : KLogging() {}.logger