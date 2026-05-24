import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun Dispatcher.setupBotHandlers() {
    command("start") {
        val chatId = message.chat.id
        saveChatId(chatId)
        userStates[chatId] = BotState.NONE
        val keyboard = KeyboardReplyMarkup(
            keyboard = listOf(
                listOf(KeyboardButton("🛒 Список покупок"), KeyboardButton("🎯 Наші цілі")),
                listOf(KeyboardButton("📅 Календар подій"), KeyboardButton("💻 Проекти")),
                listOf(KeyboardButton("👤 Таски Я"), KeyboardButton("👤 Таски Н")),
                listOf(KeyboardButton("✨ Надихни нас"), KeyboardButton("🍿 Порекомендуй щось")),
                listOf(KeyboardButton("📋 Переглянуте"))
            ),
            resizeKeyboard = true
        )
        val welcome = """
            Привіт! Я ваш сімейний бот 👋

            📌 Що я вмію:
            🛒 *Список покупок* — натисни або пиши `+молоко` / `-молоко`
            🎯 *Наші цілі* — спільні довгострокові цілі (або `*назва`)
            📅 *Календар подій* — плануй важливі дати
            💻 *Проекти* — спільні завдання за проектами
            👤 *Таски Я / Таски Н* — особисті завдання (або `+я завдання` / `+н завдання`)
            🍿 *Порекомендуй щось* — ШІ підбере фільм, серіал або аніме
            📋 *Переглянуте* — додай що дивились з оцінкою 1–5⭐
            ✨ *Надихни нас* — мотиваційне повідомлення від ШІ
        """.trimIndent()
        bot.sendMessage(ChatId.fromId(chatId), welcome, parseMode = com.github.kotlintelegrambot.entities.ParseMode.MARKDOWN, replyMarkup = keyboard)
    }

    text {
        val chatId = message.chat.id
        val state = userStates[chatId] ?: BotState.NONE
        val safeText = text.trim()

        when {
            state == BotState.ADDING_SHOPPING -> {
                if (safeText.isNotEmpty()) {
                    addShoppingItem(safeText)
                    userStates[chatId] = BotState.NONE
                    bot.sendMessage(ChatId.fromId(chatId), "✅ `$safeText` додано!")
                    showShoppingList(bot, chatId)
                }
            }
            state == BotState.ADDING_GOAL -> {
                if (safeText.isNotEmpty()) {
                    addGoalItem(safeText)
                    userStates[chatId] = BotState.NONE
                    bot.sendMessage(ChatId.fromId(chatId), "🎯 Ціль `$safeText` записана!")
                    showGoalsList(bot, chatId)
                }
            }
            state == BotState.ADDING_TASK_YA -> {
                if (safeText.isNotEmpty()) {
                    addTask(null, "YA", safeText)
                    userStates[chatId] = BotState.NONE
                    bot.sendMessage(ChatId.fromId(chatId), "✅ Особисте завдання додано для **Я**!")
                    showPersonalTasks(bot, chatId, "YA")
                }
            }
            state == BotState.ADDING_TASK_N -> {
                if (safeText.isNotEmpty()) {
                    addTask(null, "N", safeText)
                    userStates[chatId] = BotState.NONE
                    bot.sendMessage(ChatId.fromId(chatId), "✅ Особисте завдання додано для **Насосика**!")
                    showPersonalTasks(bot, chatId, "N")
                }
            }
            state == BotState.ADDING_PROJECT_NAME -> {
                if (safeText.isNotEmpty()) {
                    val newProjId = addProject(safeText)
                    userStates[chatId] = BotState.NONE
                    if (newProjId != -1) {
                        bot.sendMessage(ChatId.fromId(chatId), "✅ Проект «$safeText» успішно створено!")
                    } else {
                        bot.sendMessage(ChatId.fromId(chatId), "❌ Помилка створення проекту або він уже існує.")
                    }
                    showProjectsList(bot, chatId)
                }
            }
            state == BotState.ADDING_TASK_PROJECT -> {
                val projectId = activeProjectForUser[chatId]
                if (projectId != null && safeText.isNotEmpty()) {
                    addTask(projectId, null, safeText)
                    userStates[chatId] = BotState.NONE
                    activeProjectForUser.remove(chatId)
                    bot.sendMessage(ChatId.fromId(chatId), "✅ Завдання додано до проекту!")
                    showProjectTasks(bot, chatId, projectId)
                }
            }
            state == BotState.ADDING_WATCHED_TITLE -> {
                if (safeText.isNotEmpty()) {
                    val category = pendingWatchedCategory[chatId] ?: "movie"
                    lastRecommendations[chatId] = Pair(category, safeText)
                    pendingWatchedCategory.remove(chatId)
                    userStates[chatId] = BotState.NONE
                    val ratingKeyboard = InlineKeyboardMarkup.create(
                        listOf(listOf(
                            InlineKeyboardButton.CallbackData("1⭐", "rate_1"),
                            InlineKeyboardButton.CallbackData("2⭐", "rate_2"),
                            InlineKeyboardButton.CallbackData("3⭐", "rate_3"),
                            InlineKeyboardButton.CallbackData("4⭐", "rate_4"),
                            InlineKeyboardButton.CallbackData("5⭐", "rate_5")
                        ))
                    )
                    bot.sendMessage(ChatId.fromId(chatId), "Оцініть «$safeText» від 1 до 5:", replyMarkup = ratingKeyboard)
                }
            }
            state == BotState.ADDING_CALENDAR_EVENT -> {
                if (safeText.isNotEmpty()) {
                    val regex = Regex("""^(\d{2})\.(\d{2})\.(\d{4})\s+(.+)$""")
                    val match = regex.find(safeText)
                    if (match != null) {
                        val day = match.groupValues[1]
                        val month = match.groupValues[2]
                        val year = match.groupValues[3]
                        val eventText = match.groupValues[4].trim()

                        try {
                            val sqlDate = java.sql.Date.valueOf("$year-$month-$day")
                            addCalendarEvent(sqlDate, eventText)
                            userStates[chatId] = BotState.NONE
                            bot.sendMessage(ChatId.fromId(chatId), "✅ Подію «$eventText» заплановано на $day.$month.$year!")
                            showCalendarEvents(bot, chatId)
                        } catch (e: Exception) {
                            bot.sendMessage(ChatId.fromId(chatId), "❌ Невірна дата. Будь ласка, введіть дату у форматі `ДД.ММ.РРРР Текст` (наприклад, `25.05.2026 Вечеря`):")
                        }
                    } else {
                        bot.sendMessage(ChatId.fromId(chatId), "⚠️ Неправильний формат. Спробуйте ще раз у форматі `ДД.ММ.РРРР Текст` (наприклад, `25.05.2026 Вечеря`):")
                    }
                }
            }

            safeText == "🛒 Список покупок" -> showShoppingList(bot, chatId)
            safeText == "🎯 Наші цілі" -> showGoalsList(bot, chatId)
            safeText == "📅 Календар подій" -> showCalendarEvents(bot, chatId)
            safeText == "💻 Проекти" -> showProjectsList(bot, chatId)
            safeText == "👤 Таски Я" -> showPersonalTasks(bot, chatId, "YA")
            safeText == "👤 Таски Н" -> showPersonalTasks(bot, chatId, "N")

            safeText == "🍿 Порекомендуй щось" -> {
                val inlineKeyboard = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(InlineKeyboardButton.CallbackData("🎬 Фільм", "rec_movie"), InlineKeyboardButton.CallbackData("📺 Серіал", "rec_series")),
                        listOf(InlineKeyboardButton.CallbackData("⛩️ Аніме", "rec_anime"))
                    )
                )
                bot.sendMessage(ChatId.fromId(chatId), "Що будемо дивитися?", replyMarkup = inlineKeyboard)
            }

            safeText == "📋 Переглянуте" -> {
                val inlineKeyboard = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(
                            InlineKeyboardButton.CallbackData("🎬 Фільм", "manual_add_movie"),
                            InlineKeyboardButton.CallbackData("📺 Серіал", "manual_add_series"),
                            InlineKeyboardButton.CallbackData("⛩️ Аніме", "manual_add_anime")
                        )
                    )
                )
                bot.sendMessage(ChatId.fromId(chatId), "Що додаємо до переглянутого?", replyMarkup = inlineKeyboard)
            }
            safeText == "✨ Надихни нас" -> {
                bot.sendMessage(ChatId.fromId(chatId), "⏳ Запитую ШІ...")
                CoroutineScope(Dispatchers.IO).launch {
                    val aiMessage = generateAiMessage(isMorning = false)
                    getAllChatIds().forEach { id -> bot.sendMessage(ChatId.fromId(id), "💌 $aiMessage") }
                }
            }

            safeText.startsWith("+я", ignoreCase = true) -> {
                val taskText = safeText.substring(2).trim()
                if (taskText.isNotEmpty()) {
                    addTask(null, "YA", taskText)
                    bot.sendMessage(ChatId.fromId(chatId), "✅ Особисте завдання для **Я** додано:\n`$taskText`")
                }
            }
            safeText.startsWith("+н", ignoreCase = true) -> {
                val taskText = safeText.substring(2).trim()
                if (taskText.isNotEmpty()) {
                    addTask(null, "N", taskText)
                    bot.sendMessage(ChatId.fromId(chatId), "✅ Особисте завдання для **Насосика** додано:\n`$taskText`")
                }
            }
            safeText.startsWith("-я", ignoreCase = true) -> {
                val taskText = safeText.substring(2).trim()
                if (taskText.isNotEmpty()) {
                    val deleted = deletePersonalTaskByName("YA", taskText)
                    if (deleted) {
                        bot.sendMessage(ChatId.fromId(chatId), "🎉 Завдання для **Я** виконано та видалено:\n`$taskText`")
                    } else {
                        bot.sendMessage(ChatId.fromId(chatId), "❓ Не знайдено активного завдання для **Я** з текстом:\n`$taskText`")
                    }
                }
            }
            safeText.startsWith("-н", ignoreCase = true) -> {
                val taskText = safeText.substring(2).trim()
                if (taskText.isNotEmpty()) {
                    val deleted = deletePersonalTaskByName("N", taskText)
                    if (deleted) {
                        bot.sendMessage(ChatId.fromId(chatId), "🎉 Завдання для **Насосика** виконано та видалено:\n`$taskText`")
                    } else {
                        bot.sendMessage(ChatId.fromId(chatId), "❓ Не знайдено активного завдання для **Насосика** з текстом:\n`$taskText`")
                    }
                }
            }
            safeText.startsWith("+") -> {
                val item = safeText.removePrefix("+").trim()
                if (item.isNotEmpty()) {
                    addShoppingItem(item)
                    bot.sendMessage(ChatId.fromId(chatId), "✅ `$item` додано до покупок!")
                }
            }
            safeText.startsWith("-") -> {
                val item = safeText.removePrefix("-").trim()
                if (item.isNotEmpty()) {
                    val deleted = deleteShoppingItemByName(item)
                    if (deleted) {
                        bot.sendMessage(ChatId.fromId(chatId), "✅ `$item` видалено зі списку покупок!")
                    } else {
                        bot.sendMessage(ChatId.fromId(chatId), "❓ Не знайдено покупку з назвою:\n`$item`")
                    }
                }
            }
            safeText.startsWith("*") -> {
                val goal = safeText.removePrefix("*").trim()
                if (goal.isNotEmpty()) {
                    addGoalItem(goal)
                    bot.sendMessage(ChatId.fromId(chatId), "🎯 `$goal` додано до цілей!")
                }
            }
        }
    }

    callbackQuery {
        val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
        val messageId = callbackQuery.message?.messageId
        val data = callbackQuery.data

        when {
            // Покупки та Цілі
            data == "start_add_shop" -> {
                userStates[chatId] = BotState.ADDING_SHOPPING
                bot.sendMessage(ChatId.fromId(chatId), "📝 Напиши назву покупки:")
                bot.answerCallbackQuery(callbackQuery.id)
            }
            data == "start_add_goal" -> {
                userStates[chatId] = BotState.ADDING_GOAL
                bot.sendMessage(ChatId.fromId(chatId), "🚀 Напиши нову ціль:")
                bot.answerCallbackQuery(callbackQuery.id)
            }
            data.startsWith("del_shop_") -> {
                val id = data.removePrefix("del_shop_").toIntOrNull() ?: return@callbackQuery
                deleteShoppingItem(id)
                bot.answerCallbackQuery(callbackQuery.id, "Викреслено!")
                if (messageId != null) updateShoppingMessage(bot, chatId, messageId)
            }
            data.startsWith("del_goal_") -> {
                val id = data.removePrefix("del_goal_").toIntOrNull() ?: return@callbackQuery
                deleteGoalItem(id)
                bot.answerCallbackQuery(callbackQuery.id, "Вітаю з досягненням!")
                if (messageId != null) updateGoalsMessage(bot, chatId, messageId)
            }

            // Таски та Проекти
            data == "tasks_main" -> {
                bot.answerCallbackQuery(callbackQuery.id)
                if (messageId != null) updateTasksMainMenu(bot, chatId, messageId)
            }
            data == "tasks_ya" -> {
                bot.answerCallbackQuery(callbackQuery.id)
                if (messageId != null) updatePersonalTasksMessage(bot, chatId, messageId, "YA")
            }
            data == "tasks_n" -> {
                bot.answerCallbackQuery(callbackQuery.id)
                if (messageId != null) updatePersonalTasksMessage(bot, chatId, messageId, "N")
            }
            data == "projects_list" -> {
                bot.answerCallbackQuery(callbackQuery.id)
                if (messageId != null) updateProjectsListMessage(bot, chatId, messageId)
            }
            data.startsWith("project_view_") -> {
                val id = data.removePrefix("project_view_").toIntOrNull() ?: return@callbackQuery
                bot.answerCallbackQuery(callbackQuery.id)
                if (messageId != null) updateProjectTasksMessage(bot, chatId, messageId, id)
            }

            // Видалення тасків
            data.startsWith("del_task_personal_") -> {
                val parts = data.removePrefix("del_task_personal_").split("_")
                val assignee = parts.getOrNull(0) ?: return@callbackQuery
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return@callbackQuery
                deleteTask(id)
                bot.answerCallbackQuery(callbackQuery.id, "Виконано!")
                if (messageId != null) updatePersonalTasksMessage(bot, chatId, messageId, assignee)
            }
            data.startsWith("del_task_project_") -> {
                val parts = data.removePrefix("del_task_project_").split("_")
                val projId = parts.getOrNull(0)?.toIntOrNull() ?: return@callbackQuery
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return@callbackQuery
                deleteTask(id)
                bot.answerCallbackQuery(callbackQuery.id, "Виконано!")
                if (messageId != null) updateProjectTasksMessage(bot, chatId, messageId, projId)
            }

            // Тригери додавання
            data == "add_task_personal_ya" -> {
                userStates[chatId] = BotState.ADDING_TASK_YA
                bot.sendMessage(ChatId.fromId(chatId), "📝 Напиши завдання для **Я**:")
                bot.answerCallbackQuery(callbackQuery.id)
            }
            data == "add_task_personal_n" -> {
                userStates[chatId] = BotState.ADDING_TASK_N
                bot.sendMessage(ChatId.fromId(chatId), "📝 Напиши завдання для **Насосика**:")
                bot.answerCallbackQuery(callbackQuery.id)
            }
            data == "add_project_start" -> {
                userStates[chatId] = BotState.ADDING_PROJECT_NAME
                bot.sendMessage(ChatId.fromId(chatId), "📝 Напиши назву нового проекту:")
                bot.answerCallbackQuery(callbackQuery.id)
            }
            data.startsWith("add_task_project_start_") -> {
                val projId = data.removePrefix("add_task_project_start_").toIntOrNull() ?: return@callbackQuery
                activeProjectForUser[chatId] = projId
                userStates[chatId] = BotState.ADDING_TASK_PROJECT
                val projName = getProjectName(projId)
                bot.sendMessage(ChatId.fromId(chatId), "📝 Напиши нове завдання для проекту «$projName»:")
                bot.answerCallbackQuery(callbackQuery.id)
            }

            // Календар
            data == "start_add_calendar" -> {
                userStates[chatId] = BotState.ADDING_CALENDAR_EVENT
                bot.sendMessage(ChatId.fromId(chatId), "📝 Введіть дату та опис події у форматі `ДД.ММ.РРРР Текст` (наприклад, `25.05.2026 Вечеря`):")
                bot.answerCallbackQuery(callbackQuery.id)
            }
            data.startsWith("del_calendar_") -> {
                val id = data.removePrefix("del_calendar_").toIntOrNull() ?: return@callbackQuery
                deleteCalendarEvent(id)
                bot.answerCallbackQuery(callbackQuery.id, "Видалено з календаря!")
                if (messageId != null) updateCalendarMessage(bot, chatId, messageId)
            }

            // Ручне додавання переглянутого
            data == "manual_add_movie" || data == "manual_add_series" || data == "manual_add_anime" -> {
                val category = data.removePrefix("manual_add_")
                pendingWatchedCategory[chatId] = category
                userStates[chatId] = BotState.ADDING_WATCHED_TITLE
                val categoryName = when (category) {
                    "movie" -> "фільм"
                    "series" -> "серіал"
                    "anime" -> "аніме"
                    else -> "контент"
                }
                bot.sendMessage(ChatId.fromId(chatId), "📝 Введіть назву ($categoryName):")
                bot.answerCallbackQuery(callbackQuery.id)
            }

            // Рекомендації аніме/кіно
            data.startsWith("rec_") -> {
                val category = data.removePrefix("rec_")
                bot.answerCallbackQuery(callbackQuery.id, "Генерую підбірку...")

                CoroutineScope(Dispatchers.IO).launch {
                    val recommendation = generateRecommendation(category)

                    if (recommendation.startsWith("Помилка API") || recommendation.startsWith("Внутрішня помилка") || recommendation.contains("Здається, я передивився")) {
                        bot.sendMessage(ChatId.fromId(chatId), "🍿 $recommendation")
                    } else {
                        val title = recommendation.lines().firstOrNull()?.replace(Regex("[*\"_]"), "")?.trim() ?: "Невідомий тайтл"

                        lastRecommendations[chatId] = Pair(category, title)

                        val btn = InlineKeyboardMarkup.create(
                            listOf(listOf(InlineKeyboardButton.CallbackData("👀 Вже дивились", "mark_watched")))
                        )
                        bot.sendMessage(ChatId.fromId(chatId), "🍿 $recommendation", replyMarkup = btn)
                    }
                }
            }
            data == "mark_watched" -> {
                val rec = lastRecommendations[chatId]
                if (rec != null) {
                    bot.answerCallbackQuery(callbackQuery.id)
                    val ratingKeyboard = InlineKeyboardMarkup.create(
                        listOf(
                            listOf(
                                InlineKeyboardButton.CallbackData("1⭐", "rate_1"),
                                InlineKeyboardButton.CallbackData("2⭐", "rate_2"),
                                InlineKeyboardButton.CallbackData("3⭐", "rate_3"),
                                InlineKeyboardButton.CallbackData("4⭐", "rate_4"),
                                InlineKeyboardButton.CallbackData("5⭐", "rate_5")
                            )
                        )
                    )
                    if (messageId != null) {
                        bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = ratingKeyboard)
                        bot.sendMessage(ChatId.fromId(chatId), "Оцініть «${rec.second}» від 1 до 5:")
                    }
                } else {
                    bot.answerCallbackQuery(callbackQuery.id, "Час очікування минув.", showAlert = true)
                }
            }
            data.startsWith("rate_") -> {
                val rating = data.removePrefix("rate_").toIntOrNull() ?: 0
                val rec = lastRecommendations[chatId]

                if (rec != null) {
                    addWatchedItem(rec.first, rec.second, rating)
                    bot.answerCallbackQuery(callbackQuery.id, "✅ Оцінка $rating⭐ збережена!")

                    if (messageId != null) {
                        bot.editMessageReplyMarkup(ChatId.fromId(chatId), messageId, replyMarkup = null)
                    }
                    bot.sendMessage(ChatId.fromId(chatId), "Запам'ятав! Ви оцінили «${rec.second}» на $rating⭐. ШІ врахує це наступного разу!")
                    lastRecommendations.remove(chatId)
                } else {
                    bot.answerCallbackQuery(callbackQuery.id, "Помилка збереження.", showAlert = true)
                }
            }
        }
    }
}
