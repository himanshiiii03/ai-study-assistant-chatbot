import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;

public class Server {
    static final String MODEL ="gemini-3.5-flash-lite";
    static final String DB_URL = "jdbc:sqlite:../database/chat.db";
    static final String API_KEY = System.getenv("AI_API_KEY");
    static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        if (API_KEY == null) {
            System.out.println("Key not found. Set AI_API_KEY first.");
            return;
        }
        initDb();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/chat", Server::handleChat);
        server.createContext("/api/history", Server::handleHistory);
        server.createContext("/", Server::handleStatic);
        server.start();
        System.out.println("Server running at http://localhost:8080");
        Thread.currentThread().join();
    }

    static void initDb() throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS messages ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "role TEXT NOT NULL, "
                    + "content TEXT NOT NULL, "
                    + "created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    static void saveMessage(String role, String content) throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement("INSERT INTO messages (role, content) VALUES (?, ?)")) {
            ps.setString(1, role);
            ps.setString(2, content);
            ps.executeUpdate();
        }
    }

    static void handleChat(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("POST")) {
                send(ex, 405, "{\"error\":\"Use POST\"}", "application/json");
                return;
            }
            String bodyStr = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String message = JsonParser.parseString(bodyStr).getAsJsonObject().get("message").getAsString().trim();
            if (message.isEmpty()) {
                send(ex, 400, "{\"error\":\"Empty message\"}", "application/json");
                return;
            }
            saveMessage("user", message);
            String reply = askAi();
            saveMessage("model", reply);
            JsonObject out = new JsonObject();
            out.addProperty("reply", reply);
            send(ex, 200, out.toString(), "application/json");
        } catch (Exception e) {
            e.printStackTrace();
            JsonObject out = new JsonObject();
            out.addProperty("error", "Something went wrong: " + e.getMessage());
            send(ex, 500, out.toString(), "application/json");
        }
    }

    static void handleHistory(HttpExchange ex) throws IOException {
        try {
            JsonArray list = new JsonArray();
            try (Connection c = DriverManager.getConnection(DB_URL);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT role, content FROM messages ORDER BY id ASC")) {
                while (rs.next()) {
                    JsonObject m = new JsonObject();
                    m.addProperty("role", rs.getString("role"));
                    m.addProperty("content", rs.getString("content"));
                    list.add(m);
                }
            }
            send(ex, 200, list.toString(), "application/json");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "{\"error\":\"Could not read history\"}", "application/json");
        }
    }

    static String askAi() throws Exception {
        JsonArray contents = new JsonArray();
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT role, content FROM (SELECT * FROM messages ORDER BY id DESC LIMIT 10) ORDER BY id ASC")) {
            while (rs.next()) {
                JsonObject part = new JsonObject();
                part.addProperty("text", rs.getString("content"));
                JsonArray parts = new JsonArray();
                parts.add(part);
                JsonObject item = new JsonObject();
                item.addProperty("role", rs.getString("role"));
                item.add("parts", parts);
                contents.add(item);
            }
        }
        while (contents.size() > 0
                && contents.get(0).getAsJsonObject().get("role").getAsString().equals("model")) {
            contents.remove(0);
        }

        JsonObject sysPart = new JsonObject();
        sysPart.addProperty("text",
                "You are a friendly study assistant. Explain things simply with short examples. Keep answers under 150 words.");
        JsonArray sysParts = new JsonArray();
        sysParts.add(sysPart);
        JsonObject sys = new JsonObject();
        sys.add("parts", sysParts);

        JsonObject body = new JsonObject();
        body.add("systemInstruction", sys);
        body.add("contents", contents);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

              HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        for (int i = 0; i < 3 && (res.statusCode() == 503 || res.statusCode() == 429); i++) {
            Thread.sleep(3000);
            res = client.send(req, HttpResponse.BodyHandlers.ofString());
        }
        if (res.statusCode() != 200) {
            throw new Exception("AI error " + res.statusCode() + ": " + res.body());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("content")
                .getAsJsonArray("parts").get(0).getAsJsonObject()
                .get("text").getAsString();
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path base = Paths.get("../frontend").toAbsolutePath().normalize();
        Path file = base.resolve(path.substring(1)).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            send(ex, 404, "Page not found. Create frontend/index.html first.", "text/plain");
            return;
        }
        String name = file.toString();
        String type = "text/plain";
        if (name.endsWith(".html")) type = "text/html";
        else if (name.endsWith(".css")) type = "text/css";
        else if (name.endsWith(".js")) type = "application/javascript";
        send(ex, 200, new String(Files.readAllBytes(file), StandardCharsets.UTF_8), type);
    }

    static void send(HttpExchange ex, int code, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
