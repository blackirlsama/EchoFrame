package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceAttributes;
import com.tianmu.agent.harness.trace.TraceDetails;
import com.tianmu.agent.runtime.control.CallControl;
import com.fasterxml.jackson.databind.*;
import io.opentelemetry.api.trace.Span;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.io.*;

/** Single shared credential, independently selected model IDs; no prompt/body/header logging or implicit retries. */
public final class DeepSeekClient {
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final com.tianmu.agent.evaluation.admission.ProviderHttpTransport http;
    private final boolean nonReplayable;
    private final com.tianmu.agent.model.ModelContextBuilder contexts;
    private final com.tianmu.agent.model.HarnessContextBuilder harnessContexts;
    private final boolean tailProjectionEnabled;
    private final io.opentelemetry.api.trace.Tracer tracer;
    /** Single compensation consultation point. Production keeps {@link ModelRecovery#NONE}. */
    private final com.tianmu.agent.model.ModelRecovery recovery;
    private volatile long quotaBlockedUntil;
    private static final long QUOTA_COOLDOWN_NANOS=Duration.ofMinutes(5).toNanos();
    public DeepSeekClient(DeepSeekProperties config, ObjectMapper mapper) {
        this(config, mapper, com.tianmu.agent.model.ContextPolicy.defaults());
    }
    public DeepSeekClient(DeepSeekProperties config, ObjectMapper mapper, com.tianmu.agent.model.ContextPolicy contextPolicy) {
        this(config,mapper,contextPolicy,null);
    }
    public DeepSeekClient(DeepSeekProperties config,ObjectMapper mapper,com.tianmu.agent.model.ContextPolicy contextPolicy,io.opentelemetry.api.trace.Tracer tracer){
        this(config, mapper, contextPolicy, tracer, null);
    }
    public DeepSeekClient(DeepSeekProperties config, ObjectMapper mapper,
            com.tianmu.agent.model.ContextPolicy contextPolicy, io.opentelemetry.api.trace.Tracer tracer,
            com.tianmu.agent.evaluation.admission.ProviderHttpTransport transport) {
        this(config, mapper, contextPolicy, tracer, transport, com.tianmu.agent.model.ModelRecovery.NONE);
    }
    public DeepSeekClient(DeepSeekProperties config, ObjectMapper mapper,
            com.tianmu.agent.model.ContextPolicy contextPolicy, io.opentelemetry.api.trace.Tracer tracer,
            com.tianmu.agent.evaluation.admission.ProviderHttpTransport transport,
            com.tianmu.agent.model.ModelRecovery recovery) {
        this.tracer=tracer;
        this.recovery = recovery == null ? com.tianmu.agent.model.ModelRecovery.NONE : recovery;
        tailProjectionEnabled=contextPolicy.tailProjectionEnabled();
        contexts = new com.tianmu.agent.model.ModelContextBuilder(mapper, contextPolicy);
        harnessContexts = new com.tianmu.agent.model.HarnessContextBuilder(mapper,contextPolicy);
        this.config = config; this.mapper = mapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        nonReplayable = transport != null;
        http = transport != null ? transport : com.tianmu.agent.evaluation.admission.ProviderHttpTransport.using(
                HttpClient.newBuilder().connectTimeout(config.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    public List<Map<String, String>> messages(String instruction, Map<String, ?> input) { return contexts.build(instruction, input); }
    private com.tianmu.agent.model.ModelContextBuilder contexts(CallControl control) {
        return control.frozenContext()==null?contexts:new com.tianmu.agent.model.ModelContextBuilder(mapper,com.tianmu.agent.runtime.control.RunPolicies.context(control.frozenContext()));
    }
    public List<Map<String,String>> messages(String instruction,Map<String,?> input,CallControl control){return contexts(control).build(instruction,input);}
    public List<Map<String,Object>> harnessMessages(String instruction,com.tianmu.agent.core.AgentTypes.ModelContext context) { return harnessContexts.build(instruction,context); }
    public List<Map<String,Object>> harnessMessages(String instruction,com.tianmu.agent.core.AgentTypes.ModelContext context,CallControl control) {
        return harnessMessages(instruction,context,control,"");
    }
    public List<Map<String,Object>> harnessMessages(String instruction,com.tianmu.agent.core.AgentTypes.ModelContext context,CallControl control,String requiredAction) {
        var builder=control.frozenContext()==null?harnessContexts:new com.tianmu.agent.model.HarnessContextBuilder(mapper,com.tianmu.agent.runtime.control.RunPolicies.context(control.frozenContext()));
        return builder.build(instruction,context,requiredAction,harnessProjection(control));
    }
    String harnessProjection(CallControl control) {
        if(control.frozenContext()!=null)return control.frozenContext().projectionVersion();
        return defaultHarnessProjection();
    }
    String defaultHarnessProjection() {
        return tailProjectionEnabled ? com.tianmu.agent.core.ExecutionPolicySnapshot.Context.CURRENT_PROJECTION
                : com.tianmu.agent.core.ExecutionPolicySnapshot.Context.LEGACY_PROJECTION;
    }
    public JsonNode complete(String model, List<? extends Map<String, ?>> messages, int maxTokens, CallControl control) {
        return complete(model,messages,maxTokens,control,false);
    }
    public JsonNode complete(String model, List<? extends Map<String, ?>> messages, int maxTokens, CallControl control, boolean external) {
        return complete(model,messages,maxTokens,control,external,null);
    }
    public JsonNode completeHarness(String model,List<? extends Map<String,?>> messages,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> tools,int maxTokens,CallControl control) {
        return completeHarness(model,messages,tools,maxTokens,control,false);
    }
    public JsonNode completeHarness(String model,List<? extends Map<String,?>> messages,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> tools,int maxTokens,CallControl control,boolean requireTool) {
        return completeHarness(model,messages,tools,maxTokens,control,requireTool?"required":null);
    }
    public JsonNode completeHarness(String model,List<? extends Map<String,?>> messages,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> tools,int maxTokens,CallControl control,String requiredTool) {
        if(requiredTool!=null && (tools.isEmpty() || !"required".equals(requiredTool) && tools.stream().noneMatch(t->t.name().equals(requiredTool))))throw new AgentException("INVALID_DECISION","Required resource tool is not exposed");
        return complete(model,messages,maxTokens,control,false,List.copyOf(tools),requiredTool);
    }
    public JsonNode toolArguments(String text) {
        try {
            JsonNode value=mapper.readTree(text);
            if(value==null || !value.isObject())throw new IllegalArgumentException();
            return value;
        } catch(Exception e) { throw new AgentException("INVALID_DECISION","Invalid native tool arguments"); }
    }
    private JsonNode complete(String model,List<? extends Map<String,?>> messages,int maxTokens,CallControl control,boolean external,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> nativeTools) {
        return complete(model,messages,maxTokens,control,external,nativeTools,null);
    }
    private JsonNode complete(String model,List<? extends Map<String,?>> messages,int maxTokens,CallControl control,boolean external,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> nativeTools,String requiredTool) {
        return complete(model,messages,maxTokens,control,external,nativeTools,requiredTool,false);
    }
    public JsonNode completeFeedbackOnce(String model,List<? extends Map<String,?>> messages,CallControl control) {
        return complete(model,messages,1024,control,true,null,null,true,16384);
    }
    public JsonNode completeFeedbackDirect(String model,List<? extends Map<String,?>> messages,CallControl control) {
        return complete(model,messages,2048,control,true,null,null,true,65536);
    }
    /** Bounded structured background operation; no replayable HTTP body or implicit retry. */
    public JsonNode completeStructuredOnce(String model,List<? extends Map<String,?>> messages,int maxTokens,int maxInputBytes,CallControl control) {
        if(maxTokens<128 || maxTokens>16384 || maxInputBytes<1024 || maxInputBytes>1048576)
            throw new IllegalArgumentException("Invalid structured model limits");
        return complete(model,messages,maxTokens,control,true,null,null,true,maxInputBytes);
    }
    private JsonNode complete(String model,List<? extends Map<String,?>> messages,int maxTokens,CallControl control,boolean external,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> nativeTools,String requiredTool,boolean once) {
        return complete(model,messages,maxTokens,control,external,nativeTools,requiredTool,once,4096);
    }
    private JsonNode complete(String model,List<? extends Map<String,?>> messages,int maxTokens,CallControl control,boolean external,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> nativeTools,String requiredTool,boolean once,int onceBudget) {
        if(tracer==null)return invoke(model,messages,maxTokens,control,external,nativeTools,requiredTool,once,onceBudget);
        var span=tracer.spanBuilder("model.request").startSpan();
        com.tianmu.agent.harness.trace.TraceNode.begin(span,"model.request");
        TraceAttributes.apply(span,Map.of("model.dispatched",false,"model.name",model));
        TraceAttributes.apply(span,com.tianmu.agent.harness.trace.TraceModelCall.identity());
        String run=io.opentelemetry.context.Context.current().get(com.tianmu.agent.harness.trace.TraceUsage.RUN_ID);
        if(run!=null)TraceAttributes.apply(span,Map.of("run.id",run));
        try(var scope=span.makeCurrent()){
            var result=invoke(model,messages,maxTokens,control,external,nativeTools,requiredTool,once,onceBudget);
            com.tianmu.agent.harness.trace.TraceNode.success(span);return result;
        }catch(RuntimeException error){com.tianmu.agent.harness.trace.TraceNode.failure(span,error);span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);throw error;}
        finally{span.end();}
    }
    private JsonNode invoke(String model,List<? extends Map<String,?>> messages,int maxTokens,CallControl control,boolean external,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> nativeTools,String requiredTool,boolean once,int onceBudget) {        com.tianmu.agent.harness.trace.TracePrompt.capture(messages);
        TraceAttributes.apply(Span.current(),Map.of("model.version_state","REQUESTED_ONLY"));
        control.checkpoint();
        if(quotaBlockedUntil!=0 && System.nanoTime()-quotaBlockedUntil<0)
            throw new AgentException("MODEL_QUOTA_EXHAUSTED","Model provider quota is unavailable; calls paused for five minutes");
        contexts(control).check(messages);
        boolean streaming=nativeTools!=null && control.textOutput()!=null;
        TraceAttributes.apply(Span.current(),Map.of("model.tool_protocol",nativeTools==null?"json_structured":"native_tools"));
        // Declared before any trace attribute so the provider identity is recorded once, from the
        // same selection the request body uses.
        final String protocol=config.resolvedProtocol();
        TraceAttributes.apply(Span.current(), Map.of("model.name", model, "model.provider", protocol, "model.max_tokens", maxTokens,
                "model.temperature", 0, "model.streaming", streaming, "model.first_token_state", streaming?"PENDING":"NOT_APPLICABLE", "model.message_count", messages.size(), "model.reasoning_state", "DISABLED"));
        long nanos = Duration.between(Instant.now(), control.deadline()).toNanos();
        if (nanos <= 0) throw unavailable();
        try {
            Map<String,Object> requestBody=new LinkedHashMap<>();
            // Thinking fields are chosen by the documented contract of the provider the request is
            // actually sent to, not by the model name: deepseek.com documents a `thinking` object
            // plus reasoning_effort, siliconflow.cn documents `enable_thinking`/`thinking_budget`.
            boolean deepseekProtocol="deepseek".equals(protocol);
            boolean siliconflowProtocol="siliconflow".equals(protocol);
            // DeepSeek rejects named or required tool_choice in thinking mode.
            boolean thinking=deepseekProtocol && !once && config.thinkingEnabled() && requiredTool==null && (nativeTools!=null || external);
            TraceAttributes.apply(Span.current(),Map.of("model.reasoning_state",thinking?"ENABLED":"DISABLED"));
            TraceAttributes.apply(Span.current(),Map.of("model.thinking_contract",protocol));
            if(thinking){String effort=external?config.analysisThinkingEffort():config.thinkingEffort();requestBody.put("reasoning_effort",effort);TraceAttributes.apply(Span.current(),Map.of("model.reasoning_effort",effort));}
            if(nativeTools!=null && deepseekProtocol && config.thinkingEnabled()) {
                var replay=new ArrayList<Map<String,Object>>();
                for(var message:messages){var copy=new LinkedHashMap<String,Object>(message);if("assistant".equals(copy.get("role")))copy.putIfAbsent("reasoning_content","");replay.add(copy);}
                messages=replay;
            }
            requestBody.put("model",model);requestBody.put("messages",messages);
            if(deepseekProtocol)requestBody.put("thinking",Map.of("type",thinking?"enabled":"disabled"));
            // SiliconFlow slice: thinking stays off on every dispatch (both controllers symmetric).
            // enable_thinking=true with native tools is unverified and is a live-compat item, not a
            // value this slice pretends to have tested.
            if(siliconflowProtocol)requestBody.put("enable_thinking",false);
            requestBody.put("temperature",0);requestBody.put("stream",streaming);
            if(streaming)requestBody.put("stream_options",Map.of("include_usage",true));requestBody.put("max_tokens",maxTokens);
            if(nativeTools==null)requestBody.put("response_format",Map.of("type","json_object"));
            if(nativeTools!=null && !nativeTools.isEmpty()) {
                requestBody.put("tools",nativeTools.stream().map(t->Map.of("type","function","function",Map.of("name",t.name(),"description",t.description(),"parameters",t.inputSchema()))).toList());
                Object choice=requiredTool==null?"auto":"required".equals(requiredTool)?"required":Map.of("type","function","function",Map.of("name",requiredTool));
                boolean parallelSources=requiredTool==null
                        && (nativeTools.stream().anyMatch(t->java.util.Set.of("query_video_metrics","query_danmaku","read_result","analyze_video_content").contains(t.name()))
                        || nativeTools.stream().anyMatch(t->t.name().equals("analyze_comments")) && nativeTools.stream().anyMatch(t->t.name().equals("analyze_danmaku")));
                requestBody.put("tool_choice",choice);requestBody.put("parallel_tool_calls",parallelSources);
                TraceAttributes.apply(Span.current(),Map.of("model.tool_choice",requiredTool==null?"auto":requiredTool));
            }
            TraceAttributes.apply(Span.current(),com.tianmu.agent.model.ModelPayloadMetrics.measure(mapper,requestBody));
            contexts(control).check(List.of(Map.of("request",requestBody)));
            TraceDetails.capture("input", requestBody, "MODEL_REQUEST");
            if(once && mapper.writeValueAsBytes(messages).length>onceBudget)
                throw new AgentException("MODEL_CONTEXT_TOO_LARGE","Confirmation input exceeds the request budget");
            byte[] payload = mapper.writeValueAsBytes(requestBody);
            // Stage 4B-1 fee lifecycle: admit before dispatch; this dispatch's own response settles
            // its reservation, any dispatch failure retains it. Null handle = gate disarmed.
            // The exit key names the host the bytes actually go to: a price row belongs to the
            // endpoint that charges, so no official host is ever synthesised for a local double.
            // the CLIENT send boundary names the role from its own call shape: JSON-mode
            // feedback calls (no native tools) serve the analysis leg, tool-carrying calls serve
            // the controller loop — recorded here, never annotated by the receiving side
            String clientRole = external && nativeTools == null ? "analysis" : "harness";
            String base = config.baseUrl().toString().replaceAll("/+$", "");
            String payloadSha = com.tianmu.agent.evaluation.EvaluationRunManifest.sha256(payload);
            long reservation=(long)payload.length+maxTokens+1024;
            JsonNode body = null;
            for (int attempt = 1; ; attempt++) {
                long attemptNanos = Duration.between(Instant.now(), control.deadline()).toNanos();
                if (attemptNanos <= 0) throw new com.tianmu.agent.model.TransportFailure("MODEL_UNAVAILABLE",
                        "Model response unavailable", com.tianmu.agent.model.TransportFault.of(
                                com.tianmu.agent.model.TransportFault.Kind.WAIT_TIMEOUT, 0));
                var emitted = new java.util.concurrent.atomic.AtomicBoolean();
                com.tianmu.agent.evaluation.admission.EvaluationFeeGate.Reservation feeReservation = null;
                try {
                    http.beforeAdmission();
                    feeReservation = com.tianmu.agent.evaluation.admission.EvaluationFeeGate.admitChat(
                            model, config.baseUrl().getHost(), payload.length, maxTokens, clientRole);
                    // 4B-2A content repair: bind the serialized bytes to the reservation at THIS boundary,
                    // before anything leaves the process; the strict reconciliation cross-checks this
                    // digest against the receiving stub's own log, so recorded and sent are the same bytes.
                    // Disarmed production path: the gate returns null and nothing is computed or bound.
                    if (feeReservation != null) feeReservation.bindRequestDigest(payload.length, payloadSha);
                    HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                            .timeout(Duration.ofNanos(attemptNanos)).header("Authorization", "Bearer " + config.apiKey())
                            .header("Content-Type", "application/json").POST(once || nonReplayable ? oneShot(payload) : HttpRequest.BodyPublishers.ofByteArray(payload)).build();
                    final var bound = feeReservation;
                    body = com.tianmu.agent.runtime.control.ModelBudget.execute(control, model, external, reservation, () ->
                            streaming ? exchangeStream(request, control, payload, bound, emitted)
                                    : exchange(request, control, payload, bound));
                    if (feeReservation != null) {
                        try {
                            feeReservation.settle(body);
                        } catch (com.tianmu.agent.evaluation.admission.EvaluationFeeLedger.Blocked feeUnknown) {
                            // The delivered result stays valid; the fee evidence is unknown and the ledger
                            // itself now blocks every further paid dispatch until a human resolves it.
                            TraceAttributes.apply(Span.current(), Map.of("model.fee_state", "USAGE_UNKNOWN"));
                        }
                    }
                    break;
                } catch (RuntimeException error) {
                    // Typed cause, when this attempt produced one. An ordinary stop code carries none
                    // and stays exactly as it was: one attempt, one retained row, no consultation.
                    com.tianmu.agent.model.TransportFault fault = com.tianmu.agent.model.TransportFailure.faultOf(error);
                    com.tianmu.agent.model.ModelRecovery.CallAttempt call = fault == null ? null
                            : identity(fault, clientRole, model, attempt, feeReservation, payload.length, payloadSha);
                    com.tianmu.agent.model.ModelRecovery.Decision decision = fault == null
                            ? com.tianmu.agent.model.ModelRecovery.Decision.STOP
                            : consult(fault, clientRole, model, attempt, feeReservation, payload.length,
                                    payloadSha, remainingMillis(control));
                    // Bounded compensation: the policy declares how many re-offers one logical call
                    // may make (one by default), and never after any streamed token reached the caller.
                    boolean compensate = fault != null && attempt <= 1 + recovery.maxCompensationsPerCall()
                            && decision.compensate() && !emitted.get();
                    if (feeReservation != null) feeReservation.fail(fault == null
                            ? error.getClass().getSimpleName() : fault.kind().name(),
                            !compensate && decision.terminal());
                    if (!compensate) {
                        // The dispatch identity travels with every typed failure: a deferred one is
                        // re-sent by the fence owner, and a terminal one still needs the exact
                        // reservation/request digest in the owner's sanitized stop row.
                        if (error instanceof com.tianmu.agent.model.TransportFailure typed)
                            throw typed.withCall(call);
                        throw error;
                    }
                    TraceAttributes.apply(Span.current(), Map.of("model.compensation", fault.kind().name(),
                            "model.compensation_reason", decision.reason()));
                    pause(decision.delayMillis(), control);
                }
            }
            if (body == null) throw unavailable();
            TraceDetails.capture("provider_output", body, "MODEL_PROVIDER_RESPONSE");
            if(body.path("model").isTextual())TraceAttributes.apply(Span.current(),Map.of("model.resolved_name",body.path("model").asText(),"model.version_state","PROVIDER_REPORTED"));
            JsonNode choice = body.path("choices").path(0);
            var reasoning=choice.path("message").path("reasoning_content");
            if(reasoning.isTextual() && !reasoning.asText().isBlank()) {
                TraceAttributes.apply(Span.current(),Map.of("model.reasoning_state","RECEIVED"));TraceDetails.reasoning(reasoning.asText());
            }
            if (choice.path("finish_reason").isTextual()) TraceAttributes.apply(Span.current(), Map.of("model.finish_reason", choice.path("finish_reason").asText()));
            if("length".equals(choice.path("finish_reason").asText()))throw new AgentException("MODEL_OUTPUT_LIMIT","Model output budget exhausted before a complete result");
            if(nativeTools!=null && choice.path("message").path("tool_calls").isArray() && !choice.path("message").path("tool_calls").isEmpty()) {
                if(nativeTools.isEmpty() || !"tool_calls".equals(choice.path("finish_reason").asText()))throw new AgentException("INVALID_DECISION","Unexpected native tool call");
                if(requiredTool!=null && !"required".equals(requiredTool))for(var call:choice.path("message").path("tool_calls"))
                    if(!requiredTool.equals(call.path("function").path("name").asText()))throw new AgentException("INVALID_DECISION","Provider selected a tool outside the required resource step");
                return choice.path("message");
            }
            if(requiredTool!=null)throw new AgentException("INVALID_DECISION","Provider omitted the required resource tool call");
            if (!"stop".equals(choice.path("finish_reason").asText())) throw unavailable();
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual() || content.textValue().isBlank()) throw unavailable();
            if(nativeTools!=null)return choice.path("message");
            JsonNode result = responseObject(content.textValue());
            if (result == null || !result.isObject()) throw unavailable();
            return result;
        } catch (AgentException e) { throw e; }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            control.checkpoint();throw new AgentException("MODEL_ERROR","Model returned invalid structured JSON");
        }
        catch (Exception e) { control.checkpoint(); throw unavailable(); }
    }
    private static HttpRequest.BodyPublisher oneShot(byte[] payload){
        var delegate=HttpRequest.BodyPublishers.ofByteArray(payload);
        var sent=new java.util.concurrent.atomic.AtomicBoolean();
        return new HttpRequest.BodyPublisher(){
            public long contentLength(){return delegate.contentLength();}
            public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> subscriber){
                if(!sent.compareAndSet(false,true))throw new IllegalStateException("Confirmation request cannot be replayed");
                delegate.subscribe(subscriber);
            }
        };
    }
    private JsonNode responseObject(String content) throws com.fasterxml.jackson.core.JsonProcessingException {
        try { return mapper.readTree(content); }
        catch (com.fasterxml.jackson.core.JsonProcessingException original) {
            // Only redundant closing root braces can be removed. All values and duplicate-key checks remain intact.
            String candidate=content.stripTrailing();
            for(int i=0;i<2 && candidate.endsWith("}");i++) {
                candidate=candidate.substring(0,candidate.length()-1).stripTrailing();
                try {
                    JsonNode parsed=mapper.readTree(candidate);
                    if(parsed!=null && parsed.isObject()) {
                        TraceAttributes.apply(Span.current(),Map.of("model.response_repaired",true));
                        return parsed;
                    }
                } catch(com.fasterxml.jackson.core.JsonProcessingException ignored) {}
            }
            TraceAttributes.apply(Span.current(),Map.of("model.validation_error",original.getClass().getSimpleName()));
            throw original;
        }
    }
    private static final ScheduledExecutorService STREAM_DEADLINES=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"model-stream-deadlines");t.setDaemon(true);return t;});
    private void correctionAttempt(){if("REQUESTED".equals(com.tianmu.agent.harness.trace.TraceModelCall.identity().get("correction.state")))TraceAttributes.apply(Span.current(),Map.of("correction.state","ATTEMPTED","correction.attempted",true));}
    private JsonNode exchangeStream(HttpRequest request, CallControl control, byte[] payload,
            com.tianmu.agent.evaluation.admission.EvaluationFeeGate.Reservation fee,
            java.util.concurrent.atomic.AtomicBoolean progressed) {
        long started=System.nanoTime();var first=new java.util.concurrent.atomic.AtomicBoolean();
        control.beforeDispatch();
        correctionAttempt();
        TraceAttributes.apply(Span.current(),Map.of("model.dispatched",true));
        var future=http.send(request, HttpResponse.BodyHandlers.ofInputStream(), payload, fee);
        var body=new java.util.concurrent.atomic.AtomicReference<InputStream>();
        Runnable close=()->{future.cancel(true);var stream=body.get();if(stream!=null)try{stream.close();}catch(IOException ignored){}};
        long remaining=Math.max(1,Duration.between(Instant.now(),control.deadline()).toMillis());
        var timeout=STREAM_DEADLINES.schedule(close,remaining,TimeUnit.MILLISECONDS);
        try(var registration=control.cancellation().onCancel(close)){
            var response=future.get(remaining,TimeUnit.MILLISECONDS);body.set(response.body());control.checkpoint();
            if(!Instant.now().isBefore(control.deadline()))throw failure("MODEL_UNAVAILABLE","Model response deadline exceeded",com.tianmu.agent.model.TransportFault.Kind.WAIT_TIMEOUT,0,-1,started);
            TraceAttributes.apply(Span.current(),Map.of("model.http_status",response.statusCode()));
            requireSuccess(response.statusCode(),response.headers(),started,"MODEL_UNAVAILABLE","MODEL_UNAVAILABLE");
            // A 200 on a stream means the provider accepted the request and may already have produced
            // (and billed) tokens, so no later break may be re-sent under this logical call.
            progressed.set(true);
            TraceAttributes.apply(Span.current(),Map.of("model.streaming",true));
            if(!response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
                TraceAttributes.apply(Span.current(),Map.of("model.streaming",false,"model.first_token_state","NOT_APPLICABLE"));
                return parse(readBounded(response.body(),131072,started),started);
            }
            var sink=control.textOutput();
            java.util.function.Consumer<String> guarded=sink==null?null:(text->{if(text!=null&&!text.isEmpty())progressed.set(true);sink.accept(text);});
            var parser=new NativeSseResponse(mapper,guarded,()->{if(first.compareAndSet(false,true))TraceAttributes.apply(Span.current(),Map.of("model.first_token_state","RECEIVED","model.first_token_ms",(System.nanoTime()-started)/1_000_000));});
            var line=new ByteArrayOutputStream();var data=new StringBuilder();int total=0,next;
            while((next=response.body().read())!=-1){
                if(++total>2097152||line.size()>131072)throw failure("MODEL_UNAVAILABLE","Model response too large",com.tianmu.agent.model.TransportFault.Kind.OVERSIZE,0,-1,started);
                control.checkpoint();
                if(next!=10){line.write(next);continue;}
                String text=line.toString(java.nio.charset.StandardCharsets.UTF_8).replaceFirst("\\r$","");line.reset();
                if(text.isEmpty()){if(!data.isEmpty()){parser.accept(data.toString());data.setLength(0);if(parser.done())break;}}
                else if(text.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(text.substring(5).stripLeading());if(data.length()>131072)throw failure("MODEL_UNAVAILABLE","Model response too large",com.tianmu.agent.model.TransportFault.Kind.OVERSIZE,0,-1,started);}
            }
            if(line.size()>0){String tail=line.toString(java.nio.charset.StandardCharsets.UTF_8).stripTrailing();if(tail.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(tail.substring(5).stripLeading());}}
            if(!data.isEmpty())parser.accept(data.toString());return parser.result();
        }catch(AgentException e){throw e;}catch(Exception e){
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            if(nonReplayable && (e instanceof TimeoutException || !Instant.now().isBefore(control.deadline())))
                throw new com.tianmu.agent.model.TransportFailure("MODEL_CALL_TIMEOUT", "Model response deadline exceeded",
                        com.tianmu.agent.model.TransportFault.of(com.tianmu.agent.model.TransportFault.Kind.WAIT_TIMEOUT, millis(started)));
            control.checkpoint();
            throw new com.tianmu.agent.model.TransportFailure("MODEL_UNAVAILABLE", "Model response unavailable", classify(e, started));
        }
        finally{TraceAttributes.apply(Span.current(),Map.of("model.response_ms",millis(started)));timeout.cancel(false);close.run();}
    }
    private JsonNode exchange(HttpRequest request, CallControl control, byte[] payload,
            com.tianmu.agent.evaluation.admission.EvaluationFeeGate.Reservation fee) {
        long requestStarted = System.nanoTime();
        try {
            control.beforeDispatch();
            correctionAttempt();
            TraceAttributes.apply(Span.current(),Map.of("model.dispatched",true));
            CompletableFuture<HttpResponse<byte[]>> future = http.send(request, info -> new com.tianmu.agent.resource.BoundedHttpBody(131072), payload, fee);
            try (var registration = control.cancellation().onCancel(() -> future.cancel(true))) {
                HttpResponse<byte[]> response;
                try { response = future.get(Math.max(1, Duration.between(Instant.now(), control.deadline()).toNanos()), TimeUnit.NANOSECONDS); }
                catch (TimeoutException e) { future.cancel(true); throw failure("MODEL_UNAVAILABLE","Model response unavailable",com.tianmu.agent.model.TransportFault.Kind.WAIT_TIMEOUT,0,-1,requestStarted); }
                catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw failure("MODEL_UNAVAILABLE","Model response unavailable",com.tianmu.agent.model.TransportFault.Kind.CANCELLED,0,-1,requestStarted); }
                catch (CancellationException e) { control.checkpoint(); throw failure("MODEL_UNAVAILABLE","Model response unavailable",com.tianmu.agent.model.TransportFault.Kind.CANCELLED,0,-1,requestStarted); }
                catch (java.util.concurrent.ExecutionException e) { control.checkpoint(); throw transport(e.getCause(), requestStarted); }
                control.checkpoint();
                TraceAttributes.apply(Span.current(), Map.of("model.http_status", response.statusCode(), "model.response_ms", millis(requestStarted)));
                requireSuccess(response.statusCode(), response.headers(), requestStarted, "MODEL_UNAVAILABLE", "MODEL_REQUEST_REJECTED");
                return parse(response.body(), requestStarted);
            }
        } catch (AgentException e) { throw e; }
        catch (Exception e) { control.checkpoint(); throw new com.tianmu.agent.model.TransportFailure("MODEL_UNAVAILABLE","Model response unavailable", classify(e, requestStarted)); }
        finally{TraceAttributes.apply(Span.current(),Map.of("model.response_ms",millis(requestStarted)));}
    }
    /** Status handling shared by both exchange paths: the status code is recorded where it is read.
     *  Auth/quota keep their historical codes on both paths; only the transient pair is typed. */
    private void requireSuccess(int status, java.net.http.HttpHeaders headers, long started,
            String unavailableCode, String rejectedCode) {
        if (status == 401 || status == 403) throw failure("MODEL_AUTH_FAILED", "Model provider authentication failed",
                com.tianmu.agent.model.TransportFault.Kind.AUTH, status, -1, started);
        if (status == 402) {
            quotaBlockedUntil = System.nanoTime() + QUOTA_COOLDOWN_NANOS;
            throw failure("MODEL_QUOTA_EXHAUSTED", "Model provider rejected the quota; calls paused for five minutes",
                    com.tianmu.agent.model.TransportFault.Kind.QUOTA, status, -1, started);
        }
        if (status == 429) throw failure(unavailableCode, "Model provider rate limited the request",
                com.tianmu.agent.model.TransportFault.Kind.HTTP_429, status, retryAfter(headers), started);
        if (status >= 500) throw failure(unavailableCode, "Model provider is temporarily unavailable",
                com.tianmu.agent.model.TransportFault.Kind.HTTP_5XX, status, retryAfter(headers), started);
        if (status != 200) throw failure(rejectedCode, "Model provider rejected request",
                com.tianmu.agent.model.TransportFault.Kind.REJECTED, status, -1, started);
    }
    private static long retryAfter(java.net.http.HttpHeaders headers) {
        String raw = headers.firstValue("Retry-After").orElse("").trim();
        try {
            long seconds = Long.parseLong(raw);
            return seconds >= 0 && seconds <= 60 ? seconds * 1000 : -1;
        } catch (NumberFormatException notSeconds) { return -1; }
    }
    private static byte[] readBounded(InputStream stream, int limit, long started) throws IOException {
        byte[] bytes = stream.readNBytes(limit + 1);
        if (bytes.length > limit) throw failure("MODEL_UNAVAILABLE", "Model response too large",
                com.tianmu.agent.model.TransportFault.Kind.OVERSIZE, 0, -1, started);
        return bytes;
    }
    private JsonNode parse(byte[] bytes, long started) {
        try { return bytes.length == 0 ? null : mapper.readTree(bytes); }
        catch (IOException invalid) {
            throw failure("MODEL_UNAVAILABLE", "Model response unavailable",
                    com.tianmu.agent.model.TransportFault.Kind.PARSE_FAILED, 0, -1, started);
        }
    }
    private static com.tianmu.agent.model.TransportFailure transport(Throwable cause, long started) {
        Throwable root = cause;
        for (int depth = 0; depth < 4 && root != null
                && (root instanceof java.util.concurrent.ExecutionException
                    || root instanceof java.util.concurrent.CompletionException); depth++)
            root = root.getCause();
        return new com.tianmu.agent.model.TransportFailure("MODEL_UNAVAILABLE", "Model response unavailable",
                classify(root, started));
    }
    /** Only the transport type decides the kind; prose is never parsed to recover a cause. */
    private static com.tianmu.agent.model.TransportFault classify(Throwable cause, long started) {
        long elapsed = millis(started);
        if (cause instanceof java.net.http.HttpConnectTimeoutException)
            return com.tianmu.agent.model.TransportFault.of(com.tianmu.agent.model.TransportFault.Kind.CONNECT_FAILED, elapsed);
        if (cause instanceof java.net.ConnectException || cause instanceof java.nio.channels.UnresolvedAddressException)
            return com.tianmu.agent.model.TransportFault.of(com.tianmu.agent.model.TransportFault.Kind.CONNECT_FAILED, elapsed);
        if (cause instanceof java.net.http.HttpTimeoutException)
            return com.tianmu.agent.model.TransportFault.of(com.tianmu.agent.model.TransportFault.Kind.WAIT_TIMEOUT, elapsed);
        if (cause instanceof java.io.IOException io) {
            if ("Response limit exceeded".equals(io.getMessage()))
                return com.tianmu.agent.model.TransportFault.of(com.tianmu.agent.model.TransportFault.Kind.OVERSIZE, elapsed);
            return com.tianmu.agent.model.TransportFault.of(com.tianmu.agent.model.TransportFault.Kind.READ_BROKEN, elapsed);
        }
        if (cause instanceof java.util.concurrent.TimeoutException)
            return com.tianmu.agent.model.TransportFault.of(com.tianmu.agent.model.TransportFault.Kind.WAIT_TIMEOUT, elapsed);
        if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException)
            return com.tianmu.agent.model.TransportFault.of(com.tianmu.agent.model.TransportFault.Kind.PARSE_FAILED, elapsed);
        return com.tianmu.agent.model.TransportFault.of(com.tianmu.agent.model.TransportFault.Kind.UNKNOWN, elapsed);
    }
    private static com.tianmu.agent.model.TransportFailure failure(String code, String message,
            com.tianmu.agent.model.TransportFault.Kind kind, int status, long retryAfterMillis, long started) {
        return new com.tianmu.agent.model.TransportFailure(code, message,
                com.tianmu.agent.model.TransportFault.http(kind, status, retryAfterMillis, millis(started)));
    }
    private static long millis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
    /** One consultation; a faulty observer can only refuse the compensation, never break the call. */
    private com.tianmu.agent.model.ModelRecovery.Decision consult(com.tianmu.agent.model.TransportFault fault,
            String role, String model, int attempt,
            com.tianmu.agent.evaluation.admission.EvaluationFeeGate.Reservation reservation,
            long bodyBytes, String bodySha256, long remainingMillis) {
        try {
            return recovery.onFailure(fault, identity(fault, role, model, attempt, reservation, bodyBytes, bodySha256),
                    remainingMillis);
        } catch (RuntimeException observer) {
            return com.tianmu.agent.model.ModelRecovery.Decision.STOP;
        }
    }

    /** Budget left for this logical call; negative means "unknown, do not judge expiry". */
    private static long remainingMillis(CallControl control) {
        try {
            return Math.max(0, java.time.Duration.between(java.time.Instant.now(), control.deadline()).toMillis());
        } catch (RuntimeException unknown) {
            return -1;
        }
    }
    /**
     * The exact dispatch that failed, assembled only where all of it is known: the client's own
     * role/attempt and the reservation bound to these bytes at this boundary.
     */
    private static com.tianmu.agent.model.ModelRecovery.CallAttempt identity(
            com.tianmu.agent.model.TransportFault fault, String role, String model, int attempt,
            com.tianmu.agent.evaluation.admission.EvaluationFeeGate.Reservation reservation,
            long bodyBytes, String bodySha256) {
        return new com.tianmu.agent.model.ModelRecovery.CallAttempt(role, model, attempt,
                reservation == null ? "" : reservation.reservationId(), bodyBytes, bodySha256,
                fault.elapsedMillis());
    }
    /** Backoff sits inside the same logical call, so a cancel during the wait cannot send anything. */
    private static void pause(long millis, CallControl control) {
        if (millis > 0) {
            try { Thread.sleep(millis); }
            catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                throw new AgentException("CANCELLED", "Model compensation interrupted");
            }
        }
        control.checkpoint();
    }
    private AgentException unavailable() { return new AgentException("MODEL_UNAVAILABLE","Model response unavailable"); }
}
