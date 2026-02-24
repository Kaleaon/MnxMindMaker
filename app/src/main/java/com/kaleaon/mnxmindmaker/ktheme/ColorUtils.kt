package com.kaleaon.mnxmindmaker.ktheme

/**
 * Color utilities ported from Ktheme kotlin-plugin.
 *
 * Provides color conversion, manipulation, and validation helpers
 * with Android color-int interop.
 */
object ColorUtils {

    fun hexToRgb(hex: String): RGBColor {
        val clean = hex.removePrefix("#")
        require(clean.length == 6) { "Invalid hex color: $hex" }
        return RGBColor(
            r = clean.substring(0, 2).toInt(16),
            g = clean.substring(2, 4).toInt(16),
            b = clean.substring(4, 6).toInt(16)
        )
    }

    fun rgbToHex(rgb: RGBColor): String {
        return "#${rgb.r.toString(16).padStart(2, '0')}" +
                rgb.g.toString(16).padStart(2, '0') +
                rgb.b.toString(16).padStart(2, '0')
    }

    fun hexToRgba(hex: String, alpha: Float = 1f): RGBAColor {
        val rgb = hexToRgb(hex)
        return RGBAColor(rgb.r, rgb.g, rgb.b, alpha)
    }

    fun darken(hex: String, percent: Float): String {
        val rgb = hexToRgb(hex)
        val factor = 1 - percent / 100
        return rgbToHex(
            RGBColor(
                r = (rgb.r * factor).toInt().coerceAtLeast(0),
                g = (rgb.g * factor).toInt().coerceAtLeast(0),
                b = (rgb.b * factor).toInt().coerceAtLeast(0)
            )
        )
    }

    fun lighten(hex: String, percent: Float): String {
        val rgb = hexToRgb(hex)
        val factor = percent / 100
        return rgbToHex(
            RGBColor(
                r = (rgb.r + (255 - rgb.r) * factor).toInt().coerceAtMost(255),
                g = (rgb.g + (255 - rgb.g) * factor).toInt().coerceAtMost(255),
                b = (rgb.b + (255 - rgb.b) * factor).toInt().coerceAtMost(255)
            )
        )
    }

    fun mix(color1: String, color2: String, weight: Float = 0.5f): String {
        val rgb1 = hexToRgb(color1)
        val rgb2 = hexToRgb(color2)
        return rgbToHex(
            RGBColor(
                r = (rgb1.r * (1 - weight) + rgb2.r * weight).toInt(),
                g = (rgb1.g * (1 - weight) + rgb2.g * weight).toInt(),
                b = (rgb1.b * (1 - weight) + rgb2.b * weight).toInt()
            )
        )
    }

    fun getContrastColor(backgroundColor: String): String {
        val rgb = hexToRgb(backgroundColor)
        val luminance = (0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b) / 255
        return if (luminance > 0.5) "#000000" else "#FFFFFF"
    }

    fun isValidHex(hex: String): Boolean {
        val clean = hex.removePrefix("#")
        return clean.matches(Regex("^[a-fA-F0-9]{3}$|^[a-fA-F0-9]{6}$|^[a-fA-F0-9]{8}$"))
    }

    fun colorIntToHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }

    fun hexToColorInt(hex: String): Int {
        val clean = hex.removePrefix("#")
        return when (clean.length) {
            6 -> ("FF$clean").toLong(16).toInt()
            8 -> clean.toLong(16).toInt()
            else -> throw IllegalArgumentException("Invalid hex color: $hex")
        }
    }
}
