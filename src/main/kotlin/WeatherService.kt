import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

fun getPoltavaWeather(): String {
    val apiKey = System.getenv("WEATHER_API_KEY") ?: return "Погода: API ключ не знайдено."
    val url = "https://api.openweathermap.org/data/2.5/weather?q=Poltava,ua&appid=$apiKey&units=metric&lang=uk"
    return try {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JsonParser.parseString(response.body()).asJsonObject
        val temp = json.getAsJsonObject("main").get("temp").asDouble.toInt()
        val desc = json.getAsJsonArray("weather").get(0).asJsonObject.get("description").asString
        "🌤 Погода в Полтаві: ${temp}°C, $desc."
    } catch (e: Exception) { "Не вдалося дізнатися погоду." }
}
