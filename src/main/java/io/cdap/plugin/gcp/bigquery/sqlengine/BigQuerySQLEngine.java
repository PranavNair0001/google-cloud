/*
 * Copyright © 2021 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.gcp.bigquery.sqlengine;

import com.google.auth.Credentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.storage.Storage;
import com.google.common.annotations.VisibleForTesting;
import io.cdap.cdap.api.RuntimeContext;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.etl.api.engine.sql.BatchSQLEngine;
import io.cdap.cdap.etl.api.engine.sql.SQLEngineException;
import io.cdap.cdap.etl.api.engine.sql.dataset.SQLDataset;
import io.cdap.cdap.etl.api.engine.sql.dataset.SQLPullDataset;
import io.cdap.cdap.etl.api.engine.sql.dataset.SQLPushDataset;
import io.cdap.cdap.etl.api.engine.sql.request.SQLJoinDefinition;
import io.cdap.cdap.etl.api.engine.sql.request.SQLJoinRequest;
import io.cdap.cdap.etl.api.engine.sql.request.SQLPullRequest;
import io.cdap.cdap.etl.api.engine.sql.request.SQLPushRequest;
import io.cdap.cdap.etl.api.join.JoinCondition;
import io.cdap.cdap.etl.api.join.JoinDefinition;
import io.cdap.cdap.etl.api.join.JoinStage;
import io.cdap.plugin.gcp.bigquery.sink.BigQuerySinkUtils;
import io.cdap.plugin.gcp.bigquery.util.BigQueryUtil;
import io.cdap.plugin.gcp.common.GCPUtils;
import org.apache.avro.generic.GenericData;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * SQL Engine implementation using BigQuery as the execution engine.
 */
@Plugin(type = BatchSQLEngine.PLUGIN_TYPE)
@Name(BigQuerySQLEngine.NAME)
@Description("BigQuery SQLEngine implementation, used to push down certain pipeline steps into BigQuery. "
  + "A GCS bucket is used as staging for the read/write operations performed by this engine. "
  + "BigQuery is Google's serverless, highly scalable, enterprise data warehouse.")
public class BigQuerySQLEngine
  extends BatchSQLEngine<LongWritable, GenericData.Record, StructuredRecord, NullWritable> {

  private static final Logger LOG = LoggerFactory.getLogger(BigQuerySQLEngine.class);

  public static final String NAME = "BigQueryPushdownEngine";

  private final BigQuerySQLEngineConfig config;
  private BigQuery bigQuery;
  private Storage storage;
  private Configuration configuration;
  private String project;
  private String location;
  private String dataset;
  private String bucket;
  private String runId;
  private Map<String, String> tableNames;
  private Map<String, BigQuerySQLDataset> datasets;

  @SuppressWarnings("unused")
  public BigQuerySQLEngine(BigQuerySQLEngineConfig config) {
    this.config = config;
  }

  @Override
  public void prepareRun(RuntimeContext context) throws Exception {
    super.prepareRun(context);

    runId = BigQuerySQLEngineUtils.getIdentifier();
    tableNames = new HashMap<>();
    datasets = new HashMap<>();

    String serviceAccount = config.getServiceAccount();
    Credentials credentials = serviceAccount == null ?
      null : GCPUtils.loadServiceAccountCredentials(serviceAccount, config.isServiceAccountFilePath());
    project = config.getProject();
    dataset = config.getDataset();
    bucket = config.getBucket() != null ? config.getBucket() : "bqpushdown-" + runId;
    location = config.getLocation();

    // Initialize BQ and GCS clients.
    bigQuery = GCPUtils.getBigQuery(project, credentials);
    storage = GCPUtils.getStorage(project, credentials);

    String cmekKey = context.getRuntimeArguments().get(GCPUtils.CMEK_KEY);
    configuration = BigQueryUtil.getBigQueryConfig(config.getServiceAccount(), config.getProject(),
                                                   cmekKey, config.getServiceAccountType());
    BigQuerySinkUtils.createResources(bigQuery, storage, dataset, bucket, config.getLocation(), cmekKey);
  }

  @Override
  public SQLPushDataset<StructuredRecord, StructuredRecord, NullWritable> getPushProvider(SQLPushRequest sqlPushRequest)
    throws SQLEngineException {
    try {
      BigQueryPushDataset pushDataset =
        BigQueryPushDataset.getInstance(sqlPushRequest,
                                        configuration,
                                        bigQuery,
                                        project,
                                        dataset,
                                        bucket,
                                        runId);

      LOG.info("Executing Push operation for dataset {} stored in table {}",
               sqlPushRequest.getDatasetName(),
               pushDataset.getBigQueryTableName());

      datasets.put(sqlPushRequest.getDatasetName(), pushDataset);
      return pushDataset;
    } catch (IOException ioe) {
      throw new SQLEngineException(ioe);
    }
  }

  @Override
  public SQLPullDataset<StructuredRecord, LongWritable, GenericData.Record> getPullProvider(
    SQLPullRequest sqlPullRequest) throws SQLEngineException {
    if (!datasets.containsKey(sqlPullRequest.getDatasetName())) {
      throw new SQLEngineException(String.format("Trying to pull non-existing dataset: '%s",
                                                 sqlPullRequest.getDatasetName()));
    }

    String table = datasets.get(sqlPullRequest.getDatasetName()).getBigQueryTableName();

    LOG.info("Executing Pull operation for dataset {} stored in table {}", sqlPullRequest.getDatasetName(), table);

    try {
      return BigQueryPullDataset.getInstance(sqlPullRequest,
                                             configuration,
                                             bigQuery,
                                             project,
                                             dataset,
                                             table,
                                             bucket,
                                             runId);
    } catch (IOException ioe) {
      throw new SQLEngineException(ioe);
    }
  }

  @Override
  public boolean exists(String datasetName) throws SQLEngineException {
    return datasets.containsKey(datasetName);
  }

  @Override
  public boolean canJoin(SQLJoinDefinition sqlJoinDefinition) {
    return isValidJoinDefinition(sqlJoinDefinition);
  }

  @VisibleForTesting
  protected static boolean isValidJoinDefinition(SQLJoinDefinition sqlJoinDefinition) {
    List<String> validationProblems = new ArrayList<>();

    JoinDefinition joinDefinition = sqlJoinDefinition.getJoinDefinition();

    // Ensure none of the input schemas contains unsupported types or invalid stage names.
    for (JoinStage inputStage : joinDefinition.getStages()) {
      // Validate input stage schema and identifier
      validateInputStage(inputStage, validationProblems);
    }

    // Ensure the output schema doesn't contain unsupported types
    validateOutputSchema(joinDefinition.getOutputSchema(), validationProblems);

    // Ensure expression joins have valid aliases
    if (joinDefinition.getCondition().getOp() == JoinCondition.Op.EXPRESSION) {
      validateOnExpressionJoinCondition((JoinCondition.OnExpression) joinDefinition.getCondition(),
                                        validationProblems);
    }

    if (!validationProblems.isEmpty()) {
      LOG.warn("Join operation for stage '{}' could not be executed in BigQuery. Issues found: {}.",
               sqlJoinDefinition.getDatasetName(),
               String.join("; ", validationProblems));
    }

    return validationProblems.isEmpty();
  }

  /**
   * Validate input stage schema. Any errors will be added to the supplied list of validation issues.
   *
   * @param inputStage Input Stage
   * @param validationProblems List of validation problems to use to append messages
   */
  private static void validateInputStage(JoinStage inputStage, List<String> validationProblems) {
    String stageName = inputStage.getStageName();

    if (inputStage.getSchema() == null) {
      // Null schemas are not supported.
      validationProblems.add(String.format("Input schema from stage '%s' is null", stageName));
    } else {
      // Validate schema
      SchemaValidation schemaValidation = validateSchema(inputStage.getSchema());
      if (!schemaValidation.isSupported()) {
        validationProblems.add(
          String.format("Input schema from stage '%s' contains unsupported field types for the following fields: %s",
                        stageName,
                        String.join(", ", schemaValidation.getInvalidFields())));
      }
    }

    if (!isValidIdentifier(stageName)) {
      validationProblems.add(
        String.format("Unsupported stage name '%s'. Stage names cannot contain backtick ` or backslash \\ ",
                      stageName));
    }
  }


  /**
   * Validate output stage schema. Any errors will be added to the supplied list of validation issues.
   *
   * @param outputSchema the schema to validate
   * @param validationProblems List of validation problems to use to append messages
   */
  private static void validateOutputSchema(@Nullable Schema outputSchema, List<String> validationProblems) {
    if (outputSchema == null) {
      // Null schemas are not supported.
      validationProblems.add("Output Schema is null");
    } else {
      // Validate schema
      SchemaValidation schemaValidation = validateSchema(outputSchema);
      if (!schemaValidation.isSupported()) {
        validationProblems.add(
          String.format("Output schema contains unsupported field types for the following fields: %s",
                        String.join(", ", schemaValidation.getInvalidFields())));
      }
    }
  }

  /**
   * Validate on expression join condition
   *
   * @param onExpression Join Condition to validate
   * @param validationProblems List of validation problems to use to append messages
   */
  private static void validateOnExpressionJoinCondition(JoinCondition.OnExpression onExpression,
                                                        List<String> validationProblems) {
    for (Map.Entry<String, String> alias : onExpression.getDatasetAliases().entrySet()) {
      if (!isValidIdentifier(alias.getValue())) {
        validationProblems.add(
          String.format("Unsupported alias '%s' for stage '%s'", alias.getValue(), alias.getKey()));
      }
    }
  }

  @Override
  public SQLDataset join(SQLJoinRequest sqlJoinRequest) throws SQLEngineException {
    BigQueryJoinDataset joinDataset = BigQueryJoinDataset.getInstance(sqlJoinRequest,
                                                                      getStageNameToBQTableNameMap(),
                                                                      bigQuery,
                                                                      location,
                                                                      project,
                                                                      dataset,
                                                                      runId);

    datasets.put(sqlJoinRequest.getDatasetName(), joinDataset);

    return joinDataset;
  }

  @Override
  public void cleanup(String datasetName) throws SQLEngineException {
    BigQuerySQLDataset bqDataset = datasets.get(datasetName);
    if (bqDataset == null) {
      return;
    }

    SQLEngineException ex = null;

    // Cancel BQ job
    try {
      cancelJob(datasetName, bqDataset);
    } catch (BigQueryException e) {
      LOG.error("Exception when cancelling BigQuery job '{}' for stage '{}': {}",
                bqDataset.getJobId(), datasetName, e.getMessage());
      ex = new SQLEngineException(String.format("Exception when executing cleanup for stage '%s'", datasetName), e);
    }

    // Delete BQ Table
    try {
      deleteTable(datasetName, bqDataset);
    } catch (BigQueryException e) {
      LOG.error("Exception when deleting BigQuery table '{}' for stage '{}': {}",
                bqDataset.getBigQueryTableName(), datasetName, e.getMessage());
      if (ex == null) {
        ex = new SQLEngineException(String.format("Exception when executing cleanup for stage '%s'", datasetName), e);
      } else {
        ex.addSuppressed(e);
      }
    }

    // Delete temporary folder
    try {
      deleteTempFolder(bqDataset);
    } catch (IOException e) {
      LOG.error("Failed to delete temporary directory '{}' for stage '{}': {}",
                bqDataset.getGCSPath(), datasetName, e.getMessage());
      if (ex == null) {
        ex = new SQLEngineException(String.format("Exception when executing cleanup for stage '%s'", datasetName), e);
      } else {
        ex.addSuppressed(e);
      }
    }

    // Throw all collected exceptions, if any.
    if (ex != null) {
      throw ex;
    }
  }

  /**
   * Get a map that contains stage names as keys and BigQuery tables as Values.
   *
   * @return map representing all stages currently pushed to BQ.
   */
  protected Map<String, String> getStageNameToBQTableNameMap() {
    return datasets.entrySet()
      .stream()
      .collect(Collectors.toMap(
        Map.Entry::getKey,
        e -> e.getValue().getBigQueryTableName()
      ));
  }

  /**
   * Stops the running job for the supplied dataset
   *
   * @param stageName the name of the stage in CDAP
   * @param bqDataset the BigQuery Dataset Instance
   */
  protected void cancelJob(String stageName, BigQuerySQLDataset bqDataset) throws BigQueryException {
    String jobId = bqDataset.getJobId();

    // If this dataset does not specify a job ID, there's no need to cancel any job
    if (jobId == null) {
      return;
    }

    String tableName = bqDataset.getBigQueryTableName();
    Job job = bigQuery.getJob(jobId);

    if (job == null) {
      return;
    }

    if (!job.cancel()) {
      LOG.error("Unable to cancel BigQuery job '{}' for table '{}' and stage '{}'", jobId, tableName, stageName);
    }
  }

  /**
   * Deletes the BigQuery table for the supplied dataset
   *
   * @param stageName the name of the stage in CDAP
   * @param bqDataset the BigQuery Dataset Instance
   */
  protected void deleteTable(String stageName, BigQuerySQLDataset bqDataset) throws BigQueryException {
    String tableName = bqDataset.getBigQueryTableName();
    TableId tableId = TableId.of(project, dataset, tableName);

    if (!bigQuery.delete(tableId)) {
      LOG.error("Unable to delete BigQuery table '{}' for stage '{}'", tableName, stageName);
    }
  }

  /**
   * Deletes the temporary folder used by a certain BQ dataset.
   *
   * @param bqDataset the BigQuery Dataset Instance
   */
  protected void deleteTempFolder(BigQuerySQLDataset bqDataset) throws IOException {
    String gcsPath = bqDataset.getGCSPath();

    // If this dataset does not use temporary storage, skip this step
    if (gcsPath == null) {
      return;
    }

    BigQueryUtil.deleteTemporaryDirectory(configuration, gcsPath);
  }

  /**
   * Validate that this schema is supported in BigQuery.
   * <p>
   * We don't support CDAP types ENUM, MAP and UNION in BigQuery. However, we do support Unions
   *
   * @param schema input schema
   * @return boolean determining if this schema is supported in BQ.
   */
  @VisibleForTesting
  protected static SchemaValidation validateSchema(Schema schema) {
    List<String> invalidFields = new ArrayList<>();

    for (Schema.Field field : schema.getFields()) {
      Schema fieldSchema = field.getSchema();

      // For nullable types, check the underlying type.
      if (fieldSchema.isNullable()) {
        fieldSchema = fieldSchema.getNonNullable();
      }

      if (fieldSchema.getType() == Schema.Type.ENUM || fieldSchema.getType() == Schema.Type.MAP
        || fieldSchema.getType() == Schema.Type.UNION) {
        invalidFields.add(field.getName());
      }
    }

    return new SchemaValidation(invalidFields.isEmpty(), invalidFields);
  }

  /**
   * Ensure the Stage name is valid for execution in BQ pushdown.
   *
   * Due to differences in character escaping rules in Spark and BigQuery, identifiers that are accepted in Spark
   * might not be valid in BigQuery. Due to this limitation, we don't support stage names or aliases containing
   * backslash \ or backtick ` characters at this time.
   *
   * @param identifier stage name or alias to validate
   * @return whether this stage name is valid for BQ Pushdown.
   */
  @VisibleForTesting
  protected static boolean isValidIdentifier(String identifier) {
    return identifier != null && !identifier.contains("\\") && !identifier.contains("`");
  }

  /**
   * Used to return Schema Validation results.
   */
  protected static final class SchemaValidation {
    private final boolean isSupported;
    private final List<String> invalidFields;

    public SchemaValidation(boolean isSupported, List<String> invalidFields) {
      this.isSupported = isSupported;
      this.invalidFields = invalidFields;
    }

    public boolean isSupported() {
      return isSupported;
    }

    public List<String> getInvalidFields() {
      return invalidFields;
    }
  }
}