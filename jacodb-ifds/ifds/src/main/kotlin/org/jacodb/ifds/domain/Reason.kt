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

package org.jacodb.ifds.domain

sealed interface Reason<out Stmt, out Fact> {
    data object Initial : Reason<Nothing, Nothing>

    data class Sequent<Stmt, Fact>(
        val edge: Edge<Stmt, Fact>,
    ) : Reason<Stmt, Fact>

    data class CallToReturn<Stmt, Fact>(
        val edge: Edge<Stmt, Fact>
    ) : Reason<Stmt, Fact>

    data class CallToStart<Stmt, Fact>(
        val edge: Edge<Stmt, Fact>,
    ) : Reason<Stmt, Fact>

    data class ExitToReturnSite<Stmt, Fact>(
        val callerEdge: Edge<Stmt, Fact>,
        val edge: Edge<Stmt, Fact>,
    ) : Reason<Stmt, Fact>

    data class FromOtherRunner<Stmt, Fact>(
        val edge: Edge<Stmt, Fact>,
        val otherRunnerId: RunnerId,
    ) : Reason<Stmt, Fact>
}
