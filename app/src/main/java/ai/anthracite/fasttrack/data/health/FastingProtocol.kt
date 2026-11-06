package ai.anthracite.fasttrack.data.health

/**
 * Common intermittent-fasting protocols. [fastHours] is the fasting window length;
 * the eating window is implied (24 - fastHours) for daily protocols.
 */
enum class FastingProtocol(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val fastHours: Double
) {
    FOURTEEN_TEN("FOURTEEN_TEN", "14:10", "Beginner friendly", 14.0),
    SIXTEEN_EIGHT("SIXTEEN_EIGHT", "16:8", "Most popular", 16.0),
    EIGHTEEN_SIX("EIGHTEEN_SIX", "18:6", "Intermediate", 18.0),
    TWENTY_FOUR("TWENTY_FOUR", "20:4", "Warrior", 20.0),
    OMAD("OMAD", "OMAD", "One meal a day", 23.0);

    companion object {
        fun fromId(id: String?): FastingProtocol =
            entries.firstOrNull { it.id == id } ?: SIXTEEN_EIGHT
    }
}
