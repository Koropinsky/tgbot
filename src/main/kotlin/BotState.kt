import java.util.concurrent.ConcurrentHashMap

enum class BotState {
    NONE,
    ADDING_SHOPPING,
    ADDING_GOAL,
    ADDING_CALENDAR_EVENT,
    ADDING_TASK_YA,
    ADDING_TASK_N,
    ADDING_PROJECT_NAME,
    ADDING_TASK_PROJECT
}

// Пам'ять для тимчасового збереження стану користувача (додавання покупок/цілей/завдань)
val userStates = mutableMapOf<Long, BotState>()

// Пам'ять для тимчасового збереження назви рекомендації перед тим, як поставити оцінку
val lastRecommendations = ConcurrentHashMap<Long, Pair<String, String>>()

// Пам'ять для збереження ID проекту, в який зараз додається таск користувачем
val activeProjectForUser = ConcurrentHashMap<Long, Int>()
