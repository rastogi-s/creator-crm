package com.creatorcrm.backup;

import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.security.CryptoService;
import com.creatorcrm.security.Secret;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretRepo;
import com.creatorcrm.security.SecretStore;
import com.creatorcrm.security.SessionEpoch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Whole-install backup and restore: every table plus the stored credentials, in one passphrase-encrypted
 * file. Database-neutral (works H2 &lt;-&gt; PostgreSQL) and master-key-neutral (credentials are re-encrypted
 * with the destination install's key on restore).
 */
@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final int FORMAT = 1;

    /** Parent tables first. Restore inserts in this order and deletes in reverse. */
    static final List<String> TABLES = List.of(
            "users", "settings", "app_state", "brands", "conversations", "messages", "opportunities",
            "tasks", "followups", "deadlines", "drafts", "activity");
    private static final List<String> IDENTITY_TABLES = List.of(
            "users", "brands", "conversations", "messages", "opportunities", "tasks", "followups", "deadlines",
            "drafts", "activity");

    public record Summary(String createdAt, String schemaVersion, Map<String, Integer> rows, int credentials) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SecretRepo secretRepo;
    private final SecretStore secrets;
    private final CryptoService crypto;
    private final IngestionService ingestion;
    private final SessionEpoch sessions;
    private final Flyway flyway;
    private final ObjectMapper json = new ObjectMapper();

    public BackupService(JdbcTemplate jdbc, TransactionTemplate tx, SecretRepo secretRepo, SecretStore secrets,
                         CryptoService crypto, IngestionService ingestion, SessionEpoch sessions, Flyway flyway) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.secretRepo = secretRepo;
        this.secrets = secrets;
        this.crypto = crypto;
        this.ingestion = ingestion;
        this.sessions = sessions;
        this.flyway = flyway;
    }

    // ---------------------------------------------------------------- export

    public byte[] export(char[] passphrase) {
        BackupCrypto.checkPassphrase(passphrase);
        ObjectNode doc = json.createObjectNode();
        doc.put("format", FORMAT);
        doc.put("schemaVersion", schemaVersion());
        doc.put("createdAt", OffsetDateTime.now().toString());
        ObjectNode tables = doc.putObject("tables");
        ingestion.runExclusive(() -> {
            tx.executeWithoutResult(s -> {
                for (String t : TABLES) tables.set(t, dumpTable(t));
                ObjectNode creds = doc.putObject("credentials");
                for (Secret secret : secretRepo.findAll()) {
                    try {
                        creds.put(secret.name, crypto.decrypt(secret.valueEnc));
                    } catch (RuntimeException e) {
                        log.warn("Skipping credential {} in backup: cannot decrypt", secret.name);
                    }
                }
            });
            return null;
        });
        try {
            return BackupCrypto.encrypt(json.writeValueAsBytes(doc), passphrase);
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize backup", e);
        }
    }

    private ObjectNode dumpTable(String table) {
        ObjectNode node = json.createObjectNode();
        ArrayNode columns = node.putArray("columns");
        ArrayNode rows = node.putArray("rows");
        String order = IDENTITY_TABLES.contains(table) ? " ORDER BY id" : "";
        jdbc.query("SELECT * FROM " + table + order, rs -> {
            ResultSetMetaData md = rs.getMetaData();
            if (columns.isEmpty()) {
                for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
            ArrayNode row = rows.addArray();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String v = read(rs, md, i);
                if (v == null) row.addNull(); else row.add(v);
            }
        });
        if (columns.isEmpty()) {
            jdbc.query("SELECT * FROM " + table + " WHERE 1=0", (ResultSet rs) -> {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
                return null;
            });
        }
        return node;
    }

    private static String read(ResultSet rs, ResultSetMetaData md, int i) throws SQLException {
        int type = md.getColumnType(i);
        Object v;
        if (isTimestampTz(md, i)) v = rs.getObject(i, OffsetDateTime.class);
        else if (type == Types.TIMESTAMP) v = rs.getObject(i, LocalDateTime.class);
        else if (type == Types.DATE) v = rs.getObject(i, LocalDate.class);
        else if (type == Types.NUMERIC || type == Types.DECIMAL) v = rs.getBigDecimal(i);
        else v = rs.getObject(i);
        if (v == null) return null;
        return v instanceof BigDecimal b ? b.toPlainString() : v.toString();
    }

    // ---------------------------------------------------------------- restore

    public Summary inspect(byte[] file, char[] passphrase) {
        return summarize(parse(file, passphrase));
    }

    /** Replaces ALL data in this install with the backup. Everyone is signed out afterwards. */
    public Summary restore(byte[] file, char[] passphrase) {
        JsonNode doc = parse(file, passphrase);
        Summary summary = summarize(doc);
        ingestion.runExclusive(() -> {
            tx.executeWithoutResult(s -> {
                for (int i = TABLES.size() - 1; i >= 0; i--) jdbc.update("DELETE FROM " + TABLES.get(i));
                jdbc.update("DELETE FROM secrets");
                for (String t : TABLES) insertTable(t, doc.path("tables").path(t));
                doc.path("credentials").properties().forEach(e -> {
                    try {
                        secrets.put(SecretName.valueOf(e.getKey()), e.getValue().asText());
                    } catch (IllegalArgumentException unknown) {
                        log.warn("Ignoring unknown credential {} in backup", e.getKey());
                    }
                });
            });
            // Make new rows continue after the restored ids.
            for (String t : IDENTITY_TABLES) {
                Long next = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM " + t, Long.class);
                jdbc.execute("ALTER TABLE " + t + " ALTER COLUMN id RESTART WITH " + next);
            }
            return null;
        });
        sessions.signOutEveryone();
        log.warn("Backup from {} restored; all sessions signed out.", summary.createdAt());
        return summary;
    }

    private void insertTable(String table, JsonNode node) {
        if (node.isMissingNode()) return;
        Map<String, int[]> target = targetColumns(table); // name -> {sqlType, isTz}
        List<String> backupCols = new ArrayList<>();
        node.path("columns").forEach(c -> backupCols.add(c.asText()));
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < backupCols.size(); i++) if (target.containsKey(backupCols.get(i))) keep.add(i);
        if (keep.isEmpty()) return;

        String sql = "INSERT INTO " + table + " (" + String.join(", ", keep.stream().map(backupCols::get).toList())
                + ") VALUES (" + String.join(", ", keep.stream().map(i -> "?").toList()) + ")";
        List<Object[]> batch = new ArrayList<>();
        for (JsonNode row : node.path("rows")) {
            Object[] values = new Object[keep.size()];
            for (int k = 0; k < keep.size(); k++) {
                int idx = keep.get(k);
                JsonNode cell = row.path(idx);
                values[k] = cell.isNull() || cell.isMissingNode() ? null : convert(cell.asText(), target.get(backupCols.get(idx)));
            }
            batch.add(values);
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);
    }

    private Map<String, int[]> targetColumns(String table) {
        Map<String, int[]> cols = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM " + table + " WHERE 1=0", (ResultSet rs) -> {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                cols.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), new int[] {md.getColumnType(i), isTimestampTz(md, i) ? 1 : 0});
            }
            return null;
        });
        return cols;
    }

    private static Object convert(String v, int[] type) {
        if (type[1] == 1) return OffsetDateTime.parse(v);
        return switch (type[0]) {
            case Types.BIGINT -> Long.valueOf(v);
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.valueOf(v);
            case Types.BOOLEAN, Types.BIT -> Boolean.valueOf(v);
            case Types.NUMERIC, Types.DECIMAL -> new BigDecimal(v);
            case Types.DATE -> LocalDate.parse(v);
            case Types.TIMESTAMP -> LocalDateTime.parse(v);
            default -> v;
        };
    }

    /** H2 reports TIMESTAMP_WITH_TIMEZONE; PostgreSQL reports TIMESTAMP with type name "timestamptz". */
    private static boolean isTimestampTz(ResultSetMetaData md, int i) throws SQLException {
        int type = md.getColumnType(i);
        String name = md.getColumnTypeName(i).toLowerCase(Locale.ROOT);
        return type == Types.TIMESTAMP_WITH_TIMEZONE || (type == Types.TIMESTAMP && (name.contains("tz") || name.contains("time zone")));
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode parse(byte[] file, char[] passphrase) {
        JsonNode doc;
        try {
            doc = json.readTree(BackupCrypto.decrypt(file, passphrase));
        } catch (IOException e) {
            throw new IllegalArgumentException("The backup file is damaged.");
        }
        if (doc.path("format").asInt() != FORMAT) {
            throw new IllegalArgumentException("Unsupported backup format " + doc.path("format").asText());
        }
        String backupSchema = doc.path("schemaVersion").asText("0");
        if (MigrationVersion.fromVersion(backupSchema).isNewerThan(schemaVersion())) {
            throw new IllegalArgumentException("This backup was made by a newer version of Creator CRM (schema "
                    + backupSchema + "). Update this install first.");
        }
        return doc;
    }

    private static Summary summarize(JsonNode doc) {
        Map<String, Integer> rows = new LinkedHashMap<>();
        for (String t : TABLES) rows.put(t, doc.path("tables").path(t).path("rows").size());
        return new Summary(doc.path("createdAt").asText(), doc.path("schemaVersion").asText(), rows,
                doc.path("credentials").size());
    }

    private String schemaVersion() {
        var current = flyway.info().current();
        return current == null ? "0" : current.getVersion().getVersion();
    }
}
