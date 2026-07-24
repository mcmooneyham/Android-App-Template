package com.mattmooneyham.base.android.navigation

import kotlinx.serialization.Serializable

// ============================ TEMPLATE =============================
// HOW TO USE: adding a pushed screen is a destination type plus three
// wiring points. Copy the type below into
// ui/src/main/kotlin/.../navigation/AppDestinations.kt (as a new
// member of the tab's existing sealed hierarchy, or a new hierarchy
// for a new tab), then follow the three commented diffs. Delete these
// banners.
// ===================================================================

/**
 * STEP 1: THE DESTINATION IS DATA. Serializable because the whole
 * navigation state round-trips through saved instance state; the
 * payload-evolution rules apply verbatim (new arguments get defaults,
 * a repurposed screen is a NEW type).
 */
@Serializable
sealed interface TemplateDestination {

    @Serializable
    data class ReadingDetail(val readingId: Int) : TemplateDestination
}

// STEP 2: RENDER IT. In AppShell.kt, the tab's TabStackHost gains
// one exhaustive branch (the compiler forces this everywhere the
// hierarchy is switched):
//
// ```
// ) { destination ->
//     when (destination) {
//         is HomeDestination.JokeDetail -> ...existing...
//         is HomeDestination.ReadingDetail -> ReadingDetailPage(
//             readingId = destination.readingId,
//             onBack = { appRouter.homeRouter.pop() },
//         )
//     }
// }
// ```
// and the page that OPENS it receives a semantic lambda wired at the
// shell: onOpenTemplateDetail = { id ->
//     appRouter.homeRouter.push(HomeDestination.ReadingDetail(id)) }.

// STEP 3: DEEP-LINK IT (when the screen needs a URL). One branch in
// AppRouter.handleDeepLink:
//
// ```
// READING_DEEP_LINK_HOST -> {
//     val readingId = parsedUri.path.orEmpty()
//         .removePrefix("/").toIntOrNull() ?: return false
//     selectedTab = AppTab.HOME
//     homeRouter.replaceAll(
//         HomeDestination.ReadingDetail(readingId = readingId),
//     )
//     true
// }
// ```
// plus a <data android:host="reading"/> line in the manifest's VIEW
// intent filter. Replace, never push: a deep link states the WHOLE
// intended stack.

// STEP 4: TEST IT. AppRouterSpec gains two JVM tests: the deep link
// resolves to the replaced stack, and back pops the pushed detail
// (see the existing spec's shapes; no instrumentation needed for
// router semantics).
