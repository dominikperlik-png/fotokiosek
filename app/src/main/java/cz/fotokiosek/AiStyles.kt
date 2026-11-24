package cz.fotokiosek

import org.json.JSONArray
import org.json.JSONObject

/** Jeden AI styl: název a ikona pro zákazníka, prompt (anglicky) pro model. */
data class AiStyle(
    val id: String,
    val name: String,
    val icon: String,
    val prompt: String,
    val enabled: Boolean = true,
    /** Přibalený styl (jde vypnout a upravit, ne smazat). */
    val builtin: Boolean = false,
)

/**
 * Seznam AI stylů. Uložený jako JSON v nastavení `ai_styles`; prázdné = výchozí styly.
 * Do každého promptu se přidá [PRESERVE] – zachovat lidi, obličeje a pózu, měnit jen styl, oblečení a pozadí.
 */
object AiStyles {
    /** Pevná část promptu pro všechny styly (i vlastní). */
    const val PRESERVE =
        "Edit the attached photo. Strictly preserve every person exactly as photographed: their faces, identity, " +
            "facial features, facial expressions, age, skin tone, body proportions and gaze must stay the same, so " +
            "that each person remains clearly recognizable. Keep exactly the same number of people, the same body " +
            "poses and positions, and the same composition and framing. Do not add, remove, merge or duplicate people. " +
            "Do not add any text, letters, logos or watermarks. Change only the artistic style, the clothing and the " +
            "background, as follows: "

    val defaults = listOf(
        AiStyle(
            "comic", "Komiks", "💥",
            "Turn the photo into a classic comic book illustration with bold black ink outlines, halftone dot shading " +
                "and vivid flat colours. Replace the background with a dynamic comic panel background with speed lines.",
            builtin = true,
        ),
        AiStyle(
            "animated3d", "3D animák (Pixar)", "🎬",
            "Re-render the photo in the style of a modern 3D animated family movie: soft cinematic lighting, smooth " +
                "stylized 3D characters that stay faithful likenesses of the real people, colourful playful clothing " +
                "and a bright, cheerful animated-movie set as the background.",
            builtin = true,
        ),
        AiStyle(
            "cyberpunk", "Cyberpunk", "🌃",
            "Cyberpunk style: futuristic techwear clothing with glowing neon accents, a rainy neon-lit futuristic " +
                "city street at night with holographic signs as the background, cinematic teal and magenta lighting.",
            builtin = true,
        ),
        AiStyle(
            "retro90", "90s Retro", "📼",
            "A 1990s retro photo: colourful 90s fashion such as windbreakers, denim jackets and bold patterns, " +
                "slightly faded colours with vintage film grain, and a classic 90s laser-pattern studio backdrop.",
            builtin = true,
        ),
        AiStyle(
            "superhero", "Superhrdina", "⚡",
            "Epic superhero movie poster: dress everyone in original superhero suits with capes and armour details " +
                "(no existing characters, symbols or logos), a city skyline at sunset as the background, dramatic " +
                "cinematic lighting. No title text.",
            builtin = true,
        ),
        AiStyle(
            "fantasy", "Fantasy / Středověk", "🏰",
            "Medieval fantasy: knight armour, royal gowns or wizard robes, a grand castle hall or an enchanted forest " +
                "as the background, painterly epic fantasy lighting.",
            builtin = true,
        ),
        AiStyle(
            "gamecover", "Herní plakát (GTA styl)", "🚓",
            "Stylized illustrated action video game cover art: bold outlines, saturated colours, cel-shaded painted " +
                "look, cool streetwear clothing, a sunny palm-lined city with sports cars as the background. " +
                "Do not include any real game titles, logos or text.",
            builtin = true,
        ),
        AiStyle(
            "astronaut", "Astronaut", "🚀",
            "Space explorers: realistic white astronaut spacesuits with the helmets off so the faces stay fully " +
                "visible, inside a space station with a large window showing planet Earth and stars as the background.",
            builtin = true,
        ),
        AiStyle(
            "oilpainting", "Olejomalba", "🖼️",
            "A classical oil painting on canvas in the manner of an old master portrait: visible brush strokes, rich " +
                "warm colours, elegant historical clothing and a dark painted studio background.",
            builtin = true,
        ),
        AiStyle(
            "anime", "Anime", "🌸",
            "Japanese anime illustration: clean line art, cel shading and bright colours, with faces drawn as " +
                "recognizable likenesses of the real people, and a scenic anime background with cherry blossoms.",
            builtin = true,
        ),
    )

    fun load(settings: KioskSettings): List<AiStyle> {
        val json = settings.aiStyles
        if (json.isBlank()) return defaults
        val saved = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AiStyle(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    icon = o.optString("icon"),
                    prompt = o.optString("prompt"),
                    enabled = o.optBoolean("enabled", true),
                    builtin = o.optBoolean("builtin", false),
                )
            }.filter { it.name.isNotBlank() && it.prompt.isNotBlank() }.distinctBy { it.id }
        }.onFailure { AppLog.e("AI", "Seznam AI stylů nejde přečíst – používám výchozí", it) }
            .getOrDefault(defaults)
        // Nové přibalené styly z aktualizace přidat na konec (uložený seznam je jinak nezná).
        return saved + defaults.filter { d -> saved.none { it.id == d.id } }
    }

    fun save(settings: KioskSettings, styles: List<AiStyle>) {
        // Beze změn = nic neukládat, ať se opravy výchozích stylů z aktualizací projeví.
        if (styles == defaults) {
            settings.aiStyles = ""
            return
        }
        val arr = JSONArray()
        styles.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("icon", s.icon).put("prompt", s.prompt)
                    .put("enabled", s.enabled).put("builtin", s.builtin),
            )
        }
        settings.aiStyles = arr.toString()
    }

    /** Výchozí styly zpět (vlastní styly zůstanou). */
    fun resetBuiltins(current: List<AiStyle>): List<AiStyle> = defaults + current.filter { !it.builtin }

    fun fullPrompt(style: AiStyle) = PRESERVE + style.prompt.trim()
}
