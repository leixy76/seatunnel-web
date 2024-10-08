/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.seatunnel.app.test;

import org.apache.seatunnel.app.common.Result;
import org.apache.seatunnel.app.common.SeaTunnelWebCluster;
import org.apache.seatunnel.app.controller.JobControllerWrapper;
import org.apache.seatunnel.app.controller.JobExecutorControllerWrapper;
import org.apache.seatunnel.app.controller.SeatunnelDatasourceControllerWrapper;
import org.apache.seatunnel.app.domain.dto.job.SeaTunnelJobInstanceDto;
import org.apache.seatunnel.app.domain.request.datasource.DatasourceReq;
import org.apache.seatunnel.app.domain.request.job.JobCreateReq;
import org.apache.seatunnel.app.domain.request.job.JobExecParam;
import org.apache.seatunnel.app.domain.request.job.PluginConfig;
import org.apache.seatunnel.app.domain.response.executor.JobExecutionStatus;
import org.apache.seatunnel.app.domain.response.executor.JobExecutorRes;
import org.apache.seatunnel.app.domain.response.metrics.JobPipelineDetailMetricsRes;
import org.apache.seatunnel.app.utils.JobUtils;
import org.apache.seatunnel.engine.core.job.JobStatus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JobExecutorControllerTest {
    private static final SeaTunnelWebCluster seaTunnelWebCluster = new SeaTunnelWebCluster();
    private static JobExecutorControllerWrapper jobExecutorControllerWrapper;
    private static final String uniqueId = "_" + System.currentTimeMillis();
    private static SeatunnelDatasourceControllerWrapper seatunnelDatasourceControllerWrapper;
    private static JobControllerWrapper jobControllerWrapper;

    @BeforeAll
    public static void setUp() {
        seaTunnelWebCluster.start();
        jobExecutorControllerWrapper = new JobExecutorControllerWrapper();
        seatunnelDatasourceControllerWrapper = new SeatunnelDatasourceControllerWrapper();
        jobControllerWrapper = new JobControllerWrapper();
    }

    @Test
    public void executeJob_shouldReturnSuccess_whenValidRequest() {
        String jobName = "execJob" + uniqueId;
        long jobVersionId = JobUtils.createJob(jobName);
        Result<Long> result = jobExecutorControllerWrapper.jobExecutor(jobVersionId);
        assertTrue(result.isSuccess());
        assertTrue(result.getData() > 0);
        Result<List<JobPipelineDetailMetricsRes>> listResult =
                JobUtils.waitForJobCompletion(result.getData());
        assertEquals(1, listResult.getData().size());
        assertEquals("FINISHED", listResult.getData().get(0).getStatus());
        assertEquals(5, listResult.getData().get(0).getReadRowCount());
        assertEquals(5, listResult.getData().get(0).getWriteRowCount());
    }

    @Test
    public void executeJobWithParameters() {
        String jobName = "execJobWithParam" + uniqueId;
        long jobVersionId = JobUtils.createJob(jobName);
        Result<Long> result = jobExecutorControllerWrapper.jobExecutor(jobVersionId);
        assertTrue(result.isSuccess());
        assertTrue(result.getData() > 0);
        Result<List<JobPipelineDetailMetricsRes>> listResult =
                JobUtils.waitForJobCompletion(result.getData());
        assertEquals(1, listResult.getData().size());
        assertEquals("FINISHED", listResult.getData().get(0).getStatus());
        assertEquals(5, listResult.getData().get(0).getReadRowCount());
        assertEquals(5, listResult.getData().get(0).getWriteRowCount());
        String generatedJobFile = getGenerateJobFile(String.valueOf(jobVersionId));
        assertTrue(generatedJobFile.contains("\"is_regex\"=\"false\""));
        assertTrue(generatedJobFile.contains("\"log.print.delay.ms\"=\"100\""));

        JobExecParam jobExecParam = new JobExecParam();

        Map<String, String> envConf = new HashMap<>();
        envConf.put("job.name", "executeJobWithParameters");
        jobExecParam.setEnv(envConf);
        Map<String, Map<String, String>> tasks = new HashMap<>();
        jobExecParam.setTasks(tasks);

        int numberOfRecords = 100;
        Map<String, String> task1Config = new HashMap<>();
        task1Config.put("row.num", String.valueOf(numberOfRecords));
        tasks.put("source-fakesource", task1Config);

        Map<String, String> task2Config = new HashMap<>();
        task2Config.put("replace_first", "true");
        task2Config.put("is_regex", "true");
        tasks.put("transform-replace", task2Config);

        Map<String, String> task3Config = new HashMap<>();
        task3Config.put("log.print.delay.ms", "99");
        tasks.put("sink-console", task3Config);

        result = jobExecutorControllerWrapper.jobExecutor(jobVersionId, jobExecParam);
        assertTrue(result.isSuccess());
        assertTrue(result.getData() > 0);
        listResult = JobUtils.waitForJobCompletion(result.getData());
        assertEquals(1, listResult.getData().size());
        assertEquals("FINISHED", listResult.getData().get(0).getStatus());
        assertEquals(numberOfRecords, listResult.getData().get(0).getReadRowCount());
        assertEquals(numberOfRecords, listResult.getData().get(0).getWriteRowCount());

        // Do few validations on the generated job file
        generatedJobFile = getGenerateJobFile(String.valueOf(jobVersionId));
        assertTrue(generatedJobFile.contains("\"is_regex\"=\"true\""));
        assertTrue(generatedJobFile.contains("\"replace_first\"=\"true\""));
        // database properties except query can not be updated
        assertFalse(generatedJobFile.contains("\"log.print.delay.ms\"=\"99\""));
        assertTrue(generatedJobFile.contains("\"job.name\"=executeJobWithParameters"));
    }

    @Test
    public void executeJobWithParameters_AllowQueryUpdate() {
        String jobName = "execJobUpdateQuery" + uniqueId;
        JobCreateReq jobCreateReq = JobUtils.populateMySQLJobCreateReqFromFile();
        jobCreateReq.getJobConfig().setName(jobName);
        jobCreateReq.getJobConfig().setDescription(jobName + " description");
        String datasourceName = "execJobUpdateQuery_db" + uniqueId;
        String mysqlDatasourceId =
                seatunnelDatasourceControllerWrapper.createMysqlDatasource(datasourceName);
        for (PluginConfig pluginConfig : jobCreateReq.getPluginConfigs()) {
            pluginConfig.setDataSourceId(Long.parseLong(mysqlDatasourceId));
        }
        Result<Long> job = jobControllerWrapper.createJob(jobCreateReq);
        assertTrue(job.isSuccess());
        Long jobVersionId = job.getData();
        Result<Long> result = jobExecutorControllerWrapper.jobExecutor(jobVersionId);
        // Fails because of the wrong credentials of the database.
        assertFalse(result.isSuccess());
        String generatedJobFile = getGenerateJobFile(String.valueOf(jobVersionId));
        assertTrue(
                generatedJobFile.contains(
                        "query=\"SELECT `name`, `age` FROM `test`.`test_table`\""));
        assertTrue(generatedJobFile.contains("user=someUser"));
        assertTrue(generatedJobFile.contains("password=somePassword"));

        JobExecParam jobExecParam = new JobExecParam();
        Map<String, Map<String, String>> tasks = new HashMap<>();
        jobExecParam.setTasks(tasks);

        Map<String, String> task1Config = new HashMap<>();
        task1Config.put("query", "SELECT `name`, `age` FROM `test`.`test_table` LIMIT 10");
        task1Config.put("user", "otherUser");
        task1Config.put("password", "otherPassword");
        tasks.put("mysql_source_1", task1Config);

        result = jobExecutorControllerWrapper.jobExecutor(jobVersionId, jobExecParam);
        assertFalse(result.isSuccess());
        // query should be changed but other database details should not be changed,
        generatedJobFile = getGenerateJobFile(String.valueOf(jobVersionId));
        assertTrue(
                generatedJobFile.contains(
                        "query=\"SELECT `name`, `age` FROM `test`.`test_table` LIMIT 10\""));
        assertTrue(generatedJobFile.contains("user=someUser"));
        assertTrue(generatedJobFile.contains("password=somePassword"));
    }

    @Test
    public void executeJobWithParameters_ChangeDatabase() {
        String jobName = "execJobChangeDatabase" + uniqueId;
        JobCreateReq jobCreateReq = JobUtils.populateMySQLJobCreateReqFromFile();
        jobCreateReq.getJobConfig().setName(jobName);
        jobCreateReq.getJobConfig().setDescription(jobName + " description");
        String datasourceName = "execJobChangeDatabase_db_1" + uniqueId;
        String mysqlDatasourceId =
                seatunnelDatasourceControllerWrapper.createMysqlDatasource(datasourceName);
        for (PluginConfig pluginConfig : jobCreateReq.getPluginConfigs()) {
            pluginConfig.setDataSourceId(Long.parseLong(mysqlDatasourceId));
        }
        Result<Long> job = jobControllerWrapper.createJob(jobCreateReq);
        assertTrue(job.isSuccess());
        Long jobVersionId = job.getData();
        Result<Long> result = jobExecutorControllerWrapper.jobExecutor(jobVersionId);
        // Fails because of the wrong credentials of the database.
        assertFalse(result.isSuccess());
        String generatedJobFile = getGenerateJobFile(String.valueOf(jobVersionId));
        assertTrue(
                generatedJobFile.contains(
                        "query=\"SELECT `name`, `age` FROM `test`.`test_table`\""));
        assertTrue(generatedJobFile.contains("user=someUser"));
        assertTrue(generatedJobFile.contains("password=somePassword"));

        String datasourceName2 = "execJobChangeDatabase_db_2" + uniqueId;
        DatasourceReq req = getDatasourceReq(datasourceName2);

        Result<String> datasource = seatunnelDatasourceControllerWrapper.createDatasource(req);
        assertTrue(datasource.isSuccess());
        JobExecParam jobExecParam = new JobExecParam();
        Map<String, String> dbConfigs = new HashMap<>();
        jobExecParam.setDatasource(dbConfigs);

        dbConfigs.put("mysql_source_1", datasource.getData());

        result = jobExecutorControllerWrapper.jobExecutor(jobVersionId, jobExecParam);
        assertFalse(result.isSuccess());
        // query should be changed but other database details should not be changed,
        generatedJobFile = getGenerateJobFile(String.valueOf(jobVersionId));
        assertTrue(
                generatedJobFile.contains(
                        "query=\"SELECT `name`, `age` FROM `test`.`test_table`\""));
        assertTrue(generatedJobFile.contains("user=someUser2"));
        assertTrue(generatedJobFile.contains("password=somePassword2"));
    }

    private static DatasourceReq getDatasourceReq(String datasourceName2) {
        DatasourceReq req = new DatasourceReq();
        req.setDatasourceName(datasourceName2);
        req.setPluginName("JDBC-Mysql");
        req.setDescription(datasourceName2 + " description");
        req.setDatasourceConfig(
                "{\"url\":\"jdbc:mysql://localhost:3306/test?useSSL=false&useUnicode=true&characterEncoding=utf-8&allowMultiQueries=true&allowPublicKeyRetrieval=true\",\"driver\":\"com.mysql.cj.jdbc.Driver\",\"user\":\"someUser2\",\"password\":\"somePassword2\"}");
        return req;
    }

    @Test
    public void restoreJob_shouldReturnSuccess_whenValidRequest() {
        String jobName = "jobRestore" + uniqueId;
        long jobVersionId = JobUtils.createJob(jobName);
        Result<Long> executorResult = jobExecutorControllerWrapper.jobExecutor(jobVersionId);
        assertTrue(executorResult.isSuccess());
        Result<Void> result = jobExecutorControllerWrapper.jobRestore(executorResult.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    public void getResource_shouldReturnSuccess_whenValidRequest() {
        String jobName = "getResource" + uniqueId;
        long jobVersionId = JobUtils.createJob(jobName);
        Result<JobExecutorRes> result = jobExecutorControllerWrapper.resource(jobVersionId);
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
    }

    @Test
    public void executeJob_JobStatusUpdate_WhenSubmissionFailed() {
        String jobName = "execJobStatus" + uniqueId;
        JobCreateReq jobCreateReq = JobUtils.populateMySQLJobCreateReqFromFile();
        jobCreateReq.getJobConfig().setName(jobName);
        jobCreateReq.getJobConfig().setDescription(jobName + " description");
        String datasourceName = "execJobStatus_db_1" + uniqueId;
        String mysqlDatasourceId =
                seatunnelDatasourceControllerWrapper.createMysqlDatasource(datasourceName);
        for (PluginConfig pluginConfig : jobCreateReq.getPluginConfigs()) {
            pluginConfig.setDataSourceId(Long.parseLong(mysqlDatasourceId));
        }
        Result<Long> job = jobControllerWrapper.createJob(jobCreateReq);
        assertTrue(job.isSuccess());
        Long jobVersionId = job.getData();
        Result<Long> result = jobExecutorControllerWrapper.jobExecutor(jobVersionId);
        // Fails because of the wrong database credentials.
        assertFalse(result.isSuccess());
        // Even though job failed but job instance is created into the database.
        Long jobInstanceId = result.getData();
        assertTrue(jobInstanceId > 0);
        Result<JobExecutionStatus> jobExecutionStatusResult =
                jobExecutorControllerWrapper.getJobExecutionStatus(jobInstanceId);
        assertTrue(jobExecutionStatusResult.isSuccess());
        assertEquals(JobStatus.FAILED.name(), jobExecutionStatusResult.getData().getJobStatus());
        assertNotNull(jobExecutionStatusResult.getData().getErrorMessage());

        // Invalid jobInstanceId
        Result<JobExecutionStatus> jobExecutionStatusResult2 =
                jobExecutorControllerWrapper.getJobExecutionStatus(123L);
        assertFalse(jobExecutionStatusResult2.isSuccess());
        assertEquals(404, jobExecutionStatusResult2.getCode());
    }

    @Test
    public void storeErrorMessageWhenJobFailed() throws InterruptedException {
        String jobName = "failureCause" + uniqueId;
        long jobVersionId = JobUtils.createJob(jobName, true);
        Result<Long> result = jobExecutorControllerWrapper.jobExecutor(jobVersionId);
        // job submitted successfully but it will fail during execution
        assertTrue(result.isSuccess());
        assertTrue(result.getData() > 0);
        Long jobInstanceId = result.getData();
        JobUtils.waitForJobCompletion(jobInstanceId);
        // extra second to let the data get updated in the database
        Thread.sleep(2000);
        Result<SeaTunnelJobInstanceDto> jobExecutionDetailResult =
                jobExecutorControllerWrapper.getJobExecutionDetail(jobInstanceId);
        assertTrue(jobExecutionDetailResult.isSuccess());
        assertEquals(JobStatus.FAILED.name(), jobExecutionDetailResult.getData().getJobStatus());
        assertNotNull(jobExecutionDetailResult.getData().getErrorMessage());
        assertNotNull(jobExecutionDetailResult.getData().getJobDefineName());

        // Invalid jobInstanceId
        Result<SeaTunnelJobInstanceDto> jobExecutionDetailResult2 =
                jobExecutorControllerWrapper.getJobExecutionDetail(123L);
        assertFalse(jobExecutionDetailResult2.isSuccess());
        assertEquals(404, jobExecutionDetailResult2.getCode());
    }

    @AfterAll
    public static void tearDown() {
        seaTunnelWebCluster.stop();
    }

    private String getGenerateJobFile(String jobId) {
        String filePath = "profile/" + jobId + ".conf";
        String jsonContent;
        try {
            jsonContent = new String(Files.readAllBytes(Paths.get(filePath)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return jsonContent;
    }
}
