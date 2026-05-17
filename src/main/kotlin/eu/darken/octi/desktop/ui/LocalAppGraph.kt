package eu.darken.octi.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import eu.darken.octi.desktop.di.AppGraph

/**
 * CompositionLocal exposing the [AppGraph] to every Compose tree node. Set once in
 * [eu.darken.octi.desktop.Main.main] via `CompositionLocalProvider`. Static because the graph
 * is constructed once and never replaced for the lifetime of the process — using
 * `compositionLocalOf` would force recomposition tracking we don't need.
 */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("AppGraph not provided — must be wrapped in CompositionLocalProvider(LocalAppGraph provides graph)")
}
