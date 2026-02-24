package com.kaleaon.mnxmindmaker.ktheme

import kotlinx.serialization.Serializable

@Serializable
data class RGBColor(
    val r: Int,
    val g: Int,
    val b: Int
)

@Serializable
data class RGBAColor(
    val r: Int,
    val g: Int,
    val b: Int,
    val a: Float
)

@Serializable
data class MetallicGradient(
    val base: String,
    val highlight: String,
    val shadow: String,
    val shimmer: String
)

enum class MetallicVariant {
    SILVER, GOLD, GOLD_ROYAL_BLUE, BRONZE, COPPER,
    PLATINUM, ROSE_GOLD, TITANIUM, CHROME, COBALT
}

@Serializable
data class ColorScheme(
    val primary: String,
    val onPrimary: String,
    val primaryContainer: String,
    val onPrimaryContainer: String,
    val secondary: String,
    val onSecondary: String,
    val secondaryContainer: String,
    val onSecondaryContainer: String,
    val tertiary: String,
    val onTertiary: String,
    val tertiaryContainer: String,
    val onTertiaryContainer: String,
    val error: String,
    val onError: String,
    val errorContainer: String,
    val onErrorContainer: String,
    val background: String,
    val onBackground: String,
    val surface: String,
    val onSurface: String,
    val surfaceVariant: String,
    val onSurfaceVariant: String,
    val outline: String,
    val outlineVariant: String,
    val scrim: String,
    val inverseSurface: String,
    val inverseOnSurface: String,
    val inversePrimary: String
)

@Serializable
data class ShadowEffects(
    val enabled: Boolean,
    val elevation: Int,
    val blur: Int,
    val color: String
)

@Serializable
data class ShimmerEffects(
    val enabled: Boolean,
    val speed: Int,
    val intensity: Float,
    val angle: Int
)

@Serializable
data class MetallicEffects(
    val enabled: Boolean,
    val variant: String,
    val gradient: MetallicGradient,
    val intensity: Float
)

@Serializable
data class BlurEffects(
    val enabled: Boolean,
    val radius: Int
)

@Serializable
data class AnimationEffects(
    val enabled: Boolean,
    val duration: Int,
    val easing: String
)

@Serializable
data class TransitionEffects(
    val enabled: Boolean,
    val duration: Int,
    val properties: List<String>
)

@Serializable
data class ParticleEffects(
    val enabled: Boolean,
    val count: Int,
    val speed: Float,
    val size: Int,
    val color: String
)

@Serializable
data class GlowEffects(
    val enabled: Boolean,
    val radius: Int,
    val intensity: Float,
    val color: String,
    val pulse: Boolean = false
)

@Serializable
data class VisualEffects(
    val metallic: MetallicEffects? = null,
    val shadows: ShadowEffects? = null,
    val shimmer: ShimmerEffects? = null,
    val blur: BlurEffects? = null,
    val animations: AnimationEffects? = null,
    val transitions: TransitionEffects? = null,
    val particles: ParticleEffects? = null,
    val glow: GlowEffects? = null
)

@Serializable
data class Typography(
    val fontFamily: String,
    val fontSize: FontSize,
    val fontWeight: FontWeight,
    val lineHeight: Float,
    val letterSpacing: Float
)

@Serializable
data class FontSize(
    val small: Int,
    val medium: Int,
    val large: Int,
    val xlarge: Int
)

@Serializable
data class FontWeight(
    val light: Int,
    val regular: Int,
    val medium: Int,
    val bold: Int
)

@Serializable
data class ThemeMetadata(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val tags: List<String>,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class Theme(
    val metadata: ThemeMetadata,
    val darkMode: Boolean,
    val colorScheme: ColorScheme,
    val effects: VisualEffects? = null,
    val typography: Typography? = null
)
