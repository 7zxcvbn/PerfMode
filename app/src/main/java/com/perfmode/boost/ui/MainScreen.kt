package com.perfmode.boost.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.ui.theme.GlassBarMargin
import com.perfmode.boost.ui.theme.GlassBarShape
import com.perfmode.boost.ui.theme.GlassRoot
import com.perfmode.boost.ui.theme.LocalBackdrop
import com.perfmode.boost.ui.theme.GlassQuality
import com.perfmode.boost.ui.theme.LocalGlassQuality
import com.perfmode.boost.ui.theme.PerfTheme
import com.perfmode.boost.ui.theme.glass
import com.perfmode.boost.ui.theme.glassBarColor
import com.perfmode.boost.vm.MainViewModel

@Composable
fun AppRoot(
    vm: MainViewModel,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit
) {
    var glassQuality by remember { mutableStateOf(GlassQuality.LIGHT) }

    PerfTheme {
        CompositionLocalProvider(LocalGlassQuality provides glassQuality) {
        GlassRoot {
            var tab by remember { mutableIntStateOf(0) }
            val state by vm.shizukuState.collectAsStateWithLifecycle()
            val profile by vm.profile.collectAsStateWithLifecycle()

            LaunchedEffect(state) {
                if ((state == ShizukuManager.State.READY_ADB ||
                        state == ShizukuManager.State.READY_ROOT) && profile == null
                ) {
                    vm.probe()
                }
            }

            Scaffold(
                // 背景由 GlassRoot 绘制，Scaffold 必须透明，否则会把玻璃盖住
                containerColor = Color.Transparent,
                bottomBar = {
                    FloatingGlassBar(
                        selected = tab,
                        onSelect = { tab = it }
                    )
                }
            ) { padding ->
                // 页面切换：按 tab 序号判断左右方向，淡入 + 小幅滑入，
                // 尺寸变化不过渡（clip = false），避免玻璃卡片被裁切。
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    transitionSpec = {
                        // 全部显式标注类型：tween 是泛型函数，交给推断时
                        // 遇到 Enter/ExitTransition 的 plus 重载容易歧义，
                        // 之前就是这里报"参数类型不匹配"和"it 未解析"。
                        val dir: Int = if (targetState > initialState) 1 else -1

                        val fadeInSpec: FiniteAnimationSpec<Float> =
                            tween(durationMillis = 200, easing = FastOutSlowInEasing)
                        val slideInSpec: FiniteAnimationSpec<IntOffset> =
                            tween(durationMillis = 260, easing = FastOutSlowInEasing)
                        val fadeOutSpec: FiniteAnimationSpec<Float> =
                            tween(durationMillis = 140, easing = FastOutSlowInEasing)
                        val slideOutSpec: FiniteAnimationSpec<IntOffset> =
                            tween(durationMillis = 200, easing = FastOutSlowInEasing)

                        val enter: EnterTransition = fadeIn(animationSpec = fadeInSpec) +
                            slideInHorizontally(animationSpec = slideInSpec) { fullWidth ->
                                fullWidth / 6 * dir
                            }
                        val exit: ExitTransition = fadeOut(animationSpec = fadeOutSpec) +
                            slideOutHorizontally(animationSpec = slideOutSpec) { fullWidth ->
                                -fullWidth / 6 * dir
                            }
                        enter togetherWith exit
                    },
                    label = "page"
                ) { target ->
                    when (target) {
                        0 -> HomeScreen(
                            vm = vm,
                            onRequestPermission = onRequestPermission,
                            onOpenShizuku = onOpenShizuku,
                            glassQuality = glassQuality,
                            onQualityChange = { glassQuality = it }
                        )
                        1 -> MonitorScreen()
                        2 -> LogScreen()
                        else -> TuningScreen(vm)
                    }
                }
            }
        }
        }
    }
}

private data class TabItem(
    val index: Int,
    val label: String,
    val icon: ImageVector,
    val desc: String
)

private val Tabs = listOf(
    TabItem(0, "机型", Icons.Default.Bolt, "机型与档位"),
    TabItem(3, "调频", Icons.Default.Speed, "Unrestrict 调频"),
    TabItem(1, "监控", Icons.Default.MonitorHeart, "实时监控"),
    TabItem(2, "日志", Icons.Default.List, "日志")
)

/**
 * 悬浮式液态玻璃导航栏。
 *
 * 不贴屏幕边缘：四周留 GlassBarMargin，做成完全圆角的胶囊，
 * 浮在内容之上而不是把底部切走一块。
 *
 * 动画：选中胶囊用弹簧滑动跟随，光晕透明度、图标尺寸、文字颜色
 * 各自做补间，切换时整体有"液体流动"的感觉。
 */
@Composable
private fun FloatingGlassBar(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val backdrop = LocalBackdrop.current
    val barColor = glassBarColor()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GlassBarMargin, vertical = GlassBarMargin),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .glass(
                    backdrop = backdrop,
                    shape = GlassBarShape,
                    surfaceColor = barColor
                )
                .clip(GlassBarShape)
        ) {
            val itemWidth = maxWidth / Tabs.size
            val index = Tabs.indexOfFirst { it.index == selected }.coerceAtLeast(0)

            // 弹簧滑动的选中胶囊
            val indicatorOffset by animateDpAsState(
                targetValue = itemWidth * index,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "indicator"
            )

            val primary = MaterialTheme.colorScheme.primary
            val secondary = MaterialTheme.colorScheme.secondary

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .background(
                        brush = Brush.radialGradient(
                            listOf(
                                primary.copy(alpha = 0.42f),
                                secondary.copy(alpha = 0.24f),
                                Color.Transparent
                            )
                        ),
                        shape = GlassBarShape
                    )
            )

            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tabs.forEach { item ->
                    TabButton(
                        item = item,
                        selected = selected == item.index,
                        onClick = { onSelect(item.index) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TabButton(
    item: TabItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // 只动画一个 0→1 的进度，颜色/尺寸全部由它插值得出。
    // 这样避免 animateColorAsState（它在 androidx.compose.animation，
    // 不在 animation.core，包名容易写错），也保证各属性严格同步。
    val sel by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "sel"
    )

    val iconSize = 23.dp + 3.dp * sel
    val tint = lerp(onSurfaceVariant.copy(alpha = 0.72f), primary, sel)
    val labelColor = lerp(onSurface.copy(alpha = 0.72f), primary, sel)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(GlassBarShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            item.icon,
            contentDescription = item.desc,
            modifier = Modifier.size(iconSize),
            tint = tint
        )
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor
        )
    }
}
