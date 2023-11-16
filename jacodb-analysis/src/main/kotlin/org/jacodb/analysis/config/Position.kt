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

package org.jacodb.analysis.config

import org.jacodb.analysis.paths.AccessPath
import org.jacodb.analysis.paths.ElementAccessor
import org.jacodb.analysis.paths.toPathOrNull
import org.jacodb.api.cfg.JcAssignInst
import org.jacodb.api.cfg.JcInst
import org.jacodb.api.cfg.JcInstanceCallExpr
import org.jacodb.api.cfg.JcValue
import org.jacodb.api.ext.cfg.callExpr
import org.jacodb.taint.configuration.AnyArgument
import org.jacodb.taint.configuration.Argument
import org.jacodb.taint.configuration.Position
import org.jacodb.taint.configuration.PositionResolver
import org.jacodb.taint.configuration.Result
import org.jacodb.taint.configuration.ResultAnyElement
import org.jacodb.taint.configuration.This

class CallPositionToAccessPathResolver(
    private val callStatement: JcInst,
) : PositionResolver<AccessPath> {
    private val callExpr = callStatement.callExpr
        ?: error("Call statement should have non-null callExpr")

    override fun resolve(position: Position): AccessPath = when (position) {
        AnyArgument -> error("Unexpected $position")

        is Argument -> callExpr.args[position.index].toPathOrNull()
            ?: error("Cannot resolve $position for $callStatement")

        This -> (callExpr as? JcInstanceCallExpr)?.instance?.toPathOrNull()
            ?: error("Cannot resolve $position for $callStatement")

        Result -> if (callStatement is JcAssignInst) {
            callStatement.lhv.toPathOrNull()
        } else {
            callExpr.toPathOrNull()
        } ?: error("Cannot resolve $position for $callStatement")

        ResultAnyElement -> {
            val path = if (callStatement is JcAssignInst) {
                callStatement.lhv.toPathOrNull()
            } else {
                callExpr.toPathOrNull()
            } ?: error("Cannot resolve $position for $callStatement")
            path / ElementAccessor(null)
        }
    }
}

class CallPositionToJcValueResolver(
    private val callStatement: JcInst,
) : PositionResolver<JcValue> {
    private val callExpr = callStatement.callExpr
        ?: error("Call statement should have non-null callExpr")

    override fun resolve(position: Position): JcValue = when (position) {
        AnyArgument -> error("Unexpected $position")

        is Argument -> callExpr.args[position.index]

        This -> (callExpr as? JcInstanceCallExpr)?.instance
            ?: error("Cannot resolve $position for $callStatement")

        Result -> if (callStatement is JcAssignInst) {
            callStatement.lhv
        } else {
            error("Cannot resolve $position for $callStatement")
        }

        ResultAnyElement -> error("Cannot resolve $position for $callStatement")
    }
}
