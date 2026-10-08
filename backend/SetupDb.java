import java.sql.*;

public class SetupDb {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:sqlite:../database/chat.db";
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS messages ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "role TEXT NOT NULL, "
                    + "content TEXT NOT NULL, "
                    + "created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM messages");
            rs.next();
            System.out.println("Database ready. Rows: " + rs.getInt(1));
        }
    }
}
