package compat.corpus.kotlin

import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

class ReflectionEntry {
    companion object {
        @JvmStatic
        fun run(): String {
            val type = Class.forName("compat.corpus.kotlin.ReflectiveTarget")
            val target = type.getDeclaredConstructor().newInstance()
            return type.getDeclaredMethod("message").invoke(target) as String
        }
    }
}

class ReflectiveTarget {
    fun message(): String = "kotlin-reflection-ok"
}

class DynamicEntry {
    companion object {
        @JvmStatic
        fun run(simpleName: String): String {
            val type = Class.forName("compat.corpus.kotlin.$simpleName")
            val target = type.getDeclaredConstructor().newInstance()
            return type.getDeclaredMethod("value").invoke(target) as String
        }
    }
}

class DynamicTarget {
    fun value(): String = "kotlin-dynamic-ok"
}

class NativeBridge {
    external fun nativeRoundTrip(value: Long): Long

    companion object {
        @JvmStatic
        external fun nativeStatic(value: String): String
    }
}

class SerializableState(
    var label: String,
    var count: Int,
) : Serializable {
    private fun writeObject(output: ObjectOutputStream) {
        output.defaultWriteObject()
    }

    private fun readObject(input: ObjectInputStream) {
        input.defaultReadObject()
    }

    private fun readResolve(): Any = this

    companion object {
        private const val serialVersionUID: Long = 0x41554A3652384C
    }
}

class ScriptApi {
    fun greet(name: String): String = "hello,$name"

    fun echo(value: Any?): Any? = value

    companion object {
        @JvmStatic
        fun apiVersion(): Int = 1
    }
}

class RemovedDecoy {
    fun marker(): String = "remove-kotlin-decoy"
}
