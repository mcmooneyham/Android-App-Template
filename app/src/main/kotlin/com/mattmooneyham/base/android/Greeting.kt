package com.mattmooneyham.base.android

import com.mattmooneyham.base.android.util.sayHello

class Greeting {
    private val platform = getPlatform()

    fun greet(): String {
        return sayHello(platform.name)
    }
}
