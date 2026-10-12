package com.perfmode.boost.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃（Liquid Glass）主题基础设施。
 *
 * 整个 App 共用同一个 backdrop（背景快照层），所有卡片从这份快照里
 * 取自己身后的那块像素，做模糊 + 折射 + 高光 + 内外阴影，
 * 于是卡片看起来像一块浮在内容之上的玻璃。
 *
 * backdrop 通过 CompositionLocal 下发，业务页面不用层层传参。
 *
 * 配色上刻意不直接用 colorScheme.surface：深色模式下 surface 接近纯黑，
 * 拿它当玻璃底板会把整页压成一团黑。深色一律改用 surfaceContainer 系列
 * （有明度的灰），浅色才用 surface。
 */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 玻璃画质。
 *
 * RICH 用 26dp 模糊 + AGSL 折射着色器（lens），质感最好但很吃 GPU；
 * 一屏十几张卡片同时滚动时会明显掉帧。
 * LIGHT 只做 12dp 模糊 + 增艳，去掉最贵的 lens，观感差别不大但流畅很多。
 *
 * 默认 LIGHT：这类工具应用的可用性比"好看"重要。
 */
enum class GlassQuality { LIGHT, RICH }

val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.LIGHT }

/** 卡片圆角 */
val GlassShape: RoundedCornerShape = RoundedCornerShape(24.dp)

/** 悬浮导航栏：完全圆角的胶囊 */
val GlassBarShape: RoundedCornerShape = RoundedCornerShape(percent = 50)

/** 悬浮导航栏与屏幕边缘的距离 */
val GlassBarMargin = 16.dp

/** 玻璃底板色：即使设备不支持模糊（Android 11 及以下）也保证文字可读 */
@Composable
fun glassSurfaceColor(): Color {
    val c = MaterialTheme.colorScheme
    return if (isSystemInDarkTheme()) {
        // 深色：用带明度的 container 灰，绝不用接近纯黑的 surface
        c.surfaceContainerHigh.copy(alpha = 0.62f)
    } else {
        c.surface.copy(alpha = 0.60f)
    }
}

/** 悬浮栏的底色，比卡片更实一点，避免压在内容上看不清图标 */
@Composable
fun glassBarColor(): Color {
    val c = MaterialTheme.colorScheme
    return if (isSystemInDarkTheme()) {
        c.surfaceContainerHighest.copy(alpha = 0.72f)
    } else {
        c.surface.copy(alpha = 0.72f)
    }
}

/**
 * 把一块内容变成玻璃。
 * 支持 RenderEffect（Android 12+）时走真实模糊/折射；
 * 不支持时只叠半透明底色，视觉降级但信息不丢。
 */
@Composable
fun Modifier.glass(
    backdrop: Backdrop?,
    shape: Shape = GlassShape,
    tint: Color = Color.Unspecified,
    surfaceColor: Color? = null
): Modifier {
    if (backdrop == null) {
        return this.background(surfaceColor ?: glassSurfaceColor(), shape)
    }
    val surface = surfaceColor ?: glassSurfaceColor()
    val quality = LocalGlassQuality.current
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (quality == GlassQuality.RICH) {
                vibrancy()
                blur(26f.dp.toPx())
                lens(16f.dp.toPx(), 28f.dp.toPx())
            } else {
                // 轻量：只保留模糊与增艳，不做折射着色器
                vibrancy()
                blur(12f.dp.toPx())
            }
        },
        highlight = { if (quality == GlassQuality.RICH) Highlight.Default else null },
        shadow = { if (quality == GlassQuality.RICH) Shadow.Default else null },
        innerShadow = { if (quality == GlassQuality.RICH) InnerShadow.Default else null },
        onDrawSurface = {
            drawRect(surface)
            if (tint != Color.Unspecified) {
                drawRect(tint.copy(alpha = 0.18f))
            }
        }
    )
}

/**
 * App 背景渐变：给玻璃提供有层次的取样源。
 * 纯色背景会让玻璃完全看不出效果；深色下用 primary/secondaryContainer
 * 保证不是纯黑。
 */
@Composable
fun appBackgroundBrush(): Brush {
    val c = MaterialTheme.colorScheme
    return if (isSystemInDarkTheme()) {
        Brush.verticalGradient(
            listOf(
                c.surfaceContainerLowest,
                c.primaryContainer.copy(alpha = 0.40f),
                c.secondaryContainer.copy(alpha = 0.30f),
                c.surfaceContainerLowest
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                c.surface,
                c.primaryContainer.copy(alpha = 0.55f),
                c.tertiaryContainer.copy(alpha = 0.45f),
                c.surface
            )
        )
    }
}

/**
 * 玻璃容器：渲染背景并把 backdrop 下发给所有子页面。
 * 背景这一层必须先于任何玻璃卡片绘制，否则取样不到东西。
 */
@Composable
fun GlassRoot(content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .layerBackdrop(backdrop)
                    .fillMaxSize()
                    .background(appBackgroundBrush())
            )
            content()
        }
    }
}
