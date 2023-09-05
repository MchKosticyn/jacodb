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

package org.jacodb.testing.ir


object DoubleComparison {

    @JvmStatic
    fun box(): String {
        if ((-0.0 as Comparable<Double>) >= 0.0) return "fail"
        return "OK"
    }
}


object WhenExpr {

    val x = 1

    @JvmStatic
    fun box() =
        when (val y = x) {
            in 0..2 -> "OK"
            else -> "Fail: $y"
        }

}

object InvokeMethodWithException {

    class A {
        fun lol(a: Int): Int {
            return 888/a
        }
    }

    @JvmStatic
    fun box():String {
        val method = A::class.java.getMethod("lol", Int::class.java)
        var failed = false
        try {
            method.invoke(null, 0)
        }
        catch(e: Exception) {
            failed = true
        }

        return if (!failed) "fail" else "OK"
    }

}

object DefaultArgs {
    class A(
        val c1: Boolean,
        val c2: Boolean,
        val c3: Boolean,
        val c4: String
    ) {
        override fun equals(o: Any?): Boolean {
            if (o !is A) return false;
            return c1 == o.c1 &&
                    c2 == o.c2 &&
                    c3 == o.c3 &&
                    c4 == o.c4
        }
    }

    fun reformat(
        str : String,
        normalizeCase : Boolean = true,
        uppercaseFirstLetter : Boolean = true,
        divideByCamelHumps : Boolean = true,
        wordSeparator : String = " "
    ) =
        A(normalizeCase, uppercaseFirstLetter, divideByCamelHumps, wordSeparator)


    @JvmStatic
    fun box() : String {
        val expected = A(true, true, true, " ")
        if(reformat("", true, true) != expected) return "fail"
        return "OK"
    }

}

fun main() {
    println(DefaultArgs.box())
}