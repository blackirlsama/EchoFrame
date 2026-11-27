package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Captures the bytes the product ReportController actually serializes under demo/mock, so the
 * evaluation tooling is validated against the real product format instead of a hand-written report.
 * Evidence level is product_http_mockmvc: no HTTP server is started, no real model, no formal material.
 *
 * The product rejects explicit ranges older than 30 days (DanmakuTypes.Query.checkTime). Batch 3
 * pins the submit-time business clock to a fixed task instant, so the delivered captures keep the
 * same window forever; production time validation is unchanged (the service defaults to the real
 * system clock and only the business-date rule reads the injected one).
 */
@SpringBootTest(properties={"agent.preparation.deterministic-routing=true","agent.policy-snapshot.enabled=true","agent.feedback.compression.enabled=true","harness.trace.directory=./var/test-report-bridge-trace"})
@AutoConfigureMockMvc
@Import(ReportEvaluationBridgeHttpTest.Vectors.class)
class ReportEvaluationBridgeHttpTest {
    @TestConfiguration static class Vectors {
        @Bean FeedbackEmbedding bridgeEmbedding(){return new FeedbackEmbedding(){public String version(){return "fixture-text-v1";}public int dimension(){return 3;}public List<List<Float>> embed(List<String> input,com.tianmu.agent.runtime.control.CallControl c){return input.stream().map(t->List.of(1f,0f,0f)).toList();}};}
        @Bean FeedbackVectorIndex bridgeVectors(){var v=mock(FeedbackVectorIndex.class);when(v.dimension()).thenReturn(3);when(v.fetch(any(),anyList(),any())).thenReturn(Map.of());return v;}
    }
    @Autowired RunService runs;@Autowired MockMvc mvc;@Autowired ObjectMapper mapper;
    @Autowired FeedbackAnalysisService analysis;
    @MockBean ModelPort model;@MockBean FeedbackSnapshotPort source;
    @Value("${harness.trace.directory}") String traceDirectory;
    final AccessScope owner=new AccessScope("demo","alice",Set.of(1001L));
    final AtomicBoolean empty=new AtomicBoolean(),authorized=new AtomicBoolean(true);
    static final String START="2026-11-05";
    static final String END="2026-11-12";
    /** Fixed task clock: 11-05..11-12 stays inside the 30-day rule and 10-10..10-20 stays outside,
     *  so reruns never age out of the product's validation. */
    static final java.time.Instant TASK_NOW=java.time.Instant.parse("2026-11-21T00:00:00Z");
    static final java.time.Clock TASK_CLOCK=java.time.Clock.fixed(TASK_NOW,java.time.ZoneId.of("Asia/Shanghai"));
    static final String PROMPT=prompt(START,END);
    /** Older than the product's 30-day limit, so the product itself refuses and asks for a new range. */
    static final String OUT_OF_RANGE_PROMPT=prompt("2026-10-10","2026-10-20");
    static final String CAPTURE_PROPERTY="report.bridge.capture.dir";

    static String prompt(String start,String end){
        return "分析视频1001从"+start+"到"+end+"的评论和弹幕，生成观众反馈HTML报告，不读取视频内容";
    }

    @BeforeEach void script(){
        analysis.clock(TASK_CLOCK);
        empty.set(false);authorized.set(true);
        doAnswer(inv->{if(!authorized.get())throw new AgentException("FORBIDDEN","fixture revoked");return null;}).when(source).authorize(any(),anyLong());
        when(source.export(any(),any(),any(),any())).thenAnswer(inv->{Request request=inv.getArgument(0);java.util.function.Consumer<List<Unit>> sink=inv.getArgument(3);var rows=new ArrayList<Unit>();
            if(!empty.get())for(var s:request.sources())for(int i=1;i<=20;i++)rows.add(new Unit((s==Source.COMMENT?"C:":"D:")+i,s,""+i,i==1?"背景音乐盖住了人声":"声音太小听不清楚","",false,s==Source.DANMAKU?1000L:null,"viewer"+i,request.query().end().minusSeconds(1).atZone(DanmakuTypes.ZONE).toInstant(),i==1?120L:1L));
            sink.accept(rows);return new FeedbackSnapshotPort.Receipt(Instant.now(),new Counts(rows.stream().filter(r->r.source()==Source.COMMENT).count(),rows.stream().filter(r->r.source()==Source.DANMAKU).count(),0,0,rows.stream().map(Unit::userId).distinct().count()));});
        when(model.decide(any(),any())).thenAnswer(inv->{ModelContext c=inv.getArgument(0);
            if(!c.activeSkills().contains("audience-report"))return Decision.tool("read_skill",Map.of("name","audience-report"));
            var materials=c.observations().stream().filter(r->r.tool().equals("prepare_feedback_materials")).findFirst();
            if(materials.isEmpty())return Decision.tool("prepare_feedback_materials",Map.of());
            return Decision.tool("generate_feedback_report",Map.of("materials_id",materials.get().data().get("materials_id")));
        });
    }

    @Test void capturesDeliveredProductReportBytesForEvaluationBridge()throws Exception{
        capture("product-success",PROMPT,false,Status.SUCCEEDED);
    }
    @Test void capturesLegitimateEmptyProductReportWithoutInventingTopics()throws Exception{
        capture("product-empty",PROMPT,true,Status.SUCCEEDED);
    }
    @Test void capturesNeedsInputWithoutReportWhenProductRefusesTheRange()throws Exception{
        capture("product-needs-input",OUT_OF_RANGE_PROMPT,false,Status.NEEDS_INPUT);
    }

    private void capture(String bundleId,String prompt,boolean noData,Status expected)throws Exception{
        empty.set(noData);
        var run=runs.create(prompt,owner);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime()<deadline)Thread.sleep(20);
        assertEquals(expected,run.snapshot().status(),()->String.valueOf(run.snapshot().errorCode()));
        String runId=run.id.toString();
        Path root=captureRoot().resolve(bundleId);
        Files.createDirectories(root);

        var manifest=new LinkedHashMap<String,Object>();
        manifest.put("schema_version",1);
        manifest.put("bundle_id",bundleId);
        manifest.put("adapter_contract","product-bundle-v1");
        manifest.put("evidence_level","product_http_mockmvc");
        manifest.put("material_class","synthetic_fixture");
        manifest.put("producer",producer(bundleId));
        manifest.put("request",request(prompt));
        manifest.put("notes",List.of(
                "MockMvc contract capture; no HTTP server started, no real model, no formal material.",
                "ModelPort is a Mockito mock, so no dispatched model.request spans exist; token usage stays unknown.",
                "The product limits explicit ranges to the previous 30 days; the submit-time business clock is pinned to 2026-11-21 so reruns keep the same window."));

        var runRecord=new LinkedHashMap<String,Object>();
        runRecord.put("run_id",runId);
        runRecord.put("final_status",run.snapshot().status().name());
        runRecord.put("clarification",run.snapshot().clarification());
        runRecord.put("error_code",run.snapshot().errorCode());

        if(expected==Status.SUCCEEDED){
            var receipt=run.execution().observations().stream().filter(r->r.tool().equals("generate_feedback_report")).findFirst().orElseThrow();
            String jsonUrl=receipt.data().get("json_url").toString();
            String htmlUrl=receipt.data().get("url").toString();
            String artifactId=receipt.data().get("artifact_id").toString();
            var jsonResponse=mvc.perform(get(jsonUrl).header("Authorization","Bearer demo-alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.schemaVersion").value("feedback-report-json-v1"))
                    .andExpect(jsonPath("$.scope.video_id").value("1001"))
                    .andExpect(jsonPath("$.scope.start").value(START+"T00:00"))
                    .andExpect(jsonPath("$.scope.end").value(END+"T00:00"))
                    .andExpect(jsonPath("$.method.raw").value(noData?0:40))
                    .andReturn().getResponse();
            byte[] jsonBytes=jsonResponse.getContentAsByteArray();
            var htmlResponse=mvc.perform(get(htmlUrl).header("Authorization","Bearer demo-alice"))
                    .andExpect(status().isOk()).andReturn().getResponse();
            byte[] htmlBytes=htmlResponse.getContentAsByteArray();
            assertTrue(htmlBytes.length>0,"product HTML must not be empty");
            assertTrue(new String(htmlBytes,StandardCharsets.UTF_8).startsWith("<!doctype html>"),"product must emit HTML");

            Files.createDirectories(root.resolve("responses"));
            Files.write(root.resolve("responses/report.json"),jsonBytes);
            Files.write(root.resolve("responses/report.html"),htmlBytes);
            var responses=new LinkedHashMap<String,Object>();
            responses.put("json",responseRecord("responses/report.json",jsonResponse.getContentType(),jsonBytes));
            responses.put("html",responseRecord("responses/report.html",htmlResponse.getContentType(),htmlBytes));

            var checks=new ArrayList<Map<String,Object>>();
            checks.add(accessCheck("unauthenticated",mvc.perform(get(jsonUrl)).andReturn().getResponse().getStatus(),null));
            checks.add(accessCheck("cross_account",mvc.perform(get(jsonUrl).header("Authorization","Bearer demo-bob")).andReturn().getResponse().getStatus(),"Bearer demo-bob"));
            checks.add(accessCheck("unknown_artifact",mvc.perform(get(jsonUrl.replace(artifactId,artifactId+"_0000")).header("Authorization","Bearer demo-alice")).andReturn().getResponse().getStatus(),"Bearer demo-alice"));
            authorized.set(false);
            checks.add(accessCheck("revoked_authorization",mvc.perform(get(jsonUrl).header("Authorization","Bearer demo-alice")).andReturn().getResponse().getStatus(),"Bearer demo-alice"));
            authorized.set(true);
            for(var check:checks)assertNotEquals(200,check.get("http_status"),"a denied read must never be captured as a delivery");

            runRecord.put("artifact_id",artifactId);
            runRecord.put("evidence_id",receipt.data().get("evidence_id").toString());
            runRecord.put("receipt_tool","generate_feedback_report");
            runRecord.put("receipt",receipt.data());
            runRecord.put("json_url",jsonUrl);
            runRecord.put("html_url",htmlUrl);
            manifest.put("responses",responses);
            manifest.put("access_checks",checks);
        }else{
            assertTrue(run.execution().observations().stream().noneMatch(r->r.tool().equals("generate_feedback_report")),
                    "a refused range must not produce a report artifact");
            runRecord.put("artifact_id",null);
            runRecord.put("evidence_id",null);
            runRecord.put("receipt_tool",null);
            runRecord.put("receipt",null);
            runRecord.put("json_url",null);
            runRecord.put("html_url",null);
            var responses=new LinkedHashMap<String,Object>();
            responses.put("json",null);responses.put("html",null);
            manifest.put("responses",responses);
            manifest.put("access_checks",List.of());
        }
        manifest.put("run",runRecord);
        manifest.put("trace",captureTrace(root,runId));
        Files.writeString(root.resolve("bundle.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),StandardCharsets.UTF_8);
    }

    private static Map<String,Object> producer(String bundleId){
        var producer=new LinkedHashMap<String,Object>();
        producer.put("kind","product_http_mockmvc");
        producer.put("test_class","com.tianmu.agent.feedback.ReportEvaluationBridgeHttpTest");
        producer.put("test_method",bundleId);
        producer.put("maven_command","mvn -o -f agent/pom.xml -Dspring.profiles.active=demo -Dtest=ReportEvaluationBridgeHttpTest test");
        producer.put("captured_at",Instant.now().toString());
        producer.put("spring_profile","demo");
        producer.put("java_version",System.getProperty("java.version"));
        return producer;
    }

    private static Map<String,Object> request(String prompt){
        var request=new LinkedHashMap<String,Object>();
        request.put("prompt",prompt);
        request.put("owner_token","demo-alice");
        request.put("video_id","1001");
        request.put("sources",List.of("COMMENT","DANMAKU"));
        request.put("grounding","FEEDBACK_ONLY");
        return request;
    }

    private static Path captureRoot(){
        String configured=System.getProperty(CAPTURE_PROPERTY);
        Path root=Path.of(configured==null||configured.isBlank()?"./var/report-bridge-capture":configured);
        return root.toAbsolutePath().normalize();
    }

    private static Map<String,Object> accessCheck(String name,int status,String authorization){
        var check=new LinkedHashMap<String,Object>();
        check.put("name",name);check.put("http_status",status);check.put("authorization",authorization);
        return check;
    }

    private static Map<String,Object> responseRecord(String path,String contentType,byte[] bytes)throws Exception{
        var record=new LinkedHashMap<String,Object>();
        record.put("path",path);record.put("http_status",200);record.put("content_type",contentType);
        record.put("sha256",sha256(bytes));record.put("bytes",bytes.length);
        return record;
    }

    private static String sha256(byte[] bytes)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        var out=new StringBuilder();
        for(byte b:digest)out.append(String.format("%02x",b));
        return out.toString();
    }

    /** Copies only this run's real exported spans; never synthesizes a span. */
    private Map<String,Object> captureTrace(Path root,String runId)throws Exception{
        Path archive=Path.of(traceDirectory).resolve("archive").resolve("spans.jsonl");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        List<String> mine=new ArrayList<>();
        boolean rootSeen=false;
        while(System.nanoTime()<deadline){
            mine=new ArrayList<>();rootSeen=false;
            if(Files.isRegularFile(archive)){
                for(String line:Files.readAllLines(archive,StandardCharsets.UTF_8)){
                    if(line.isBlank())continue;
                    var node=mapper.readTree(line);
                    if(!runId.equals(node.path("attributes").path("run.id").asText(null)))continue;
                    mine.add(line);
                    if("agent.run".equals(node.path("name").asText()))rootSeen=true;
                }
            }
            if(rootSeen)break;
            Thread.sleep(50);
        }
        if(mine.isEmpty())return null;
        Files.createDirectories(root.resolve("traces"));
        byte[] bytes=(String.join("\n",mine)+"\n").getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("traces/spans.jsonl"),bytes);
        var trace=new LinkedHashMap<String,Object>();
        trace.put("path","traces/spans.jsonl");trace.put("sha256",sha256(bytes));trace.put("bytes",bytes.length);
        trace.put("span_count",mine.size());trace.put("run_id",runId);
        trace.put("source","harness_jsonl_archive");trace.put("root_span_observed",rootSeen);
        return trace;
    }
}