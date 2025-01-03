package org.example;

import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.insertInto;
import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.literal;
import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.selectFrom;
import static com.datastax.oss.driver.api.querybuilder.SchemaBuilder.createIndex;
import static com.datastax.oss.driver.api.querybuilder.SchemaBuilder.createTable;

import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.apache.commons.collections4.ListUtils;
import org.jetbrains.annotations.NotNull;

public final class Main {
  public static final int MILLISECONDS_PER_SECOND = 1000;

  private Main() {
  }

  public static void main(final String[] args) {
    final String datacenter = "datacenter1";
    final String[] ips = {
        "192.168.0.163",
        "192.168.0.24",
        "192.168.0.214"
    };
    final int port = 9042;
    final String keyspace = "foobar";
    final int replicationFactor = 3;
    final String table = "lorem";

    final int itemCount = 1000;
    final int writeThreadCount = 50;
    final int readThreadCount = 500;
    final int randomStringLength = 1024;

    try (CassandraClient client = new CassandraClient(datacenter, ips, port)) {
      Main.log("createKeyspaceIfNotExists");

      client.createKeyspaceIfNotExists(keyspace, replicationFactor);

      Main.log("tableCreate");

      Main.tableCreate(client, keyspace, table);

      Main.log("indexCreate");

      Main.indexCreate(client, keyspace, table);

      final int processorCount = Runtime.getRuntime().availableProcessors();

      Main.log(String.format("Number of processors: %d", processorCount));

      final List<Integer> values = Main.createValues(itemCount);
      final List<List<Integer>> writeBatches = partitionList(values, writeThreadCount);
      final List<List<Integer>> readBatches = partitionList(values, readThreadCount);

      Main.evaluateInsert(
          client,
          keyspace,
          table,
          writeBatches,
          randomStringLength
      );

      Main.evaluateSelect(
          client,
          keyspace,
          table,
          readBatches
      );
    }
  }

  private static void log(@NotNull final String message) {
    final String time = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

    System.out.format("[%s] %s%n", time, message);
  }

  private static void evaluateInsert(
      @NotNull final CassandraClient client,
      @NotNull final String keyspace,
      @NotNull final String table,
      @NotNull final List<List<Integer>> batches,
      final int randomStringLength
  ) {
    final int threadCount = batches.size();
    final int itemCount = batches.stream().mapToInt(Collection::size).sum();

    Main.log(String.format("Inserting %d items using %d threads", itemCount, threadCount));

    final Duration durationInsert = Main.stopwatch(() -> {
      final List<Thread> threads = batches.stream()
          .map((batch) -> {
            return new Thread(() -> {
              for (int value : batch) {
                Main.insertRow(client, keyspace, table, randomStringLength, value);
              }
            });
          }).toList();

      Main.runThreads(threads);
    });

    Main.log(String.format(
        "Inserting %d items using %d threads takes %.2f seconds (average: %.2f per second)",
        itemCount,
        threadCount,
        (double) durationInsert.toMillis() / MILLISECONDS_PER_SECOND,
        (double) itemCount / durationInsert.toMillis() * MILLISECONDS_PER_SECOND
    ));
  }

  private static void evaluateSelect(
      @NotNull final CassandraClient client,
      @NotNull final String keyspace,
      @NotNull final String table,
      @NotNull final List<List<Integer>> batches
  ) {
    final int threadCount = batches.size();
    final int itemCount = batches.stream().mapToInt(Collection::size).sum();

    Main.log(String.format("Selecting %d items randomly using %d threads", itemCount, threadCount));

    final Duration durationSelect = Main.stopwatch(() -> {
      final List<Thread> threads = batches.stream()
          .map((batch) -> {
            return new Thread(() -> {
              final Random random = new Random();

              for (int i = 0; i < batch.size(); i++) {
                final int index = random.nextInt(0, batch.size());
                final int value = batch.get(index);

                final Row row = Main.selectRow(client, keyspace, table, value).one();

                if (row == null) {
                  Main.log(String.format("Missing value %d", value));
                }
              }
            });
          })
          .toList();

      Main.runThreads(threads);
    });

    Main.log(String.format(
        "Selecting %d items randomly using %d threads takes %.2f seconds (average: %.2f per second)",
        itemCount,
        threadCount,
        (double) durationSelect.toMillis() / MILLISECONDS_PER_SECOND,
        (double) itemCount / durationSelect.toMillis() * MILLISECONDS_PER_SECOND
    ));
  }

  private static List<Integer> createValues(final int count) {
    final Random random = new Random();

    return IntStream.generate(random::nextInt)
        .limit(count)
        .boxed()
        .toList();
  }

  private static <T> @NotNull List<List<T>> partitionList(
      @NotNull final List<T> list,
      final int listCount
  ) {
    final int size = (int) Math.ceil((double) list.size() / listCount);

    return ListUtils.partition(list, size);
  }

  private static void runThreads(@NotNull final List<Thread> threads) {
    for (Thread thread : threads) {
      thread.start();
    }

    for (Thread thread : threads) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        Main.log("A thread was interrupted.");
      }
    }
  }

  private static Duration stopwatch(@NotNull final Runnable callback) {
    final long timeStart = System.currentTimeMillis();

    callback.run();

    final long timeEnd = System.currentTimeMillis();

    return Duration.ofMillis(timeEnd - timeStart);
  }

  private static void tableCreate(
      @NotNull final CassandraClient client,
      @NotNull final String keyspace,
      @NotNull final String table
  ) {
    final SimpleStatement statement = createTable(keyspace, table)
        .ifNotExists()
        .withPartitionKey("id", DataTypes.TEXT)
        .withClusteringColumn("value", DataTypes.INT)
        .withColumn("hex", DataTypes.TEXT)
        .withColumn("random", DataTypes.TEXT)
        .withClusteringOrder("value", ClusteringOrder.DESC)
        .build();

    client.execute(statement);
  }

  private static void indexCreate(
      @NotNull final CassandraClient client,
      @NotNull final String keyspace,
      @NotNull final String table
  ) {
    final SimpleStatement statement = createIndex()
        .ifNotExists()
        .usingSASI()
        .onTable(keyspace, table)
        .andColumn("hex")
        .withSASIOptions(ImmutableMap.of("mode", "CONTAINS"))
        .build();

    client.execute(statement);
  }

  private static void insertRow(
      @NotNull final CassandraClient client,
      @NotNull final String keyspace,
      @NotNull final String table,
      final int randomStringLength,
      final int value
  ) {
    final String id = Integer.toString(value);
    final String hex = Integer.toHexString(value);
    final String randomText = Generator.randomString(randomStringLength);

    final SimpleStatement statement = insertInto(keyspace, table)
        .value("id", literal(id))
        .value("value", literal(value))
        .value("hex", literal(hex))
        .value("random", literal(randomText))
        .build();

    client.execute(statement);
  }

  private static @NotNull ResultSet selectRow(
      @NotNull final CassandraClient client,
      @NotNull final String keyspace,
      @NotNull final String table,
      final int value
  ) {
    final String id = Integer.toString(value);

    final SimpleStatement statement = selectFrom(keyspace, table)
        .all()
        .whereColumn("id").isEqualTo(literal(id))
        .build();

    return client.execute(statement);
  }
}
