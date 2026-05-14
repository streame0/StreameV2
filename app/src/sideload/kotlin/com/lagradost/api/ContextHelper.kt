package com.lagradost.api

import android.content.Context
import java.lang.ref.WeakReference

var ctx: WeakReference<Context>? = null

/**
 * Helper function for Android specific context. Not usable in JVM.
 * Do not use this unless absolutely necessary.
 */
fun getContext(): Any? {
    return ctx?.get()
}

fun setContext(context: WeakReference<Any>) {
    val ref = context.get()
    if (ref is Context) {
        ctx = WeakReference(ref)
    }
}