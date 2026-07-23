package com.mattmooneyham.base.android.views

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mattmooneyham.base.android.animations.AppAnimations
import com.mattmooneyham.base.android.viewModels.MainViewModel
import com.mattmooneyham.base.android.viewModels.SettingsViewModel
import com.mattmooneyham.base.android.constants.BrandColors

data class NavigationPage(
    val name: String,
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
 * Root of the app: a bottom tab bar switching between the Home and Settings
 * tabs. Two tabs need no back stack, so plain saveable state stands in for
 * a navigation library.
 */
@Composable
fun NavigationBar(
    mainViewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    NavigationBarScaffold(
        selectedTabIndex = selectedTabIndex,
        onTabSelected = { tappedIndex -> selectedTabIndex = tappedIndex },
        modifier = modifier,
    ) { currentTabIndex ->
        when (currentTabIndex) {
            0 -> HomePage(mainViewModel)
            else -> SettingsPage(settingsViewModel)
        }
    }
}

@Composable
internal fun NavigationBarScaffold(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val tabs = baseNavigationTabs()
    Scaffold(
        modifier = modifier,
        bottomBar = {
            BottomTabBar(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex,
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
            tabs.forEachIndexed { tabIndex, _ ->
                TabPageContainer(
                    tabIndex = tabIndex,
                    selectedTabIndex = selectedTabIndex,
                ) {
                    pageContent(tabIndex)
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
    tabs: List<NavigationPage>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
) {
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
                tabs.forEachIndexed { tabIndex, tab ->
                    TabBarItem(
                        tab = tab,
                        isSelected = tabIndex == selectedTabIndex,
                        onClick = { onTabSelected(tabIndex) },
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
                contentDescription = tab.name,
                tint = itemTint,
                modifier = Modifier.size(TabIconSize),
            )
        }
        Spacer(modifier = Modifier.height(TabLabelSpacing))
        Text(
            text = tab.name,
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

private fun baseNavigationTabs(): List<NavigationPage> = listOf(
    NavigationPage(name = "Home", icon = Icons.Filled.Home),
    NavigationPage(name = "Settings", icon = Icons.Filled.Settings),
)

@Preview(showBackground = true)
@Composable
private fun NavigationBarScaffoldPreview() {
    BaseAppTheme {
        NavigationBarScaffold(
            selectedTabIndex = 0,
            onTabSelected = {},
        ) {
            Text(text = "Page content")
        }
    }
}
