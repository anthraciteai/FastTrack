package ai.anthracite.fasttrack.nutrition.estimator

/**
 * Picks the best available engine: the on-device LiteRT model when its weights are downloaded,
 * otherwise the always-available heuristic. Swapping in a cloud estimator later is a one-line
 * change here.
 */
class CompositeMealEstimator(
    private val primary: MealEstimator,
    private val fallback: MealEstimator
) : MealEstimator {

    override val engineName: String get() = "auto"

    override suspend fun status(): EstimatorStatus {
        val primaryStatus = primary.status()
        return if (primaryStatus.state == EstimatorState.READY) primaryStatus else fallback.status()
    }

    override suspend fun estimate(description: String): MealEstimate? {
        return probe(description).estimate
    }

    override suspend fun parseMeal(description: String): ParsedMeal {
        val first = primary.parseMeal(description)
        if (first.ok && first.items.isNotEmpty()) return first
        val second = fallback.parseMeal(description)
        if (second.items.isNotEmpty()) {
            return second.copy(engine = "${primary.engineName} miss → ${second.engine}")
        }
        return first
    }

    override suspend fun probe(description: String): NlpProbeResult {
        val first = primary.probe(description)
        if (first.ok && first.estimate != null) return first
        val second = fallback.probe(description)
        if (second.estimate != null) {
            return second.copy(
                engine = "${primary.engineName} miss → ${second.engine}",
                error = first.error,
                engineReset = first.engineReset
            )
        }
        return first
    }
}
