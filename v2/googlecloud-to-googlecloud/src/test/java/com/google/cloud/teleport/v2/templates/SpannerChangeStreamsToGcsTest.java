/*
 * Copyright (C) 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.templates;

import static com.google.cloud.teleport.v2.templates.SpannerChangeStreamsToGcs.run;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.api.gax.grpc.testing.MockServiceHelper;
import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.MockSpannerServiceImpl;
import com.google.cloud.spanner.MockSpannerServiceImpl.StatementResult;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Options.RpcPriority;
import com.google.cloud.spanner.Statement;
import com.google.cloud.teleport.v2.options.SpannerChangeStreamsToGcsOptions;
import com.google.cloud.teleport.v2.spanner.IntegrationTest;
import com.google.cloud.teleport.v2.spanner.SpannerServerResource;
import com.google.cloud.teleport.v2.transforms.FileFormatFactorySpannerChangeStreams;
import com.google.cloud.teleport.v2.utils.DurationUtils;
import com.google.cloud.teleport.v2.utils.WriteToGCSUtility.FileFormat;
import com.google.gson.Gson;
import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import com.google.spanner.v1.ResultSet;
import com.google.spanner.v1.ResultSetMetadata;
import com.google.spanner.v1.StructType;
import com.google.spanner.v1.StructType.Field;
import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.spanner.SpannerConfig;
import org.apache.beam.sdk.io.gcp.spanner.SpannerIO;
import org.apache.beam.sdk.io.gcp.spanner.changestreams.model.DataChangeRecord;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.PCollection;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test class for {@link SpannerChangeStreamsToGcsTest}. */
@RunWith(JUnit4.class)
public final class SpannerChangeStreamsToGcsTest {

  /** Rule for exception testing. */
  @Rule public ExpectedException exception = ExpectedException.none();

  @Rule public final TemporaryFolder tmpDir = new TemporaryFolder();

  /** Rule for Spanner server resource. */
  @Rule public final SpannerServerResource spannerServer = new SpannerServerResource();

  /** Rule for pipeline testing. */
  @Rule public final transient TestPipeline pipeline = TestPipeline.create();

  public static final String SPANNER_HOST = "my-host";
  private static final String AVRO_FILENAME_PREFIX = "avro-output-";
  private static final String TEXT_FILENAME_PREFIX = "text-output-";
  private static final Integer NUM_SHARDS = 1;
  private static final String TEST_PROJECT = "span-cloud-testing";
  private static final String TEST_INSTANCE = "changestream";
  private static final String TEST_DATABASE_PREFIX = "testdbchangestreams";
  private static final String TEST_TABLE = "Users";
  private static final String TEST_CHANGE_STREAM = "UsersStream";
  private static final int MAX_TABLE_NAME_LENGTH = 29;

  private static final String FAKE_PROJECT = "fake-project";
  private static final String FAKE_INSTANCE = "fake-instance";
  private static final String FAKE_DATABASE = "fake-database";

  private static String fakeDir;
  private static String fakeTempLocation;
  private static final String FILENAME_PREFIX = "filenamePrefix";

  private MockSpannerServiceImpl mockSpannerService;
  private MockServiceHelper serviceHelper;

  @Before
  public void setup() throws Exception {
    fakeDir = tmpDir.newFolder("output").getAbsolutePath();
    fakeTempLocation = tmpDir.newFolder("temporaryLocation").getAbsolutePath();
    mockSpannerService = new MockSpannerServiceImpl();
    serviceHelper =
        new MockServiceHelper(SPANNER_HOST, Collections.singletonList(mockSpannerService));
    serviceHelper.start();
    serviceHelper.reset();
  }

  @After
  public void tearDown() throws NoSuchFieldException, IllegalAccessException {
    serviceHelper.stop();
  }

  @SuppressWarnings("DefaultAnnotationParam")
  private static class VerifyDataChangeRecordAvro
      implements SerializableFunction<
          Iterable<com.google.cloud.teleport.v2.DataChangeRecord>, Void> {
    @Override
    public Void apply(Iterable<com.google.cloud.teleport.v2.DataChangeRecord> actualIter) {
      // Make sure actual is the right length, and is a
      // subset of expected.
      List<com.google.cloud.teleport.v2.DataChangeRecord> actual = new ArrayList<>();
      for (com.google.cloud.teleport.v2.DataChangeRecord s : actualIter) {
        actual.add(s);
        assertEquals(TEST_TABLE, s.getTableName());
        assertTrue(s.getCommitTimestamp() > 0);
        assertTrue(s.getPartitionToken() != null && s.getPartitionToken().length() > 0);
        assertTrue(s.getServerTransactionId() != null && s.getServerTransactionId().length() > 0);
        assertTrue(s.getRecordSequence() != null && s.getRecordSequence().length() > 0);
        assertTrue(!s.getRowType().isEmpty());
        assertTrue(
            s.getRowType().get(0).getType()
                != com.google.cloud.teleport.v2.TypeCode.TYPE_CODE_UNSPECIFIED);
        assertTrue(!s.getMods().isEmpty());
        assertTrue(s.getNumberOfRecordsInTransaction() > 0);
        assertTrue(s.getNumberOfPartitionsInTransaction() > 0);
        assertTrue(s.getMetadata() != null);
      }
      return null;
    }
  }

  @SuppressWarnings("DefaultAnnotationParam")
  private static class VerifyDataChangeRecordText
      implements SerializableFunction<Iterable<String>, Void> {
    @Override
    public Void apply(Iterable<String> actualIter) {
      // Make sure actual is the right length, and is a
      // subset of expected.
      List<DataChangeRecord> actual = new ArrayList<>();
      for (String dataChangeRecordString : actualIter) {
        DataChangeRecord s = new Gson().fromJson(dataChangeRecordString, DataChangeRecord.class);
        actual.add(s);
        assertEquals(TEST_TABLE, s.getTableName());
        assertTrue(s.getCommitTimestamp().getSeconds() > 0);
        assertTrue(s.getPartitionToken() != null && s.getPartitionToken().length() > 0);
        assertTrue(s.getServerTransactionId() != null && s.getServerTransactionId().length() > 0);
        assertTrue(s.getRecordSequence() != null && s.getRecordSequence().length() > 0);
        assertTrue(!s.getRowType().isEmpty());
        assertTrue(!s.getMods().isEmpty());
        assertTrue(s.getNumberOfRecordsInTransaction() > 0);
        assertTrue(s.getNumberOfPartitionsInTransaction() > 0);
        assertTrue(s.getMetadata() != null);
      }
      return null;
    }
  }

  private String generateDatabaseName() {
    return TEST_DATABASE_PREFIX
        + "_"
        + RandomStringUtils.randomNumeric(
            MAX_TABLE_NAME_LENGTH - 1 - TEST_DATABASE_PREFIX.length());
  }

  /**
   * Test whether {@link FileFormatFactory} maps the output file format to the transform to be
   * carried out. And throws illegal argument exception if invalid file format is passed.
   */
  @Test
  public void testFileFormatFactoryInvalid() {
    mockGetDialect();

    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("Invalid output format:PARQUET. Supported output formats: TEXT, AVRO");

    SpannerChangeStreamsToGcsOptions options =
        PipelineOptionsFactory.create().as(SpannerChangeStreamsToGcsOptions.class);
    options.setOutputFileFormat(FileFormat.PARQUET);
    options.setGcsOutputDirectory(fakeDir);
    options.setOutputFilenamePrefix(FILENAME_PREFIX);
    options.setNumShards(NUM_SHARDS);
    options.setTempLocation(fakeTempLocation);

    Pipeline p = Pipeline.create(options);

    Timestamp startTimestamp = Timestamp.now();
    Timestamp endTimestamp = Timestamp.now();

    p
        // Reads from the change stream
        .apply(
            SpannerIO.readChangeStream()
                .withSpannerConfig(getSpannerConfig())
                .withMetadataDatabase(FAKE_DATABASE)
                .withChangeStreamName("changestream")
                .withInclusiveStartAt(startTimestamp)
                .withInclusiveEndAt(endTimestamp)
                .withRpcPriority(RpcPriority.HIGH))
        .apply(
            "Creating " + options.getWindowDuration() + " Window",
            Window.into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowDuration()))))
        .apply(
            "Write To GCS",
            FileFormatFactorySpannerChangeStreams.newBuilder().setOptions(options).build());

    p.run();
  }

  @Test
  public void testInvalidWindowDuration() {
    mockGetDialect();

    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("The window duration must be greater than 0!");
    SpannerChangeStreamsToGcsOptions options =
        PipelineOptionsFactory.create().as(SpannerChangeStreamsToGcsOptions.class);
    options.setOutputFileFormat(FileFormat.AVRO);
    options.setGcsOutputDirectory(fakeDir);
    options.setOutputFilenamePrefix(FILENAME_PREFIX);
    options.setNumShards(NUM_SHARDS);
    options.setTempLocation(fakeTempLocation);
    options.setWindowDuration("invalidWindowDuration");

    Pipeline p = Pipeline.create(options);

    Timestamp startTimestamp = Timestamp.now();
    Timestamp endTimestamp = Timestamp.now();

    p
        // Reads from the change stream
        .apply(
            SpannerIO.readChangeStream()
                .withSpannerConfig(getSpannerConfig())
                .withMetadataDatabase(FAKE_DATABASE)
                .withChangeStreamName("changestream")
                .withInclusiveStartAt(startTimestamp)
                .withInclusiveEndAt(endTimestamp)
                .withRpcPriority(RpcPriority.HIGH))
        .apply(
            "Creating " + options.getWindowDuration() + " Window",
            Window.into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowDuration()))))
        .apply(
            "Write To GCS",
            FileFormatFactorySpannerChangeStreams.newBuilder().setOptions(options).build());

    p.run();
  }

  @Test
  @Category(IntegrationTest.class)
  // This test can only be run locally with the following command:
  // mvn -Dexcluded.spanner.tests="" -Dtest=SpannerChangeStreamsToGcsTest test
  public void testWriteToGCSAvro() throws Exception {
    // Create a test database.
    String testDatabase = generateDatabaseName();
    fakeDir = tmpDir.newFolder("output").getAbsolutePath();
    fakeTempLocation = tmpDir.newFolder("temporaryLocation").getAbsolutePath();

    spannerServer.dropDatabase(testDatabase);

    // Create a table.
    List<String> statements = new ArrayList<String>();
    final String createTable =
        "CREATE TABLE "
            + TEST_TABLE
            + " ("
            + "user_id INT64 NOT NULL,"
            + "name STRING(MAX) "
            + ") PRIMARY KEY(user_id)";
    final String createChangeStream = "CREATE CHANGE STREAM " + TEST_CHANGE_STREAM + " FOR Users";
    statements.add(createTable);
    statements.add(createChangeStream);
    spannerServer.createDatabase(testDatabase, statements);

    Timestamp startTimestamp = Timestamp.now();

    // Create a mutation for the table that will generate 1 data change record.
    List<Mutation> mutations = new ArrayList<>();
    mutations.add(
        Mutation.newInsertBuilder(TEST_TABLE).set("user_id").to(1).set("name").to("Name1").build());
    mutations.add(
        Mutation.newInsertBuilder(TEST_TABLE).set("user_id").to(2).set("name").to("Name2").build());

    spannerServer.getDbClient(testDatabase).write(mutations);

    Timestamp endTimestamp = Timestamp.now();

    SpannerChangeStreamsToGcsOptions options =
        PipelineOptionsFactory.create().as(SpannerChangeStreamsToGcsOptions.class);
    options.setSpannerProjectId(TEST_PROJECT);
    options.setSpannerInstanceId(TEST_INSTANCE);
    options.setSpannerDatabase(testDatabase);
    options.setSpannerMetadataInstanceId(TEST_INSTANCE);
    options.setSpannerMetadataDatabase(testDatabase);
    options.setSpannerChangeStreamName(TEST_CHANGE_STREAM);

    options.setStartTimestamp(startTimestamp.toString());
    options.setEndTimestamp(endTimestamp.toString());
    List<String> experiments = new ArrayList<String>();
    options.setExperiments(experiments);

    options.setOutputFileFormat(FileFormat.AVRO);
    options.setGcsOutputDirectory(fakeDir);
    options.setOutputFilenamePrefix(AVRO_FILENAME_PREFIX);
    options.setNumShards(NUM_SHARDS);
    options.setTempLocation(fakeTempLocation);

    // Run the pipeline.
    PipelineResult result = run(options);
    result.waitUntilFinish();

    // Read from the output Avro file to assert that 1 data change record has been generated.
    PCollection<com.google.cloud.teleport.v2.DataChangeRecord> dataChangeRecords =
        pipeline.apply(
            "readRecords",
            AvroIO.read(com.google.cloud.teleport.v2.DataChangeRecord.class)
                .from(fakeDir + "/avro-output-*.avro"));
    PAssert.that(dataChangeRecords).satisfies(new VerifyDataChangeRecordAvro());
    pipeline.run();

    // Drop the database.
    spannerServer.dropDatabase(testDatabase);
  }

  @Test
  @Category(IntegrationTest.class)
  // This test can only be run locally with the following command:
  // mvn -Dexcluded.spanner.tests="" -Dtest=SpannerChangeStreamsToGcsTest test
  public void testWriteToGCSText() throws Exception {
    // Create a test database.
    String testDatabase = generateDatabaseName();
    fakeDir = tmpDir.newFolder("output").getAbsolutePath();
    fakeTempLocation = tmpDir.newFolder("temporaryLocation").getAbsolutePath();

    spannerServer.dropDatabase(testDatabase);

    // Create a table.
    List<String> statements = new ArrayList<String>();
    final String createTable =
        "CREATE TABLE "
            + TEST_TABLE
            + " ("
            + "user_id INT64 NOT NULL,"
            + "name STRING(MAX) "
            + ") PRIMARY KEY(user_id)";
    final String createChangeStream = "CREATE CHANGE STREAM " + TEST_CHANGE_STREAM + " FOR Users";
    statements.add(createTable);
    statements.add(createChangeStream);
    spannerServer.createDatabase(testDatabase, statements);

    Timestamp startTimestamp = Timestamp.now();

    // Create a mutation for the table that will generate 1 data change record.
    List<Mutation> mutations = new ArrayList<>();
    mutations.add(
        Mutation.newInsertBuilder(TEST_TABLE).set("user_id").to(1).set("name").to("Name1").build());
    mutations.add(
        Mutation.newInsertBuilder(TEST_TABLE).set("user_id").to(2).set("name").to("Name2").build());

    spannerServer.getDbClient(testDatabase).write(mutations);

    Timestamp endTimestamp = Timestamp.now();

    SpannerChangeStreamsToGcsOptions options =
        PipelineOptionsFactory.create().as(SpannerChangeStreamsToGcsOptions.class);
    options.setSpannerProjectId(TEST_PROJECT);
    options.setSpannerInstanceId(TEST_INSTANCE);
    options.setSpannerDatabase(testDatabase);
    options.setSpannerMetadataInstanceId(TEST_INSTANCE);
    options.setSpannerMetadataDatabase(testDatabase);
    options.setSpannerChangeStreamName(TEST_CHANGE_STREAM);

    options.setStartTimestamp(startTimestamp.toString());
    options.setEndTimestamp(endTimestamp.toString());
    List<String> experiments = new ArrayList<String>();
    options.setExperiments(experiments);

    options.setOutputFileFormat(FileFormat.TEXT);
    options.setGcsOutputDirectory(fakeDir);
    options.setOutputFilenamePrefix(TEXT_FILENAME_PREFIX);
    options.setNumShards(NUM_SHARDS);
    options.setTempLocation(fakeTempLocation);

    // Run the pipeline.
    PipelineResult result = run(options);
    result.waitUntilFinish();

    // Read from the output Avro file to assert that 1 data change record has been generated.
    PCollection<String> dataChangeRecords =
        pipeline.apply("readRecords", TextIO.read().from(fakeDir + "/text-output-*.txt"));
    PAssert.that(dataChangeRecords).satisfies(new VerifyDataChangeRecordText());
    pipeline.run();

    // Drop the database.
    spannerServer.dropDatabase(testDatabase);
  }

  private SpannerConfig getSpannerConfig() {
    RetrySettings quickRetrySettings =
        RetrySettings.newBuilder()
            .setInitialRetryDelay(org.threeten.bp.Duration.ofMillis(250))
            .setMaxRetryDelay(org.threeten.bp.Duration.ofSeconds(1))
            .setRetryDelayMultiplier(5)
            .setTotalTimeout(org.threeten.bp.Duration.ofSeconds(1))
            .build();
    return SpannerConfig.create()
        .withEmulatorHost(StaticValueProvider.of(SPANNER_HOST))
        .withIsLocalChannelProvider(StaticValueProvider.of(true))
        .withCommitRetrySettings(quickRetrySettings)
        .withExecuteStreamingSqlRetrySettings(quickRetrySettings)
        .withProjectId(FAKE_PROJECT)
        .withInstanceId(FAKE_INSTANCE)
        .withDatabaseId(FAKE_DATABASE);
  }

  private void mockGetDialect() {
    Statement determineDialectStatement =
        Statement.newBuilder(
                "SELECT 'POSTGRESQL' AS DIALECT\n"
                    + "FROM INFORMATION_SCHEMA.SCHEMATA\n"
                    + "WHERE SCHEMA_NAME='information_schema'\n"
                    + "UNION ALL\n"
                    + "SELECT 'GOOGLE_STANDARD_SQL' AS DIALECT\n"
                    + "FROM INFORMATION_SCHEMA.SCHEMATA\n"
                    + "WHERE SCHEMA_NAME='INFORMATION_SCHEMA' AND CATALOG_NAME=''")
            .build();
    ResultSetMetadata dialectResultSetMetadata =
        ResultSetMetadata.newBuilder()
            .setRowType(
                StructType.newBuilder()
                    .addFields(
                        Field.newBuilder()
                            .setName("dialect")
                            .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                            .build())
                    .build())
            .build();
    ResultSet dialectResultSet =
        ResultSet.newBuilder()
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("GOOGLE_STANDARD_SQL").build())
                    .build())
            .setMetadata(dialectResultSetMetadata)
            .build();
    mockSpannerService.putStatementResult(
        StatementResult.query(determineDialectStatement, dialectResultSet));
  }
}
