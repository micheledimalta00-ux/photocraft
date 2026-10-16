package com.michele.luce.engine

/**
 * Profili di rendering: un "punto di partenza" applicato prima dei cursori,
 * scalato dal valore Quantità (0..200%).
 */
data class Profile(
    val name: String,
    val group: String,
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -1..1
    val vibrance: Float = 0f,     // -1..1
    val warmth: Float = 0f,       // -1..1
    val fade: Float = 0f,         // 0..1 sollevamento dei neri
    val shoulder: Float = 0f,     // 0..1 morbidezza delle luci
    val bw: Boolean = false,
    val bwRed: Float = 0f, val bwGreen: Float = 0f, val bwBlue: Float = 0f
)

object Profiles {
    const val DEFAULT = "Colore"

    val all: List<Profile> = listOf(
        Profile("Colore", "Base"),
        Profile("Vivido", "Base", contrast = 0.18f, saturation = 0.18f, vibrance = 0.2f),
        Profile("Ritratto", "Base", contrast = 0.05f, saturation = -0.05f, vibrance = 0.08f, warmth = 0.06f, shoulder = 0.3f),
        Profile("Paesaggio", "Base", contrast = 0.15f, saturation = 0.12f, vibrance = 0.25f, warmth = -0.03f),
        Profile("Neutro", "Base", contrast = -0.15f, saturation = -0.08f, shoulder = 0.4f),
        Profile("Piatto", "Base", contrast = -0.35f, saturation = -0.15f, fade = 0.06f, shoulder = 0.6f),
        Profile("Cinema", "Artistici", contrast = 0.12f, saturation = -0.1f, warmth = 0.05f, fade = 0.04f, shoulder = 0.5f),
        Profile("Pellicola calda", "Artistici", contrast = 0.08f, saturation = -0.05f, vibrance = 0.1f, warmth = 0.18f, fade = 0.05f, shoulder = 0.4f),
        Profile("Pellicola fredda", "Artistici", contrast = 0.1f, saturation = -0.08f, warmth = -0.15f, fade = 0.04f, shoulder = 0.4f),
        Profile("Retrò", "Artistici", contrast = -0.05f, saturation = -0.3f, warmth = 0.2f, fade = 0.1f, shoulder = 0.5f),
        Profile("Monocromatico", "Bianco e nero", bw = true),
        Profile("B&N alto contrasto", "Bianco e nero", contrast = 0.35f, bw = true, bwRed = 0.2f, bwBlue = -0.2f),
        Profile("B&N morbido", "Bianco e nero", contrast = -0.15f, fade = 0.05f, shoulder = 0.5f, bw = true),
        Profile("B&N filtro rosso", "Bianco e nero", contrast = 0.15f, bw = true, bwRed = 0.45f, bwGreen = -0.1f, bwBlue = -0.45f),
        Profile("B&N filtro verde", "Bianco e nero", contrast = 0.1f, bw = true, bwRed = -0.2f, bwGreen = 0.35f, bwBlue = -0.1f)
    )

    fun byName(name: String): Profile = all.firstOrNull { it.name == name } ?: all[0]
}
