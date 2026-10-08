import com.google.gson.*;
import java.net.URI;
import java.net.http.*;

public class AiTest {
    static final String MODEL = "gemini-3.8-flash";

    public static void main(String[] args) throws Exception {
        String key = System.getenv("AI_API_KEY");
        if (key == null) {
            System.out.println("Key not found");
            return;
        }

        JsonObject part = new JsonObject();
        part.addProperty("text", "Explain what Java is in one simple sentence.");
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject body = new JsonObject();
        body.add("contents", contents);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> res = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200) {
            System.out.println("Error " + res.statusCode());
            System.out.println(res.body());
            return;
        }

        String text = JsonParser.parseString(res.body()).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("content")
                .getAsJsonArray("parts").get(0).getAsJsonObject()
                .get("text").getAsString();
        System.out.println("AI says: " + text);
    }
}
