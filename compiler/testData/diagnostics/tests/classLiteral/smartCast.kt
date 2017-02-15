// KT-16291 Smart cast doesn't work when getting class of instance

import kotlin.reflect.KClass

class Foo {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other === null || other::class != this::class) return false

        return true
    }
}

private fun test(f: Foo?): KClass<out Foo>? = if (f != null) f::class else null
