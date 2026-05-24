import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun getPoltavaWeather(): String {
    val apiKey = System.getenv("WEATHER_API_KEY") ?: return "Погода: API ключ не знайдено."
    // Використовуємо forecast API для отримання прогнозу на весь день та заходу сонця
    val url = "https://api.openweathermap.org/data/2.5/forecast?q=Poltava,ua&appid=$apiKey&units=metric&lang=uk"
    return try {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            return "Не вдалося отримати погоду. Код: ${response.statusCode()}"
        }
        
        val json = JsonParser.parseString(response.body()).asJsonObject
        
        // Отримуємо час заходу сонця з блоку city
        val sunsetUnix = json.getAsJsonObject("city").get("sunset").asLong
        val sunsetTime = Instant.ofEpochSecond(sunsetUnix)
            .atZone(ZoneId.of("Europe/Kyiv"))
            .format(DateTimeFormatter.ofPattern("HH:mm"))

        val list = json.getAsJsonArray("list")
        var maxTemp = -100.0
        var minTemp = 100.0
        var willRain = false
        var mainDescription = ""

        val today = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).toLocalDate()

        // Проходимося по 3-годинних прогнозах на сьогодні
        for (i in 0 until list.size()) {
            val item = list.get(i).asJsonObject
            val dt = item.get("dt").asLong
            val itemTime = Instant.ofEpochSecond(dt).atZone(ZoneId.of("Europe/Kyiv"))
            
            if (itemTime.toLocalDate() == today) {
                val tempMax = item.getAsJsonObject("main").get("temp_max").asDouble
                val tempMin = item.getAsJsonObject("main").get("temp_min").asDouble
                
                if (tempMax > maxTemp) maxTemp = tempMax
                if (tempMin < minTemp) minTemp = tempMin
                
                val weather = item.getAsJsonArray("weather").get(0).asJsonObject
                val desc = weather.get("description").asString
                val mainStr = weather.get("main").asString
                
                if (mainStr.equals("Rain", true) || mainStr.equals("Snow", true) || desc.contains("дощ") || desc.contains("сніг")) {
                    willRain = true
                }
                
                // Беремо опис погоди ближче до середини дня (12:00-15:00)
                if (mainDescription.isEmpty() || itemTime.hour in 12..15) {
                    mainDescription = desc
                }
            }
        }

        // Якщо даних на сьогодні вже немає (наприклад, пізній вечір), беремо перші доступні
        if (maxTemp == -100.0) {
            val firstItem = list.get(0).asJsonObject
            maxTemp = firstItem.getAsJsonObject("main").get("temp_max").asDouble
            minTemp = firstItem.getAsJsonObject("main").get("temp_min").asDouble
            mainDescription = firstItem.getAsJsonArray("weather").get(0).asJsonObject.get("description").asString
        }

        val precipText = if (willRain) "☔ Очікуються опади" else "☀️ Без опадів"
        
        "🌤 Погода в Полтаві на день:\n" +
        "🌡 Від ${minTemp.toInt()}°C до ${maxTemp.toInt()}°C, $mainDescription.\n" +
        "$precipText. 🌇 Захід сонця о $sunsetTime."

    } catch (e: Exception) { 
        "Не вдалося дізнатися погоду: ${e.message}" 
    }
}
