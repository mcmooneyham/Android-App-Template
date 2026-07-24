package com.mattmooneyham.base.android.views

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mattmooneyham.base.android.ui.R
import com.mattmooneyham.base.android.animations.AppAnimations
import com.mattmooneyham.base.android.navigation.AppRouter
import com.mattmooneyham.base.android.navigation.AppTab
import com.mattmooneyham.base.android.navigation.HomeDestination
import com.mattmooneyham.base.android.navigation.rememberAppRouter
import com.mattmooneyham.base.android.viewModels.MainViewModel
import com.mattmooneyham.base.android.viewModels.SettingsViewModel
import com.mattmooneyham.base.android.constants.BrandColors

data class NavigationPage(
    @StringRes val nameResId: Int,
    val icon: ImageVector,
)

// iOS-style tab bar metrics: a 56x30 selection pill, an 18dp icon, an
// 11sp medium label, and 8dp top / 4dp bottom padding above the system
// inset.
private val TabPillWidth = 56.dp
private val TabPillHeight = 30.dp
private val TabIconSize = 18.dp
private val TabLabelSize = 11.sp
private val TabLabelSpacing = 4.dp

// iOS UIColor.separator equivalents for the hairline (the theme's
// outlineVariant is nearly invisible against the light bar).
private val TabBarDividerLight = Color(0x4A3C3C43)
private val TabBarDividerDark = Color(0x99545458)

/**
 * Root of the app: a bottom tab bar switching between the Home and
 * Settings tabs, each hosting its own typed back stack (AppRouter +
 * TabStackHost) inside the keep-alive shell. Deep links resolve to
 * typed destinations in one router method (AppRouter.handleDeepLink);
 * system back pops the selected tab's stack and otherwise lets the
 * activity finish. The whole navigation state survives configuration
 * change and process death via rememberAppRouter's JSON saver.
 */
@Composable
fun NavigationBar(
    mainViewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    pendingDeepLinkUrl: String?,
    onDeepLinkConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appRouter = rememberAppRouter()

    // Deep links resolve in one place; the consumed callback clears
    // the pending URL so recompositions never re-route it.
    LaunchedEffect(pendingDeepLinkUrl) {
        if (pendingDeepLinkUrl != null) {
            appRouter.handleDeepLink(pendingDeepLinkUrl)
            onDeepLinkConsumed()
        }
    }

    // System back pops the selected tab's stack while one exists; at
    // a root it stays disabled and the activity finishes normally.
    BackHandler(enabled = appRouter.canGoBack) {
        appRouter.handleBack()
    }

    RouteOnAppEvents(appRouter)

    NavigationBarScaffold(
        selectedTab = appRouter.selectedTab,
        onTabSelected = appRouter::selectTab,
        modifier = modifier,
    ) { tab ->
        // Exhaustive: a new tab breaks THIS switch at compile time
        // instead of falling into a silent else.
        when (tab) {
            AppTab.HOME -> TabStackHost(
                tabRouter = appRouter.homeRouter,
                rootContent = {
                    HomePage(
                        mainViewModel = mainViewModel,
                        onOpenJokeDetail = { jokeId ->
                            appRouter.homeRouter.push(
                                HomeDestination.JokeDetail(
                                    jokeId = jokeId,
                                ),
                            )
                        },
                    )
                },
            ) { destination ->
                when (destination) {
                    is HomeDestination.JokeDetail -> JokeDetailPage(
                        jokeId = destination.jokeId,
                        onLoadJokeDetail =
                            mainViewModel::loadJokeDetail,
                        onBack = { appRouter.homeRouter.pop() },
                    )
                }
            }
            AppTab.SETTINGS -> TabStackHost(
                tabRouter = appRouter.settingsRouter,
                rootContent = {
                    SettingsPage(settingsViewModel)
                },
            ) {
                // No pushed Settings screens yet: the first one adds
                // a destination type and a branch, nothing else.
            }
        }
    }
}

/**
 * The ONLY place bus facts become navigation. Managers and viewmodels
 * publish FACTS (never destinations, never router calls); this choke
 * point maps facts to typed navigation, so routing policy lives in
 * one function. Direct user actions do not come through here: a
 * page's semantic lambda (onOpenJokeDetail) calls the router via the
 * shell's wiring above.
 *
 * No routed facts exist yet. The SessionComponent design's adoption
 * step (ARCHITECTURE-SCALING.md section 1) shows the shape a real
 * fact will take, using listenTo with an explicit owner plus
 * unsubscribeOwner for deterministic detach:
 *
 * ```
 * val eventManager = LocalEventManager.current
 * DisposableEffect(eventManager, appRouter) {
 *     val routingOwner = object {}
 *     eventManager.listenTo(SessionEnded, routingOwner) {
 *         appRouter.selectTab(AppTab.HOME)
 *     }
 *     onDispose { eventManager.unsubscribeOwner(routingOwner) }
 * }
 * ```
 */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun RouteOnAppEvents(appRouter: AppRouter) = Unit

@Composable
internal fun NavigationBarScaffold(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable (AppTab) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = {
            BottomTabBar(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
            )
        },
    ) { contentPadding ->
        // Keep-alive tab switching: every page stays composed (so state,
        // scroll positions, and one-shot entrance animations survive tab
        // changes) while opacity and a short shared-axis offset animate.
        Box(
            modifier = Modifier
                .padding(contentPadding)
                .clipToBounds(),
        ) {
            AppTab.entries.forEach { tab ->
                TabPageContainer(
                    tabIndex = tab.ordinal,
                    selectedTabIndex = selectedTab.ordinal,
                ) {
                    pageContent(tab)
                }
            }
        }
    }
}

/**
 * Custom iOS-style bottom tab bar: a hairline top divider, a translucent
 * surface (standing in for a material blur), and a brand-tinted pill
 * that slides between the equal-width items.
 */
@Composable
private fun BottomTabBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
) {
    val tabs = AppTab.entries
    val selectedTabIndex = selectedTab.ordinal
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A half-strength surface wash over the page background,
            // approximating a translucent material bar in both modes.
            .background(MaterialTheme.colorScheme.background)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color =
                if (isSystemInDarkTheme()) TabBarDividerDark
                else TabBarDividerLight,
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
        ) {
            val itemWidth = maxWidth / tabs.size
            val pillOffset by animateDpAsState(
                targetValue = itemWidth * selectedTabIndex +
                    (itemWidth - TabPillWidth) / 2,
                animationSpec = AppAnimations.tabOffsetSpec,
                label = "tabPillOffset",
            )

            // Sliding selection pill: brand at 14% fill.
            Box(
                modifier = Modifier
                    .offset(x = pillOffset)
                    .size(width = TabPillWidth, height = TabPillHeight)
                    .background(
                        color = Color(BrandColors.BRAND).copy(alpha = 0.14f),
                        shape = CircleShape,
                    ),
            )

            Row(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                tabs.forEach { tab ->
                    TabBarItem(
                        tab = tab.toNavigationPage(),
                        isSelected = tab == selectedTab,
                        onClick = { onTabSelected(tab) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabBarItem(
    tab: NavigationPage,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The selected item uses the non-adaptive BRAND blue in both
    // appearances, deliberately bypassing colorScheme.primary (which
    // swaps to BRAND_DISABLED in dark mode).
    val itemTint by animateColorAsState(
        targetValue =
            if (isSelected) Color(BrandColors.BRAND)
            else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = AppAnimations.tabTintSpec,
        label = "tabItemTint",
    )

    val tabLabel = stringResource(tab.nameResId)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // selectable (not clickable) so screen readers announce the tab
        // role and which item is selected.
        modifier = modifier.selectable(
            selected = isSelected,
            interactionSource = remember { MutableInteractionSource() },
            // No ripple: the sliding pill is the selection feedback.
            indication = null,
            role = Role.Tab,
            onClick = onClick,
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.height(TabPillHeight),
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tabLabel,
                tint = itemTint,
                modifier = Modifier.size(TabIconSize),
            )
        }
        Spacer(modifier = Modifier.height(TabLabelSpacing))
        Text(
            text = tabLabel,
            color = itemTint,
            fontSize = TabLabelSize,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TabPageContainer(
    tabIndex: Int,
    selectedTabIndex: Int,
    content: @Composable () -> Unit,
) {
    val isSelected = tabIndex == selectedTabIndex

    val pageAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = AppAnimations.tabFadeSpec,
        label = "tabPageAlpha",
    )
    // Unselected pages rest slightly toward their own side, producing the
    // shared-axis slide as the crossfade runs.
    val restingOffset = when {
        isSelected -> 0.dp
        tabIndex > selectedTabIndex -> AppAnimations.tabSwitchOffset
        else -> -AppAnimations.tabSwitchOffset
    }
    val pageOffset by animateDpAsState(
        targetValue = restingOffset,
        animationSpec = AppAnimations.tabOffsetSpec,
        label = "tabPageOffset",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (isSelected) 1f else 0f)
            .graphicsLayer {
                alpha = pageAlpha
                translationX = pageOffset.toPx()
            }
            // Keeps hardware-keyboard/D-pad focus from wandering onto the
            // invisible page's controls.
            .focusProperties { canFocus = isSelected },
    ) {
        content()
        if (!isSelected) {
            // Shield over the hidden page: swallows any touch that would
            // otherwise fall through to its invisible controls (zIndex
            // ordering alone only helps where the selected page has a
            // pointer-consuming node at that position).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .forEach { change -> change.consume() }
                            }
                        }
                    },
            )
        }
    }
}

// Display data stays a view concern: the router knows tabs, the view
// knows their names and icons. Labels are resource IDs so the bar
// localizes; the composable item resolves them. Exhaustive: a new tab
// will not compile until it has display data here.
private fun AppTab.toNavigationPage(): NavigationPage = when (this) {
    AppTab.HOME -> NavigationPage(
        nameResId = R.string.tab_home,
        icon = Icons.Filled.Home,
    )
    AppTab.SETTINGS -> NavigationPage(
        nameResId = R.string.tab_settings,
        icon = Icons.Filled.Settings,
    )
}

@Preview(showBackground = true)
@Composable
private fun NavigationBarScaffoldPreview() {
    BaseAppTheme {
        NavigationBarScaffold(
            selectedTab = AppTab.HOME,
            onTabSelected = {},
        ) {
            Text(text = "Page content")
        }
    }
}
