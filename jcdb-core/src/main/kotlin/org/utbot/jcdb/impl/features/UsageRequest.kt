package org.utbot.jcdb.impl.features

import kotlinx.serialization.Serializable
import org.utbot.jcdb.api.ClassSource

@Serializable
data class UsageFeatureRequest(
    val methodName: String?,
    val methodDesc: String?,
    val field: String?,
    val opcodes: Collection<Int>,
    val className: String
) : java.io.Serializable

class UsageFeatureResponse(
    val source: ClassSource,
    val offsets: List<Int>
)

