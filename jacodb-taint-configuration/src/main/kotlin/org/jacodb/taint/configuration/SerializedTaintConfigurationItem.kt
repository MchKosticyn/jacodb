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

package org.jacodb.taint.configuration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed interface SerializedTaintConfigurationItem {
    @SerialName("methodInfo")
    val methodInfo: FunctionMatcher

    fun updateMethodInfo(updatedMethodInfo: FunctionMatcher): SerializedTaintConfigurationItem =
        when (this) {
            is SerializedTaintCleaner -> copy(methodInfo = updatedMethodInfo)
            is SerializedTaintEntryPointSource -> copy(methodInfo = updatedMethodInfo)
            is SerializedTaintMethodSink -> copy(methodInfo = updatedMethodInfo)
            is SerializedTaintMethodSource -> copy(methodInfo = updatedMethodInfo)
            is SerializedTaintPassThrough -> copy(methodInfo = updatedMethodInfo)
        }
}

@Serializable
@SerialName("EntryPointSource")
data class SerializedTaintEntryPointSource(
    @SerialName("methodInfo") override val methodInfo: FunctionMatcher,
    @SerialName("condition") val condition: Condition,
    @SerialName("actionsAfter") val actionsAfter: List<Action>,
) : SerializedTaintConfigurationItem

@Serializable
@SerialName("MethodSource")
data class SerializedTaintMethodSource(
    @SerialName("methodInfo") override val methodInfo: FunctionMatcher,
    @SerialName("condition") val condition: Condition,
    @SerialName("actionsAfter") val actionsAfter: List<Action>,
) : SerializedTaintConfigurationItem

@Serializable
@SerialName("MethodSink")
data class SerializedTaintMethodSink(
    @SerialName("ruleNote") val ruleNote: String,
    @SerialName("cwe") val cwe: List<Int>,
    @SerialName("methodInfo") override val methodInfo: FunctionMatcher,
    @SerialName("condition") val condition: Condition,
) : SerializedTaintConfigurationItem

@Serializable
@SerialName("PassThrough")
data class SerializedTaintPassThrough(
    @SerialName("methodInfo") override val methodInfo: FunctionMatcher,
    @SerialName("condition") val condition: Condition,
    @SerialName("actionsAfter") val actionsAfter: List<Action>,
) : SerializedTaintConfigurationItem

@Serializable
@SerialName("Cleaner")
data class SerializedTaintCleaner(
    @SerialName("methodInfo") override val methodInfo: FunctionMatcher,
    @SerialName("condition") val condition: Condition,
    @SerialName("actionsAfter") val actionsAfter: List<Action>,
) : SerializedTaintConfigurationItem

fun main() {
    val methodSource = SerializedTaintMethodSource(
        methodInfo = FunctionMatcher(
            cls = ClassMatcher(
                pkg = NameExactMatcher("java.util"),
                classNameMatcher = NameExactMatcher("Scanner")
            ),
            functionName = NamePatternMatcher("findInLine|findWithinHorizon|nextLine|useDelimiter|useLocale|useRadix|skip|reset"),
            parametersMatchers = emptyList(),
            returnTypeMatcher = AnyTypeMatcher,
            applyToOverrides = true,
            functionLabel = null,
            modifier = -1,
            exclude = emptyList()
        ),
        condition = ConstantTrue,
        actionsAfter = listOf(
            CopyAllMarks(from = This, to = Result)
        )
    )
    val json = Json {
        prettyPrint = true
    }
    val methodSourceJson = json.encodeToString(methodSource)
    println("methodSource.toJson() = $methodSourceJson")
}
