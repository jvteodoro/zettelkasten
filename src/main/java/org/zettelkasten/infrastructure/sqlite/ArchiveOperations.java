package org.zettelkasten.infrastructure.sqlite;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.zettelkasten.application.PersistenceException;

/** Local archive maintenance and deterministic, versioned JSONL portability. */
public final class ArchiveOperations {
  private static final JsonFactory JSON = JsonFactory.builder().build();
  private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
  private static final List<ExportFile> EXPORT_FILES =
      List.of(
          new ExportFile("cards.jsonl", List.of("cards", "run_containers", "card_runs")),
          new ExportFile("placements.jsonl", List.of("placements")),
          new ExportFile("references.jsonl", List.of("refs")),
          new ExportFile(
              "index.jsonl",
              List.of(
                  "index_list_cards",
                  "index_keyterm_cards",
                  "index_entries",
                  "keyterm_entries",
                  "index_promotions")),
          new ExportFile(
              "bibliography.jsonl",
              List.of(
                  "bibliographic_sources",
                  "source_external_ids",
                  "bibliographic_source_fields",
                  "bibcards",
                  "bibnotes",
                  "import_batches",
                  "import_entries")),
          new ExportFile("events.jsonl", List.of("domain_events")));

  private static final List<String> INSERT_ORDER =
      List.of(
          "bibliographic_sources",
          "source_external_ids",
          "bibliographic_source_fields",
          "index_list_cards",
          "index_keyterm_cards",
          "index_entries",
          "keyterm_entries",
          "index_promotions",
          "bibcards",
          "bibnotes",
          "import_batches",
          "import_entries",
          "refs",
          "domain_events");

  private final SqliteDatabase database;

  public ArchiveOperations(SqliteDatabase database) {
    this.database = Objects.requireNonNull(database, "database");
  }

  public DoctorReport doctor() {
    try (Connection connection = database.newConnection()) {
      List<String> problems = new ArrayList<>();
      String integrity = "";
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
        while (result.next()) {
          String row = result.getString(1);
          if (!"ok".equalsIgnoreCase(row)) problems.add("integrity_check: " + row);
          if (integrity.isEmpty()) integrity = row;
        }
      }
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
        while (result.next())
          problems.add(
              "foreign_key_check: table="
                  + result.getString(1)
                  + ", rowid="
                  + result.getString(2)
                  + ", parent="
                  + result.getString(3));
      }
      long previous = 0;
      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery("SELECT global_seq FROM domain_events ORDER BY global_seq")) {
        while (result.next()) {
          long current = result.getLong(1);
          if (current <= previous) problems.add("domain event sequence is not strictly increasing");
          previous = current;
        }
      }
      int schema = new MigrationRunner().currentVersion(connection);
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("PRAGMA user_version")) {
        if (!result.next() || result.getInt(1) != schema)
          problems.add("SQLite user_version does not match schema_migrations");
      }
      return new DoctorReport(
          problems.isEmpty(), schema, integrity, previous, List.copyOf(problems));
    } catch (SQLException exception) {
      throw new PersistenceException("Could not inspect archive integrity", exception);
    }
  }

  /** Creates a complete consistent snapshot through SQLite VACUUM INTO, never by raw copy. */
  public Path backup(Path requestedOutput) {
    return backupExisting(database.path(), requestedOutput);
  }

  /** Writes deterministic JSONL data files and a checksummed manifest for one SQLite snapshot. */
  public Path export(Path requestedDirectory) {
    Path directory = normalizeTarget(requestedDirectory);
    refuseExisting(directory);
    Path temporary = temporarySibling(directory);
    try {
      Files.createDirectory(temporary);
      setPrivatePermissions(temporary, "rwx------");
      Map<String, FileDigest> digests = new LinkedHashMap<>();
      try (Connection connection = database.newConnection()) {
        connection.setAutoCommit(false);
        for (ExportFile file : EXPORT_FILES) {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          long count = 0;
          try (JsonGenerator generator = JSON.createGenerator(bytes)) {
            for (String table : file.tables()) count += writeTable(connection, generator, table);
          }
          byte[] content = bytes.toByteArray();
          Files.write(
              temporary.resolve(file.name()),
              content,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE);
          setPrivatePermissions(temporary.resolve(file.name()), "rw-------");
          digests.put(file.name(), new FileDigest(count, sha256(content)));
        }
        connection.commit();
      }
      writeManifest(temporary, database.schemaVersion(), digests);
      setPrivatePermissions(temporary.resolve("manifest.json"), "rw-------");
      forceDirectory(temporary);
      moveNew(temporary, directory);
      forceDirectory(directory.getParent());
      return directory;
    } catch (SQLException | IOException exception) {
      deleteTreeQuietly(temporary);
      throw new PersistenceException("Could not export portable archive", exception);
    }
  }

  /** Validates every byte and row before creating a new destination archive. */
  public static SqliteDatabase importArchive(Path requestedDirectory, Path requestedDatabase) {
    Path directory = normalizeExistingDirectory(requestedDirectory);
    Path destination = normalizeTarget(requestedDatabase);
    refuseExisting(destination);
    try {
      ValidatedArchive archive = validateArchive(directory);
      SqliteDatabase created = SqliteDatabase.create(destination);
      try {
        loadArchive(created, archive);
        DoctorReport report = new ArchiveOperations(created).doctor();
        if (!report.healthy())
          throw new PersistenceException("Imported archive failed doctor: " + report.problems());
        return created;
      } catch (RuntimeException exception) {
        deleteDatabaseFiles(destination);
        throw exception;
      }
    } catch (IOException | SQLException exception) {
      throw new PersistenceException(
          "Portable archive preflight failed; destination was untouched", exception);
    }
  }

  public static List<String> pendingMigrations(Path databasePath) {
    return new MigrationRunner()
        .pendingDescriptions(SqliteDatabase.schemaVersionWithoutMigrating(databasePath));
  }

  public static int schemaVersion(Path databasePath) {
    return SqliteDatabase.schemaVersionWithoutMigrating(databasePath);
  }

  /** Writes a safety copy without opening the source through auto-migrating code. */
  public static Path backupBeforeMigration(Path databasePath, Path requestedOutput) {
    return backupExisting(databasePath, requestedOutput);
  }

  public static Path backupExisting(Path databasePath, Path requestedOutput) {
    Path source = databasePath.toAbsolutePath().normalize();
    Path output = normalizeTarget(requestedOutput);
    refuseExisting(output);
    Path temporary = temporarySibling(output);
    try {
      if (Files.isSymbolicLink(source) || !Files.isRegularFile(source))
        throw new PersistenceException("Source archive is not a regular file");
      try (Connection connection = DriverManagerConnections.open(source);
          PreparedStatement vacuum = connection.prepareStatement("VACUUM INTO ?")) {
        vacuum.setString(1, temporary.toString());
        vacuum.execute();
      }
      verifyDatabase(temporary);
      setPrivatePermissions(temporary, "rw-------");
      forceFile(temporary);
      moveNew(temporary, output);
      forceDirectory(output.getParent());
      return output;
    } catch (SQLException | IOException exception) {
      deleteQuietly(temporary);
      throw new PersistenceException("Could not create consistent SQLite backup", exception);
    }
  }

  private static long writeTable(Connection connection, JsonGenerator generator, String table)
      throws SQLException, IOException {
    long count = 0;
    try (Statement query = connection.createStatement();
        ResultSet rows =
            query.executeQuery(
                "SELECT * FROM \"" + table + "\" ORDER BY " + primaryKeyOrder(connection, table))) {
      var metadata = rows.getMetaData();
      while (rows.next()) {
        generator.writeStartObject();
        generator.writeStringField("table", table);
        generator.writeObjectFieldStart("row");
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
          String name = metadata.getColumnLabel(column);
          Object value = rows.getObject(column);
          if (value == null) generator.writeNullField(name);
          else if (value instanceof Number number)
            generator.writeNumberField(name, number.longValue());
          else generator.writeStringField(name, value.toString());
        }
        generator.writeEndObject();
        generator.writeEndObject();
        generator.writeRaw('\n');
        count++;
      }
    }
    return count;
  }

  private static String primaryKeyOrder(Connection connection, String table) throws SQLException {
    List<String> columns = new ArrayList<>();
    try (Statement query = connection.createStatement();
        ResultSet rows = query.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
      while (rows.next()) {
        int ordinal = rows.getInt("pk");
        if (ordinal > 0) columns.add(ordinal + ":\"" + rows.getString("name") + "\"");
      }
    }
    if (columns.isEmpty()) throw new SQLException("Table has no canonical key: " + table);
    columns.sort(String::compareTo);
    return columns.stream()
        .map(column -> column.substring(column.indexOf(':') + 1))
        .reduce((a, b) -> a + "," + b)
        .orElseThrow();
  }

  private static void writeManifest(Path directory, int schema, Map<String, FileDigest> digests)
      throws IOException {
    Path manifest = directory.resolve("manifest.json");
    try (var output = Files.newOutputStream(manifest, StandardOpenOption.CREATE_NEW);
        JsonGenerator json = JSON.createGenerator(output)) {
      json.useDefaultPrettyPrinter();
      json.writeStartObject();
      json.writeStringField("format", "zettelkasten-jsonl");
      json.writeNumberField("formatVersion", 1);
      json.writeStringField("appVersion", "0.1.0-SNAPSHOT");
      json.writeNumberField("schemaVersion", schema);
      json.writeNumberField(
          "itemCount",
          digests.entrySet().stream()
              .filter(entry -> !entry.getKey().equals("events.jsonl"))
              .mapToLong(entry -> entry.getValue().count())
              .sum());
      json.writeNumberField(
          "eventCount", digests.getOrDefault("events.jsonl", new FileDigest(0, "")).count());
      json.writeArrayFieldStart("addressSchemes");
      json.writeString("ANTINET_SLASH_V1");
      json.writeEndArray();
      json.writeObjectFieldStart("files");
      for (ExportFile file : EXPORT_FILES) {
        FileDigest digest = digests.get(file.name());
        json.writeObjectFieldStart(file.name());
        json.writeNumberField("count", digest.count());
        json.writeStringField("sha256", digest.sha256());
        json.writeEndObject();
      }
      json.writeEndObject();
      json.writeEndObject();
    }
  }

  private static ValidatedArchive validateArchive(Path directory) throws IOException, SQLException {
    Path manifestPath = directory.resolve("manifest.json");
    if (Files.isSymbolicLink(manifestPath) || !Files.isRegularFile(manifestPath))
      throw new IOException("manifest.json must be a regular file");
    if (Files.size(manifestPath) > 1024 * 1024)
      throw new IOException("manifest.json exceeds the 1 MiB limit");
    Manifest manifest = readManifest(manifestPath);
    if (!"zettelkasten-jsonl".equals(manifest.format()) || manifest.version() != 1)
      throw new IOException("Unsupported portable archive format/version");
    if (manifest.schemaVersion() != MigrationRunner.CURRENT_VERSION)
      throw new IOException("Archive schema version is not supported by this application");
    if (!manifest
        .digests()
        .keySet()
        .equals(
            EXPORT_FILES.stream()
                .map(ExportFile::name)
                .collect(java.util.stream.Collectors.toSet())))
      throw new IOException("Manifest must name every canonical data file exactly once");
    long total = 0;
    Map<String, List<Row>> records = new LinkedHashMap<>();
    try (Connection schema = DriverManagerConnections.temporarySchema()) {
      for (ExportFile file : EXPORT_FILES) {
        Path path = directory.resolve(file.name());
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path))
          throw new IOException("Archive data file must be regular: " + file.name());
        long size = Files.size(path);
        total += size;
        if (total > MAX_ARCHIVE_BYTES) throw new IOException("Archive exceeds 256 MiB limit");
        byte[] bytes = Files.readAllBytes(path);
        FileDigest expected = manifest.digests().get(file.name());
        if (!sha256(bytes).equals(expected.sha256()))
          throw new IOException("Checksum mismatch: " + file.name());
        List<Row> fileRows = parseRows(bytes, file, schema);
        if (fileRows.size() != expected.count())
          throw new IOException("Record count mismatch: " + file.name());
        records.put(file.name(), fileRows);
      }
    }
    return new ValidatedArchive(manifest.schemaVersion(), records);
  }

  private static List<Row> parseRows(byte[] bytes, ExportFile file, Connection schema)
      throws IOException, SQLException {
    List<Row> rows = new ArrayList<>();
    String content;
    try {
      content =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
              .decode(java.nio.ByteBuffer.wrap(bytes))
              .toString();
    } catch (java.nio.charset.CharacterCodingException exception) {
      throw new IOException("Archive data is not valid UTF-8", exception);
    }
    for (String line : content.split("\\n", -1)) {
      if (line.isEmpty()) continue;
      try (var input = JSON.createParser(line)) {
        if (input.nextToken() != JsonToken.START_OBJECT)
          throw new IOException("JSONL row must be an object");
        String table = null;
        Map<String, Object> values = null;
        Set<String> fields = new java.util.HashSet<>();
        while (input.nextToken() != JsonToken.END_OBJECT) {
          String field = input.currentName();
          if (!fields.add(field)) throw new IOException("Duplicate JSONL field: " + field);
          JsonToken token = input.nextToken();
          if ("table".equals(field) && token == JsonToken.VALUE_STRING) table = input.getText();
          else if ("row".equals(field) && token == JsonToken.START_OBJECT)
            values = readObject(input);
          else throw new IOException("Unknown or malformed JSONL field");
        }
        if (table == null || values == null || file.tables().stream().noneMatch(table::equals))
          throw new IOException("JSONL table is not declared in " + file.name());
        validateColumns(schema, table, values);
        rows.add(new Row(table, values));
        if (input.nextToken() != null)
          throw new IOException("Data files require one JSON object per line");
      }
    }
    return List.copyOf(rows);
  }

  private static Map<String, Object> readObject(JsonParser input) throws IOException {
    Map<String, Object> values = new LinkedHashMap<>();
    while (input.nextToken() != JsonToken.END_OBJECT) {
      String key = input.currentName();
      JsonToken token = input.nextToken();
      Object value =
          switch (token) {
            case VALUE_NULL -> null;
            case VALUE_STRING -> input.getText();
            case VALUE_NUMBER_INT -> input.getLongValue();
            default ->
                throw new IOException("Canonical row values must be strings, integers, or null");
          };
      if (values.containsKey(key)) throw new IOException("Duplicate JSON field: " + key);
      values.put(key, value);
    }
    return values;
  }

  private static void validateColumns(
      Connection connection, String table, Map<String, Object> values)
      throws SQLException, IOException {
    Set<String> columns = new java.util.HashSet<>();
    try (Statement query = connection.createStatement();
        ResultSet rows = query.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
      while (rows.next()) columns.add(rows.getString("name"));
    }
    if (!columns.equals(values.keySet()))
      throw new IOException("Unknown/missing columns for table " + table);
  }

  private static Manifest readManifest(Path path) throws IOException {
    try (JsonParser json = JSON.createParser(path.toFile())) {
      String format = null;
      String appVersion = null;
      int version = -1, schema = -1;
      long itemCount = -1, eventCount = -1;
      List<String> schemes = new ArrayList<>();
      Map<String, FileDigest> digests = new LinkedHashMap<>();
      Set<String> fields = new java.util.HashSet<>();
      if (json.nextToken() != JsonToken.START_OBJECT)
        throw new IOException("Manifest must be an object");
      while (json.nextToken() != JsonToken.END_OBJECT) {
        String field = json.currentName();
        if (!fields.add(field)) throw new IOException("Duplicate manifest field: " + field);
        JsonToken token = json.nextToken();
        switch (field) {
          case "format" -> format = json.getValueAsString();
          case "formatVersion" -> version = json.getIntValue();
          case "appVersion" -> appVersion = json.getValueAsString();
          case "schemaVersion" -> schema = json.getIntValue();
          case "itemCount" -> itemCount = json.getLongValue();
          case "eventCount" -> eventCount = json.getLongValue();
          case "addressSchemes" -> {
            if (token != JsonToken.START_ARRAY)
              throw new IOException("Manifest addressSchemes must be an array");
            while (json.nextToken() != JsonToken.END_ARRAY) {
              if (json.currentToken() != JsonToken.VALUE_STRING)
                throw new IOException("Invalid address scheme entry");
              schemes.add(json.getText());
            }
          }
          case "files" -> {
            if (token != JsonToken.START_OBJECT)
              throw new IOException("Manifest files must be an object");
            while (json.nextToken() != JsonToken.END_OBJECT) {
              String filename = json.currentName();
              if (json.nextToken() != JsonToken.START_OBJECT)
                throw new IOException("Invalid file manifest entry");
              long count = -1;
              String hash = null;
              while (json.nextToken() != JsonToken.END_OBJECT) {
                String property = json.currentName();
                json.nextToken();
                if ("count".equals(property)) count = json.getLongValue();
                else if ("sha256".equals(property)) hash = json.getValueAsString();
                else throw new IOException("Unknown manifest file property");
              }
              if (count < 0
                  || hash == null
                  || !hash.matches("[0-9a-f]{64}")
                  || digests.put(filename, new FileDigest(count, hash)) != null)
                throw new IOException("Invalid manifest file digest");
            }
          }
          default -> throw new IOException("Unknown manifest field: " + field);
        }
      }
      if (json.nextToken() != null) throw new IOException("Trailing manifest content");
      long calculatedItems =
          digests.entrySet().stream()
              .filter(entry -> !entry.getKey().equals("events.jsonl"))
              .mapToLong(entry -> entry.getValue().count())
              .sum();
      if (appVersion == null
          || itemCount != calculatedItems
          || eventCount < 0
          || !schemes.equals(List.of("ANTINET_SLASH_V1")))
        throw new IOException("Manifest summary counts or address scheme registry are invalid");
      if (eventCount != digests.getOrDefault("events.jsonl", new FileDigest(-1, "")).count())
        throw new IOException("Manifest event count does not match the event file count");
      return new Manifest(format, version, schema, digests);
    }
  }

  private static void loadArchive(SqliteDatabase database, ValidatedArchive archive) {
    try (Connection connection = database.newConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("DROP TRIGGER placement_neighbor_state_guard");
      }
      List<Row> allRows = archive.files().values().stream().flatMap(List::stream).toList();
      Set<String> originalContainers =
          allRows.stream()
              .filter(row -> row.table().equals("run_containers"))
              .filter(row -> "ORIGINAL".equals(row.values().get("container_kind")))
              .map(row -> row.values().get("id").toString())
              .collect(java.util.stream.Collectors.toSet());
      Set<String> annotationContainers =
          allRows.stream()
              .filter(row -> row.table().equals("run_containers"))
              .filter(row -> "ANNOTATION".equals(row.values().get("container_kind")))
              .map(row -> row.values().get("id").toString())
              .collect(java.util.stream.Collectors.toSet());
      insertTableRows(connection, allRows, "cards", ignored -> true);
      insertTableRows(
          connection,
          allRows,
          "run_containers",
          row -> "ORIGINAL".equals(row.values().get("container_kind")));
      insertTableRows(
          connection,
          allRows,
          "card_runs",
          row -> originalContainers.contains(row.values().get("container_id").toString()));
      insertTableRows(connection, allRows, "placements", ignored -> true);
      insertTableRows(
          connection,
          allRows,
          "run_containers",
          row -> "ANNOTATION".equals(row.values().get("container_kind")));
      insertTableRows(
          connection,
          allRows,
          "card_runs",
          row -> annotationContainers.contains(row.values().get("container_id").toString()));
      for (String table : INSERT_ORDER) {
        insertTableRows(connection, allRows, table, ignored -> true);
      }
      restoreDiscardTombstones(connection, allRows);
      try (Statement statement = connection.createStatement()) {
        statement.execute(
            """
            CREATE TRIGGER placement_neighbor_state_guard
            BEFORE INSERT ON placements
            WHEN (NEW.predecessor_card_id IS NOT NULL AND NOT EXISTS
                  (SELECT 1 FROM placements WHERE card_id = NEW.predecessor_card_id))
              OR (NEW.successor_card_id IS NOT NULL AND NOT EXISTS
                  (SELECT 1 FROM placements WHERE card_id = NEW.successor_card_id))
            BEGIN
              SELECT RAISE(ABORT, 'placement neighbor must already be installed');
            END
            """);
      }
      connection.commit();
    } catch (SQLException exception) {
      throw new PersistenceException("Could not import portable archive", exception);
    }
  }

  private static void insertTableRows(
      Connection connection, List<Row> rows, String table, java.util.function.Predicate<Row> filter)
      throws SQLException {
    for (Row row : rows)
      if (row.table().equals(table) && filter.test(row)) insertRow(connection, row);
  }

  private static void insertRow(Connection connection, Row row) throws SQLException {
    List<String> columns = new ArrayList<>(row.values().keySet());
    String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
    String names =
        columns.stream()
            .map(name -> "\"" + name + "\"")
            .reduce((a, b) -> a + "," + b)
            .orElseThrow();
    String sql = "INSERT INTO \"" + row.table() + "\"(" + names + ") VALUES(" + placeholders + ")";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      for (int i = 0; i < columns.size(); i++) {
        Object value = row.values().get(columns.get(i));
        if (row.table().equals("cards")
            && "MAIN".equals(row.values().get("kind"))
            && "INSTALLED".equals(row.values().get("state"))) {
          if ("state".equals(columns.get(i))) value = "DRAFT";
          if ("installed_at".equals(columns.get(i))
              || "installed_body_revision".equals(columns.get(i))) value = null;
        }
        if (row.table().equals("cards") && "discarded_at".equals(columns.get(i))) value = null;
        if (value == null) insert.setObject(i + 1, null);
        else insert.setObject(i + 1, value);
      }
      insert.executeUpdate();
    }
  }

  private static void restoreDiscardTombstones(Connection connection, List<Row> rows)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE cards SET discarded_at=? WHERE id=? AND kind='MAIN' AND discarded_at IS NULL")) {
      for (Row row : rows) {
        if (!row.table().equals("cards") || row.values().get("discarded_at") == null) continue;
        update.setObject(1, row.values().get("discarded_at"));
        update.setObject(2, row.values().get("id"));
        if (update.executeUpdate() != 1)
          throw new SQLException("Could not restore draft tombstone");
      }
    }
  }

  private static void verifyDatabase(Path path) throws IOException, SQLException {
    try (Connection connection = DriverManagerConnections.open(path)) {
      try (Statement statement = connection.createStatement();
          ResultSet row = statement.executeQuery("PRAGMA integrity_check")) {
        if (!row.next() || !"ok".equalsIgnoreCase(row.getString(1)))
          throw new IOException("Backup integrity check failed");
      }
      try (Statement statement = connection.createStatement();
          ResultSet row = statement.executeQuery("PRAGMA foreign_key_check")) {
        if (row.next()) throw new IOException("Backup contains foreign key violations");
      }
      if (tableExists(connection, "schema_migrations")) {
        int version = new MigrationRunner().currentVersion(connection);
        try (Statement statement = connection.createStatement();
            ResultSet row = statement.executeQuery("PRAGMA user_version")) {
          if (!row.next() || row.getInt(1) != version)
            throw new IOException("Backup schema version metadata is inconsistent");
        }
      }
      if (tableExists(connection, "domain_events")) {
        long previous = 0;
        try (Statement statement = connection.createStatement();
            ResultSet rows =
                statement.executeQuery(
                    "SELECT global_seq FROM domain_events ORDER BY global_seq")) {
          while (rows.next()) {
            long current = rows.getLong(1);
            if (current <= previous) throw new IOException("Backup event sequence is invalid");
            previous = current;
          }
        }
      }
    }
  }

  private static boolean tableExists(Connection connection, String table) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
      query.setString(1, table);
      try (ResultSet row = query.executeQuery()) {
        return row.next();
      }
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Path normalizeTarget(Path path) {
    if (path == null || path.getFileName() == null)
      throw new IllegalArgumentException("Output path is required");
    Path normalized = path.toAbsolutePath().normalize();
    Path parent = normalized.getParent();
    if (parent == null || !Files.isDirectory(parent) || containsSymbolicLink(normalized))
      throw new PersistenceException(
          "Output parent must exist and output cannot be a symbolic link");
    return normalized;
  }

  private static boolean containsSymbolicLink(Path path) {
    Path current = path.getRoot();
    for (Path component : path) {
      current = current == null ? component : current.resolve(component);
      if (Files.isSymbolicLink(current)) return true;
    }
    return false;
  }

  private static Path normalizeExistingDirectory(Path path) {
    if (path == null) throw new IllegalArgumentException("Archive directory is required");
    Path normalized = path.toAbsolutePath().normalize();
    if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized))
      throw new PersistenceException("Archive directory must be a regular directory");
    return normalized;
  }

  private static void refuseExisting(Path path) {
    if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
      throw new PersistenceException("Refusing to overwrite existing output: " + path);
  }

  private static Path temporarySibling(Path path) {
    return path.resolveSibling(
        "." + path.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
  }

  private static void moveNew(Path source, Path destination) throws IOException {
    Files.move(source, destination);
  }

  private static void forceFile(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private static void setPrivatePermissions(Path path, String permissions) {
    try {
      Files.setPosixFilePermissions(
          path, java.nio.file.attribute.PosixFilePermissions.fromString(permissions));
    } catch (IOException | UnsupportedOperationException ignored) {
      // Keep the native filesystem policy where POSIX modes are unavailable.
    }
  }

  private static void forceDirectory(Path path) {
    if (path == null) return;
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException | UnsupportedOperationException ignored) {
      /* Best effort on non-POSIX filesystems. */
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }

  private static void deleteTreeQuietly(Path path) {
    if (!Files.exists(path)) return;
    try (var paths = Files.walk(path)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(ArchiveOperations::deleteQuietly);
    } catch (IOException ignored) {
    }
  }

  private static void deleteDatabaseFiles(Path path) {
    deleteQuietly(path.resolveSibling(path.getFileName() + "-wal"));
    deleteQuietly(path.resolveSibling(path.getFileName() + "-shm"));
    deleteQuietly(path);
  }

  private record ExportFile(String name, List<String> tables) {}

  private record FileDigest(long count, String sha256) {}

  private record Manifest(
      String format, int version, int schemaVersion, Map<String, FileDigest> digests) {}

  private record ValidatedArchive(int schemaVersion, Map<String, List<Row>> files) {}

  private record Row(String table, Map<String, Object> values) {}

  public record DoctorReport(
      boolean healthy,
      int schemaVersion,
      String integrity,
      long lastEventSequence,
      List<String> problems) {}
}
