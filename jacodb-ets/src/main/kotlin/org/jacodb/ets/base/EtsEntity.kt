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

package org.jacodb.ets.base

import org.jacodb.api.common.cfg.CommonExpr

interface EtsEntity : CommonExpr {
    val type: EtsType

    override val typeName: String
        get() = type.typeName

    interface Visitor<out R> :
        EtsValue.Visitor<R>,
        EtsExpr.Visitor<R> {

        fun visit(value: EtsRawEntity): R {
            if (this is Default) {
                return defaultVisit(value)
            }
            error("Cannot handle ${value::class.java.simpleName}: $value")
        }

        interface Default<out R> : Visitor<R>,
            EtsValue.Visitor.Default<R>,
            EtsExpr.Visitor.Default<R> {

            override fun defaultVisit(value: EtsValue): R = defaultVisit(value as EtsEntity)
            override fun defaultVisit(expr: EtsExpr): R = defaultVisit(expr as EtsEntity)

            fun defaultVisit(value: EtsEntity): R
        }
    }

    fun <R> accept(visitor: Visitor<R>): R
}

data class EtsRawEntity(
    val kind: String,
    val extra: Map<String, Any> = emptyMap(),
    override val type: EtsType,
) : EtsEntity {
    override fun toString(): String {
        return "$kind $extra: $type"
    }

    override fun <R> accept(visitor: EtsEntity.Visitor<R>): R {
        return visitor.visit(this)
    }
}
