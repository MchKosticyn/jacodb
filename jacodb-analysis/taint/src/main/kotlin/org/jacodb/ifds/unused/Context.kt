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

package org.jacodb.ifds.unused

import org.jacodb.api.JcClasspath
import org.jacodb.api.analysis.JcApplicationGraph
import org.jacodb.api.cfg.JcInst
import org.jacodb.ifds.ChunkStrategy
import org.jacodb.ifds.DefaultChunkResolver
import org.jacodb.ifds.common.ClassChunkStrategy
import org.jacodb.ifds.common.JcIfdsContext

fun unusedIfdsContext(
    cp: JcClasspath,
    graph: JcApplicationGraph,
    bannedPackagePrefixes: List<String>,
    chunkStrategy: ChunkStrategy<JcInst> = ClassChunkStrategy,
): JcIfdsContext<UnusedVariableDomainFact> =
    JcIfdsContext(
        cp,
        bannedPackagePrefixes,
        DefaultChunkResolver(chunkStrategy)
    ) { runnerId ->
        when (runnerId) {
            is SingletonRunnerId -> UnusedVariableAnalyzer(SingletonRunnerId, graph)
            else -> error("Unexpected runnerId: $runnerId")
        }
    }
