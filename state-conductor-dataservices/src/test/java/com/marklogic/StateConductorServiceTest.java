package com.marklogic;

import com.marklogic.client.FailedRequestException;
import com.marklogic.client.document.DocumentWriteSet;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.ext.AbstractStateConductorTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StateConductorServiceTest extends AbstractStateConductorTest {

  static Logger logger = LoggerFactory.getLogger(StateConductorServiceTest.class);

  final String data1Uri = "/test/doc1.json";
  final String data2Uri = "/test/doc2.json";
  final String job1Uri = "/test/stateConductorJob/job1.json";
  final String job2Uri = "/test/stateConductorJob/job2.json";

  StateConductorService mockService;
  StateConductorService service;

  @BeforeEach
  public void setup() throws IOException {
    // setup the service
    mockService = new StateConductorServiceMock();
    service = StateConductorService.on(getDatabaseClient());

    // replacement tokens
    Map<String, String> tokens = new HashMap<>();
    tokens.put("%DATABASE%", getContentDatabaseId());
    tokens.put("%MODULES%", getModulesDatabaseId());

    // add job docs
    DocumentWriteSet batch = getJobsManager().newWriteSet();
    DocumentMetadataHandle jobMeta = new DocumentMetadataHandle();
    jobMeta.getCollections().add("stateConductorJob");
    jobMeta.getPermissions().add("state-conductor-reader-role", DocumentMetadataHandle.Capability.READ);
    jobMeta.getPermissions().add("state-conductor-job-writer-role", DocumentMetadataHandle.Capability.UPDATE);
    batch.add("/test/stateConductorJob/job1.json", jobMeta, loadTokenizedResource("jobs/job1.json", tokens));
    batch.add("/test/stateConductorJob/job2.json", jobMeta, loadTokenizedResource("jobs/job2.json", tokens));
    batch.add("/test/stateConductorJob/job3.json", jobMeta, loadTokenizedResource("jobs/job3.json", tokens));
    batch.add("/test/stateConductorJob/job4.json", jobMeta, loadTokenizedResource("jobs/job4.json", tokens));
    batch.add("/test/stateConductorJob/job5.json", jobMeta, loadTokenizedResource("jobs/job5.json", tokens));
    getJobsManager().write(batch);

    // add data docs
    batch = getContentManager().newWriteSet();
    batch.add("/test/doc1.json", loadFileResource("data/doc1.json"));
    batch.add("/test/doc2.json", loadFileResource("data/doc2.json"));
    getContentManager().write(batch);

    // add flow docs
    DocumentMetadataHandle flowMeta = new DocumentMetadataHandle();
    flowMeta.getCollections().add("state-conductor-flow");
    batch = getContentManager().newWriteSet();
    batch.add("/state-conductor-flow/test-flow.asl.json", flowMeta, loadFileResource("flows/test-flow.asl.json"));
    getContentManager().write(batch);
  }

  @Test
  public void testGetJobsMock() {
    int count = 10;
    Object[] uris = mockService.getJobs(count, null, null).toArray();
    assertEquals(count, uris.length);
    assertEquals("/test/test1.json", uris[0].toString());
    assertEquals("/test/test2.json", uris[1].toString());
    assertEquals("/test/test10.json", uris[9].toString());
  }

  @Test
  public void testGetJobs() throws IOException {
    int count = 10;
    String[] status;
    String[] uris;
    StateConductorJob jobDoc;

    uris = service.getJobs(1000, null, null).toArray(String[]::new);
    assertTrue(3 <= uris.length);
    for (int i = 0; i < uris.length; i++) {
      String flowStatus = getJobDocument(uris[i]).getFlowStatus();
      assertTrue((flowStatus.equals("new") || flowStatus.equals("working")));
    }

    status = new String[]{ "new" };
    uris = service.getJobs(count, null, Arrays.stream(status)).toArray(String[]::new);
    assertTrue(2 <= uris.length);
    for (int i = 0; i < uris.length; i++) {
      assertEquals("new", getJobDocument(uris[i]).getFlowStatus());
    }

    status = new String[]{ "new" };
    uris = service.getJobs(count, "test-flow", Arrays.stream(status)).toArray(String[]::new);
    assertTrue(2 <= uris.length);
    for (int i = 0; i < uris.length; i++) {
      assertEquals("new", getJobDocument(uris[i]).getFlowStatus());
      assertEquals("test-flow", getJobDocument(uris[i]).getFlowName());
    }

    status = new String[]{ "working" };
    uris = service.getJobs(count, "test-flow", Arrays.stream(status)).toArray(String[]::new);
    assertEquals(1, uris.length);
    assertEquals("working", getJobDocument(uris[0]).getFlowStatus());
    assertEquals("test-flow", getJobDocument(uris[0]).getFlowName());
    assertEquals("job3", getJobDocument(uris[0]).getId());

    status = new String[]{ "complete" };
    uris = service.getJobs(count, "test-flow", Arrays.stream(status)).toArray(String[]::new);
    assertEquals(1, uris.length);
    assertEquals("complete", getJobDocument(uris[0]).getFlowStatus());
    assertEquals("test-flow", getJobDocument(uris[0]).getFlowName());
    assertEquals("job4", getJobDocument(uris[0]).getId());

    status = new String[]{ "failed" };
    uris = service.getJobs(count, "test-flow", Arrays.stream(status)).toArray(String[]::new);
    assertEquals(1, uris.length);
    assertEquals("failed", getJobDocument(uris[0]).getFlowStatus());
    assertEquals("test-flow", getJobDocument(uris[0]).getFlowName());
    assertEquals("job5", getJobDocument(uris[0]).getId());

    status = new String[]{ "complete", "failed" };
    uris = service.getJobs(count, "test-flow", Arrays.stream(status)).toArray(String[]::new);
    assertTrue(2 <= uris.length);
    for (int i = 0; i < uris.length; i++) {
      String flowStatus = getJobDocument(uris[i]).getFlowStatus();
      assertTrue((flowStatus.equals("complete") || flowStatus.equals("failed")));
      assertEquals("test-flow", getJobDocument(uris[i]).getFlowName());
    }

    uris = service.getJobs(count, "fake-flow", null).toArray(String[]::new);
    assertEquals(0, uris.length);
  }

  @Test
  public void testCreateJobMock() {
    String resp = mockService.createJob(data2Uri, "test-flow");
    assertTrue(resp.length() > 0);
  }

  @Test
  public void testCreateJob() throws IOException {
    String resp = service.createJob(data2Uri, "test-flow");
    assertTrue(resp.length() > 0);

    StateConductorJob jobDoc = getJobDocument("/stateConductorJob/" + resp + ".json");
    assertTrue(jobDoc != null);
    assertEquals(resp, jobDoc.getId());
    assertEquals(data2Uri, jobDoc.getUri());
    assertEquals("test-flow", jobDoc.getFlowName());

    assertThrows(FailedRequestException.class, () -> {
      service.createJob("/my/fake/document.json", "test-flow");
    });

    assertThrows(FailedRequestException.class, () -> {
      service.createJob(data2Uri, "my-fake-flow");
    });
  }

  @Test
  public void testProcessJobMock() {
    boolean resp = mockService.processJob("/test.json");
    assertEquals(true, resp);
  }

  @Test
  public void testProcessJob() throws IOException {
    boolean resp;
    DocumentMetadataHandle meta = new DocumentMetadataHandle();
    StateConductorJob job1Doc;

    // start job 1
    resp = service.processJob(job1Uri);
    job1Doc = getJobDocument(job1Uri);
    getContentManager().readMetadata(data1Uri, meta);
    assertEquals(true, resp);
    assertEquals(false, meta.getCollections().contains("testcol1"));
    assertEquals(false, meta.getCollections().contains("testcol2"));
    assertEquals("test-flow", job1Doc.getFlowName());
    assertEquals("working", job1Doc.getFlowStatus());
    assertEquals("add-collection-1", job1Doc.getFlowState());
    // continue job 1
    resp = service.processJob(job1Uri);
    job1Doc = getJobDocument(job1Uri);
    getContentManager().readMetadata(data1Uri, meta);
    assertEquals(true, resp);
    assertEquals(true, meta.getCollections().contains("testcol1"));
    assertEquals(false, meta.getCollections().contains("testcol2"));
    assertEquals("test-flow", job1Doc.getFlowName());
    assertEquals("working", job1Doc.getFlowStatus());
    assertEquals("add-collection-2", job1Doc.getFlowState());
    // continue job 1
    resp = service.processJob(job1Uri);
    job1Doc = getJobDocument(job1Uri);
    getContentManager().readMetadata(data1Uri, meta);
    assertEquals(true, resp);
    assertEquals(true, meta.getCollections().contains("testcol1"));
    assertEquals(true, meta.getCollections().contains("testcol2"));
    assertEquals("test-flow", job1Doc.getFlowName());
    assertEquals("working", job1Doc.getFlowStatus());
    assertEquals("success", job1Doc.getFlowState());
    // continue job 1
    resp = service.processJob(job1Uri);
    job1Doc = getJobDocument(job1Uri);
    assertEquals(true, resp);
    assertEquals("test-flow", job1Doc.getFlowName());
    assertEquals("complete", job1Doc.getFlowStatus());
    assertEquals("success", job1Doc.getFlowState());
    // end job 1
    resp = service.processJob(job1Uri);
    job1Doc = getJobDocument(job1Uri);
    assertEquals(false, resp);
    assertEquals("test-flow", job1Doc.getFlowName());
    assertEquals("complete", job1Doc.getFlowStatus());
    assertEquals("success", job1Doc.getFlowState());
  }

}
