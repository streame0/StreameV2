package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.*

suspend fun <K, V, R> Map<out K, V>.amap(f: suspend (Map.Entry<K, V>) -> R): List<R> =
    coroutineScope {
        map { async { f(it) } }.awaitAll()
    }

fun <K, V, R> Map<out K, V>.apmap(f: suspend (Map.Entry<K, V>) -> R): List<R> = runBlocking {
    map { async { f(it) } }.awaitAll()
}


suspend fun <A, B> List<A>.amap(f: suspend (A) -> B): List<B> =
    coroutineScope {
        map { async { f(it) } }.awaitAll()
    }


fun <A, B> List<A>.apmap(f: suspend (A) -> B): List<B> = runBlocking {
    map { async { f(it) } }.awaitAll()
}

fun <A, B> List<A>.apmapIndexed(f: suspend (index: Int, A) -> B): List<B> = runBlocking {
    mapIndexed { index, a -> async { f(index, a) } }.awaitAll()
}

suspend fun <A, B> List<A>.amapIndexed(f: suspend (index: Int, A) -> B): List<B> =
    coroutineScope {
        mapIndexed { index, a -> async { f(index, a) } }.awaitAll()
    }

// built in try catch
fun <R> argamap(
    vararg transforms: suspend () -> R,
) = runBlocking {
    transforms.map {
        async {
            try {
                it.invoke()
            } catch (e: Exception) {
                logError(e)
            }
        }
    }.awaitAll()
}
