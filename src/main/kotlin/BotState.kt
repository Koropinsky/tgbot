import java.util.concurrent.ConcurrentHashMap

enum class BotState { NONE, ADDING_SHOPPING, ADDING_GOAL }

// Пам'ять для тимчасового збереження стану користувача (додавання покупок/цілей)
val userStates = mutableMapOf<Long, BotState>()

// Пам'ять для тимчасового збереження назви рекомендації перед тим, як поставити оцінку
val lastRecommendations = ConcurrentHashMap<Long, Pair<String, String>>()
