package org.zettelkasten.infrastructure.sqlite;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Objects;
import org.zettelkasten.application.PersistenceException;

/** Deterministic, read-only extraction of authored and explicitly derived relationships. */
public final class GraphProjectionExporter {
  private static final JsonFactory JSON = JsonFactory.builder().build();
  private final SqliteDatabase database;

  public GraphProjectionExporter(SqliteDatabase database) {
    this.database = Objects.requireNonNull(database, "database");
  }

  /** Writes a versioned graph JSONL projection into a new directory. */
  public Path export(Path requestedDirectory) {
    Path target = requestedDirectory.toAbsolutePath().normalize();
    if (Files.exists(target) || Files.isSymbolicLink(target))
      throw new PersistenceException("Refusing to overwrite graph export destination: " + target);
    Path parent = target.getParent();
    if (parent == null || !Files.isDirectory(parent) || Files.isSymbolicLink(parent))
      throw new PersistenceException("Graph export parent must be an existing directory");
    Path temporary =
        parent.resolve("." + target.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
    try {
      Files.createDirectory(temporary);
      restrict(temporary, true);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      long nodes;
      long edges;
      long events;
      int schema;
      try (Connection connection = database.newReadOnlyConnection();
          JsonGenerator json = JSON.createGenerator(output)) {
        connection.setAutoCommit(false);
        nodes = writeNodes(connection, json);
        edges = writeReferenceEdges(connection, json);
        edges += writeIndexEdges(connection, json);
        edges += writeBibliographyEdges(connection, json);
        edges += writeFilingEdges(connection, json);
        events = writeEvents(connection, json);
        schema = new MigrationRunner().currentVersion(connection);
        connection.commit();
      }
      byte[] content = output.toByteArray();
      Path graph = temporary.resolve("graph.jsonl");
      Files.write(graph, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      restrict(graph, false);
      force(graph);
      writeManifest(
          temporary.resolve("manifest.json"), schema, nodes, edges, events, sha256(content));
      restrict(temporary.resolve("manifest.json"), false);
      force(temporary.resolve("manifest.json"));
      force(temporary);
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      force(parent);
      return target;
    } catch (IOException | SQLException | RuntimeException exception) {
      deleteTree(temporary);
      throw new PersistenceException("Could not export graph projection", exception);
    }
  }

  private static long writeNodes(Connection c, JsonGenerator j) throws SQLException, IOException {
    long count = 0;
    count +=
        query(
            c,
            j,
            "SELECT c.id,c.state,c.created_at,p.raw_address,p.scheme_id,p.scheme_version,"
                + "il.divider,il.overflow_number,ik.keyterm,bc.source_id "
                + "FROM cards c LEFT JOIN placements p ON p.card_id=c.id "
                + "LEFT JOIN index_list_cards il ON il.card_id=c.id "
                + "LEFT JOIN index_keyterm_cards ik ON ik.card_id=c.id "
                + "LEFT JOIN bibcards bc ON bc.card_id=c.id ORDER BY c.id",
            (r, g) -> {
              g.writeStartObject();
              g.writeStringField("record", "node");
              g.writeStringField("id", "card:" + r.getString(1));
              g.writeStringField("kind", "CARD");
              g.writeStringField("state", r.getString(2));
              g.writeStringField("createdAt", r.getString(3));
              nullable(g, "address", r.getString(4));
              nullable(g, "addressScheme", r.getString(5));
              if (r.getObject(6) == null) g.writeNullField("addressSchemeVersion");
              else g.writeNumberField("addressSchemeVersion", r.getInt(6));
              nullable(g, "indexDivider", r.getString(7));
              if (r.getObject(8) == null) g.writeNullField("indexOverflow");
              else g.writeNumberField("indexOverflow", r.getInt(8));
              nullable(g, "keyterm", r.getString(9));
              nullable(g, "bibliographicSourceId", namespaced("source", r.getString(10)));
              g.writeEndObject();
            });
    count +=
        query(
            c,
            j,
            "SELECT cr.id,rc.card_id,rc.container_kind,cr.run_order,cr.run_kind,cr.authored_literal "
                + "FROM card_runs cr JOIN run_containers rc ON rc.id=cr.container_id "
                + "WHERE cr.run_kind<>'TEXT' ORDER BY cr.id",
            (r, g) -> {
              g.writeStartObject();
              g.writeStringField("record", "node");
              g.writeStringField("id", namespaced("run", r.getString(1)));
              g.writeStringField("kind", "BODY_RUN");
              g.writeStringField("cardId", namespaced("card", r.getString(2)));
              g.writeStringField("containerKind", r.getString(3));
              g.writeNumberField("runOrder", r.getInt(4));
              g.writeStringField("runKind", r.getString(5));
              g.writeStringField("authoredLiteral", r.getString(6));
              g.writeEndObject();
            });
    count +=
        query(
            c,
            j,
            "SELECT id,source_type,title,created_at,raw_record FROM bibliographic_sources ORDER BY id",
            (r, g) -> {
              g.writeStartObject();
              g.writeStringField("record", "node");
              g.writeStringField("id", namespaced("source", r.getString(1)));
              g.writeStringField("kind", "BIBLIOGRAPHIC_SOURCE");
              g.writeStringField("sourceType", r.getString(2));
              g.writeStringField("title", r.getString(3));
              g.writeStringField("createdAt", r.getString(4));
              g.writeStringField("rawRecord", r.getString(5));
              g.writeEndObject();
            });
    count +=
        query(
            c,
            j,
            "SELECT n.id,n.bibcard_id,n.ordinal,n.locator_type,n.locator_literal,n.authored_text,n.created_at,n.derived_main_card_id FROM bibnotes n ORDER BY n.id",
            (r, g) -> {
              g.writeStartObject();
              g.writeStringField("record", "node");
              g.writeStringField("id", namespaced("bibnote", r.getString(1)));
              g.writeStringField("kind", "BIB_NOTE");
              g.writeStringField("bibCardId", namespaced("card", r.getString(2)));
              g.writeNumberField("ordinal", r.getInt(3));
              g.writeStringField("locatorType", r.getString(4));
              g.writeStringField("locatorLiteral", r.getString(5));
              g.writeStringField("authoredText", r.getString(6));
              g.writeStringField("createdAt", r.getString(7));
              nullable(g, "derivedMainCardId", namespaced("card", r.getString(8)));
              g.writeEndObject();
            });
    return count;
  }

  private static long writeReferenceEdges(Connection c, JsonGenerator j)
      throws SQLException, IOException {
    long rows =
        query(
            c,
            j,
            "SELECT id,source_card_id,source_run_id,kind,authored_literal,target_literal,target_card_id,target_source_id,target_index_id,status,created_at FROM refs ORDER BY sequence",
            (r, g) -> {
              g.writeStartObject();
              g.writeStringField("record", "edge");
              g.writeStringField("id", namespaced("reference", r.getString(1)));
              g.writeStringField("fromId", namespaced("card", r.getString(2)));
              String target = r.getString(7);
              String source = r.getString(8);
              String index = r.getString(9);
              g.writeStringField(
                  "toId",
                  target != null
                      ? namespaced("card", target)
                      : source != null
                          ? namespaced("source", source)
                          : index != null ? namespaced("card", index) : null);
              g.writeStringField("kind", r.getString(4));
              g.writeStringField("namespace", "AUTHORED_REFERENCE");
              g.writeBooleanField("derived", false);
              g.writeStringField("authoredLiteral", r.getString(5));
              g.writeStringField("targetLiteral", r.getString(6));
              g.writeStringField("resolutionStatus", r.getString(10));
              g.writeStringField("sourceRunId", namespaced("run", r.getString(3)));
              g.writeStringField("authoredAt", r.getString(11));
              g.writeEndObject();
            });
    return rows
        + query(
            c,
            j,
            "SELECT cr.id,rc.card_id,cr.run_kind,cr.authored_literal "
                + "FROM card_runs cr JOIN run_containers rc ON rc.id=cr.container_id "
                + "WHERE cr.run_kind<>'TEXT' AND NOT EXISTS "
                + "(SELECT 1 FROM refs r WHERE r.source_run_id=cr.id) ORDER BY cr.id",
            (r, g) -> {
              String runId = r.getString(1);
              String runKind = r.getString(3);
              g.writeStartObject();
              g.writeStringField("record", "edge");
              g.writeStringField("id", namespaced("reference-run", runId));
              g.writeStringField("fromId", namespaced("card", r.getString(2)));
              g.writeNullField("toId");
              g.writeStringField("kind", runKind);
              g.writeStringField("namespace", "AUTHORED_REFERENCE");
              g.writeBooleanField("derived", false);
              g.writeStringField("authoredLiteral", r.getString(4));
              g.writeStringField("targetLiteral", r.getString(4));
              g.writeStringField("resolutionStatus", "PENDING");
              g.writeStringField("sourceRunId", namespaced("run", runId));
              g.writeEndObject();
            });
  }

  private static long writeIndexEdges(Connection c, JsonGenerator j)
      throws SQLException, IOException {
    return query(
            c,
            j,
            "SELECT e.id,e.index_card_id,e.target_kind,e.target_literal,e.target_main_card_id,e.target_index_card_id,e.created_at,e.authored_text FROM index_entries e ORDER BY e.id",
            (r, g) -> writeIndexEdge(r, g, "index-entry"))
        + query(
            c,
            j,
            "SELECT e.id,e.keyterm_card_id,e.target_kind,e.target_literal,e.target_main_card_id,e.target_index_card_id,e.created_at,e.authored_text FROM keyterm_entries e ORDER BY e.id",
            (r, g) -> writeIndexEdge(r, g, "keyterm-entry"))
        + query(
            c,
            j,
            "SELECT p.source_entry_id,e.index_card_id,p.keyterm_card_id,p.promoted_entry_id,p.promoted_at "
                + "FROM index_promotions p JOIN index_entries e ON e.id=p.source_entry_id "
                + "ORDER BY p.source_entry_id",
            (r, g) -> {
              g.writeStartObject();
              g.writeStringField("record", "edge");
              g.writeStringField("id", namespaced("index-promotion", r.getString(1)));
              g.writeStringField("fromId", namespaced("card", r.getString(2)));
              g.writeStringField("toId", namespaced("card", r.getString(3)));
              g.writeStringField("kind", "INDEX_ENTRY_PROMOTED_TO_KEYTERM");
              g.writeStringField("namespace", "AUTHORED_INDEX_PROMOTION");
              g.writeBooleanField("derived", false);
              g.writeStringField("sourceEntryId", namespaced("index-entry", r.getString(1)));
              g.writeStringField("promotedEntryId", namespaced("keyterm-entry", r.getString(4)));
              g.writeStringField("authoredAt", r.getString(5));
              g.writeEndObject();
            });
  }

  private static void writeIndexEdge(ResultSet r, JsonGenerator g, String idPrefix)
      throws SQLException, IOException {
    g.writeStartObject();
    g.writeStringField("record", "edge");
    g.writeStringField("id", namespaced(idPrefix, r.getString(1)));
    g.writeStringField("fromId", namespaced("card", r.getString(2)));
    String main = r.getString(5);
    String index = r.getString(6);
    g.writeStringField(
        "toId",
        main != null ? namespaced("card", main) : index != null ? namespaced("card", index) : null);
    g.writeStringField("kind", r.getString(3));
    g.writeStringField("namespace", "AUTHORED_INDEX_POINTER");
    g.writeBooleanField("derived", false);
    g.writeStringField("targetLiteral", r.getString(4));
    g.writeStringField("authoredText", r.getString(8));
    g.writeStringField("resolutionStatus", main != null || index != null ? "RESOLVED" : "PENDING");
    g.writeStringField("authoredAt", r.getString(7));
    g.writeEndObject();
  }

  private static long writeBibliographyEdges(Connection c, JsonGenerator j)
      throws SQLException, IOException {
    return query(
            c,
            j,
            "SELECT card_id,source_id FROM bibcards ORDER BY card_id",
            (r, g) -> {
              g.writeStartObject();
              g.writeStringField("record", "edge");
              g.writeStringField("id", namespaced("bibcard-source", r.getString(1)));
              g.writeStringField("fromId", namespaced("card", r.getString(1)));
              g.writeStringField("toId", namespaced("source", r.getString(2)));
              g.writeStringField("kind", "BIB_CARD_FOR_SOURCE");
              g.writeStringField("namespace", "AUTHORED_BIBLIOGRAPHY_ASSOCIATION");
              g.writeBooleanField("derived", false);
              g.writeEndObject();
            })
        + query(
            c,
            j,
            "SELECT id,bibcard_id FROM bibnotes ORDER BY id",
            (r, g) -> {
              g.writeStartObject();
              g.writeStringField("record", "edge");
              g.writeStringField("id", namespaced("bibnote-card", r.getString(1)));
              g.writeStringField("fromId", namespaced("bibnote", r.getString(1)));
              g.writeStringField("toId", namespaced("card", r.getString(2)));
              g.writeStringField("kind", "BIB_NOTE_OF");
              g.writeStringField("namespace", "AUTHORED_BIBLIOGRAPHY_ASSOCIATION");
              g.writeBooleanField("derived", false);
              g.writeEndObject();
            })
        + query(
            c,
            j,
            "SELECT id,derived_main_card_id FROM bibnotes WHERE derived_main_card_id IS NOT NULL ORDER BY id",
            (r, g) -> {
              g.writeStartObject();
              g.writeStringField("record", "edge");
              g.writeStringField("id", namespaced("bibnote-main-context", r.getString(1)));
              g.writeStringField("fromId", namespaced("bibnote", r.getString(1)));
              g.writeStringField("toId", namespaced("card", r.getString(2)));
              g.writeStringField("kind", "BIBNOTE_MAIN_CARD_CONTEXT");
              g.writeStringField("namespace", "DERIVED_BIBLIOGRAPHIC_CONTEXT");
              g.writeBooleanField("derived", true);
              g.writeEndObject();
            });
  }

  private static long writeFilingEdges(Connection c, JsonGenerator j)
      throws SQLException, IOException {
    long[] edgeCount = {0};
    return query(
                c,
                j,
                "SELECT card_id,predecessor_card_id,successor_card_id,raw_address,scheme_id,scheme_version,installed_at FROM placements ORDER BY card_id",
                (r, g) -> {
                  String card = r.getString(1);
                  String address = r.getString(4);
                  String scheme = r.getString(5);
                  int version = r.getInt(6);
                  String at = r.getString(7);
                  String predecessor = r.getString(2);
                  if (predecessor != null) {
                    edgeCount[0]++;
                    g.writeStartObject();
                    g.writeStringField("record", "edge");
                    g.writeStringField("id", namespaced("placement-predecessor", card));
                    g.writeStringField("fromId", namespaced("card", card));
                    g.writeStringField("toId", namespaced("card", predecessor));
                    g.writeStringField("kind", "FILED_AFTER_SNAPSHOT");
                    g.writeStringField("namespace", "FILED_ADJACENT_TO");
                    g.writeBooleanField("derived", true);
                    g.writeStringField("addressLiteral", address);
                    g.writeStringField("addressScheme", scheme);
                    g.writeNumberField("addressSchemeVersion", version);
                    g.writeStringField("capturedAt", at);
                    g.writeEndObject();
                  }
                  String successor = r.getString(3);
                  if (successor != null) {
                    edgeCount[0]++;
                    g.writeStartObject();
                    g.writeStringField("record", "edge");
                    g.writeStringField("id", namespaced("placement-successor", card));
                    g.writeStringField("fromId", namespaced("card", card));
                    g.writeStringField("toId", namespaced("card", successor));
                    g.writeStringField("kind", "FILED_BEFORE_SNAPSHOT");
                    g.writeStringField("namespace", "FILED_ADJACENT_TO");
                    g.writeBooleanField("derived", true);
                    g.writeStringField("addressLiteral", address);
                    g.writeStringField("addressScheme", scheme);
                    g.writeNumberField("addressSchemeVersion", version);
                    g.writeStringField("capturedAt", at);
                    g.writeEndObject();
                  }
                })
            == 0
        ? 0
        : edgeCount[0];
  }

  private static long writeEvents(Connection c, JsonGenerator j) throws SQLException, IOException {
    return query(
        c,
        j,
        "SELECT global_seq,event_id,aggregate_type,aggregate_id,event_type,schema_version,happened_at,correlation_id,payload_json FROM domain_events ORDER BY global_seq",
        (r, g) -> {
          g.writeStartObject();
          g.writeStringField("record", "event");
          g.writeStringField("id", namespaced("event", r.getString(2)));
          g.writeNumberField("sequence", r.getLong(1));
          g.writeStringField("aggregateType", r.getString(3));
          g.writeStringField("aggregateId", r.getString(4));
          g.writeStringField("eventType", r.getString(5));
          g.writeNumberField("schemaVersion", r.getInt(6));
          g.writeStringField("occurredAt", r.getString(7));
          nullable(g, "correlationId", r.getString(8));
          g.writeStringField("payloadJson", r.getString(9));
          g.writeEndObject();
        });
  }

  @FunctionalInterface
  private interface RowWriter {
    void write(ResultSet row, JsonGenerator json) throws SQLException, IOException;
  }

  private static long query(Connection c, JsonGenerator g, String sql, RowWriter writer)
      throws SQLException, IOException {
    long count = 0;
    try (Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      while (r.next()) {
        writer.write(r, g);
        g.writeRaw('\n');
        count++;
      }
    }
    return count;
  }

  private static String namespaced(String type, String id) {
    return id == null ? null : type + ":" + id;
  }

  private static void nullable(JsonGenerator g, String name, String value) throws IOException {
    if (value == null) g.writeNullField(name);
    else g.writeStringField(name, value);
  }

  private static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void writeManifest(
      Path path, int schema, long nodes, long edges, long events, String digest)
      throws IOException {
    try (var stream = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW);
        JsonGenerator g = JSON.createGenerator(stream)) {
      g.useDefaultPrettyPrinter();
      g.writeStartObject();
      g.writeStringField("format", "zettelkasten-graph-jsonl");
      g.writeNumberField("formatVersion", 1);
      g.writeNumberField("schemaVersion", schema);
      g.writeNumberField("nodeCount", nodes);
      g.writeNumberField("edgeCount", edges);
      g.writeNumberField("eventCount", events);
      g.writeStringField("graphFile", "graph.jsonl");
      g.writeStringField("sha256", digest);
      g.writeEndObject();
    }
  }

  private static void restrict(Path path, boolean directory) {
    try {
      Files.setPosixFilePermissions(
          path,
          java.nio.file.attribute.PosixFilePermissions.fromString(
              directory ? "rwx------" : "rw-------"));
    } catch (IOException | UnsupportedOperationException ignored) {
    }
  }

  private static void force(Path path) {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException ignored) {
    }
  }

  private static void deleteTree(Path path) {
    try {
      if (Files.isDirectory(path)) {
        try (var children = Files.list(path)) {
          children.forEach(GraphProjectionExporter::deleteTree);
        }
      }
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }
}
