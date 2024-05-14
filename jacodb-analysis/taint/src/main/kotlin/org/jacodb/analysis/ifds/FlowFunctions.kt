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

package org.jacodb.analysis.ifds

import org.jacodb.api.JcMethod
import org.jacodb.api.cfg.JcInst

interface FlowFunctions<Fact> {
    /**
     * Method for obtaining initial domain facts at the method entrypoint.
     * Commonly, it is only `listOf(Zero)`.
     */
    fun obtainPossibleStartFacts(method: JcMethod): Collection<Fact>

    /**
     * Sequent flow function.
     *
     * ```
     *   [ DO() ] :: current
     *     |
     *     | (sequent edge)
     *     |
     *   [ DO() ]
     * ```
     */
    fun sequent(
        current: JcInst,
        next: JcInst,
        fact: Fact,
    ): Collection<Fact>

    /**
     * Call-to-return-site flow function.
     *
     * ```
     * [ CALL p ] :: callStatement
     *   :
     *   : (call-to-return-site edge)
     *   :
     * [ RETURN FROM p ] :: returnSite
     * ```
     */
    fun callToReturn(
        callStatement: JcInst,
        returnSite: JcInst,
        fact: Fact
    ): Collection<Fact>

    /**
     * Call-to-start flow function.
     *
     * ```
     * [ CALL p ] :: callStatement
     *   : \
     *   :  \ (call-to-start edge)
     *   :   \
     *   :  [ START p ]
     *   :    |
     *   :  [ EXIT p ]
     *   :   /
     *   :  /
     * [ RETURN FROM p ]
     * ```
     */
    fun callToStart(
        callStatement: JcInst,
        calleeStart: JcInst,
        fact: Fact,
    ): Collection<Fact>

    /**
     * Exit-to-return-site flow function.
     *
     * ```
     * [ CALL p ] :: callStatement
     *   :  \
     *   :   \
     *   :  [ START p ]
     *   :    |
     *   :  [ EXIT p ] :: exitStatement
     *   :   /
     *   :  / (exit-to-return-site edge)
     *   : /
     * [ RETURN FROM p ] :: returnSite
     * ```
     */
    fun exitToReturnSite(
        callStatement: JcInst,
        returnSite: JcInst,
        exitStatement: JcInst,
        fact: Fact,
    ): Collection<Fact>
}
