import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.flywaydb.core.Flyway;

final class ReceiverStore {
    private final String url;
    private final Properties credentials;

    ReceiverStore(String url, String username, String password) {
        this.url = url;
        this.credentials = new Properties();
        credentials.setProperty("user", username);
        credentials.setProperty("password", password);
        credentials.setProperty("connectTimeout", "2");
        credentials.setProperty("socketTimeout", "5");
    }

    void migrate() {
        Flyway.configure().dataSource(url, credentials.getProperty("user"), credentials.getProperty("password"))
                .schemas("demo_receiver").defaultSchema("demo_receiver")
                .locations("classpath:receiver-db").cleanDisabled(true).validateMigrationNaming(true).load().migrate();
    }

    Receipt accept(String deliveryId, String payload) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout='2s'");
                    statement.execute("SET LOCAL lock_timeout='2s'");
                }
                try (var statement = connection.prepareStatement("SELECT jsonb_typeof(CAST(? AS jsonb))")) {
                    statement.setString(1, payload);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || !"object".equals(rows.getString(1))) throw new InvalidPayload();
                    }
                }
                int inserted;
                try (var statement = connection.prepareStatement("INSERT INTO demo_receiver.receipts(delivery_id,payload) VALUES (?,CAST(? AS jsonb)) ON CONFLICT(delivery_id) DO NOTHING")) {
                    statement.setString(1, deliveryId);
                    statement.setString(2, payload);
                    inserted = statement.executeUpdate();
                }
                int count = 1;
                if (inserted == 1) {
                    try (var statement = connection.prepareStatement("INSERT INTO demo_receiver.business_effects(delivery_id) VALUES (?)")) {
                        statement.setString(1, deliveryId);
                        statement.executeUpdate();
                    }
                } else {
                    try (var statement = connection.prepareStatement("SELECT payload=CAST(? AS jsonb) FROM demo_receiver.receipts WHERE delivery_id=?")) {
                        statement.setString(1, payload);
                        statement.setString(2, deliveryId);
                        try (var rows = statement.executeQuery()) {
                            if (!rows.next() || !rows.getBoolean(1)) throw new Conflict();
                        }
                    }
                    try (var statement = connection.prepareStatement("UPDATE demo_receiver.receipts SET receipt_count=receipt_count+1 WHERE delivery_id=? RETURNING receipt_count")) {
                        statement.setString(1, deliveryId);
                        try (var rows = statement.executeQuery()) { rows.next(); count = rows.getInt(1); }
                    }
                }
                connection.commit();
                return new Receipt(inserted == 0, count);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                if (exception instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("22")) throw new InvalidPayload();
                throw exception;
            }
        }
    }

    record Receipt(boolean duplicate, int receiptCount) { }
    static final class Conflict extends RuntimeException { }
    static final class InvalidPayload extends RuntimeException { }
}
