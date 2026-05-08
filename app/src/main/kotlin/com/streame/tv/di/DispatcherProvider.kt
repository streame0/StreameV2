package com.streame.tv.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides coroutine dispatchers for dependency injection.
 * In production, delegates to standard Dispatchers.
 * In tests, can be replaced with TestDispatchers for deterministic testing.
 */
interface DispatcherProvider {
    val io: kotlinx.coroutines.CoroutineDispatcher
    val main: kotlinx.coroutines.CoroutineDispatcher
    val default: kotlinx.coroutines.CoroutineDispatcher
}

@Singleton
class AppDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val io = Dispatchers.IO
    override val main = Dispatchers.Main
    override val default = Dispatchers.Default
}
