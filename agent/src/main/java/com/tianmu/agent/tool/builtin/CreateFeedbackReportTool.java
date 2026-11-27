package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class CreateFeedbackReportTool implements AgentTool {
    private final FeedbackAnalysisService service;
    public CreateFeedbackReportTool(FeedbackAnalysisService service){this.service=service;}
    public String name(){return "create_feedback_report";}
    /**
     * Side-effect-free shape check before the handler: a mixed legacy/source call, a blank id, or
     * a source selection that differs from the frozen task is rejected here, so the existing
     * bounded correction loop can ask the model to fix it. No evidence is read, no job starts and
     * no report is written. The handler below keeps its own defensive checks.
     */
    public ToolArguments.Validation preflight(ToolCall call,ToolContext context){
        var arguments=call.arguments();var issues=new java.util.ArrayList<ToolArguments.Issue>();
        for(String key:List.of("evidence_id","comment_evidence_id","danmaku_evidence_id")){
            if(arguments.get(key) instanceof String text && text.isBlank())
                issues.add(new ToolArguments.Issue(key,ToolArguments.Problem.STRING));
        }
        boolean legacy=arguments.containsKey("evidence_id");
        boolean comment=arguments.containsKey("comment_evidence_id");
        boolean danmaku=arguments.containsKey("danmaku_evidence_id");
        if(legacy && (comment || danmaku)){
            issues.add(new ToolArguments.Issue("evidence_id",ToolArguments.Problem.EVIDENCE_MIXED));
            if(comment)issues.add(new ToolArguments.Issue("comment_evidence_id",ToolArguments.Problem.EVIDENCE_MIXED));
            if(danmaku)issues.add(new ToolArguments.Issue("danmaku_evidence_id",ToolArguments.Problem.EVIDENCE_MIXED));
            return new ToolArguments.Validation(issues,false);
        }
        if(legacy)return new ToolArguments.Validation(issues,false);
        var contract=context.analysisContract();
        if(contract==null || !contract.reportRequired())return new ToolArguments.Validation(issues,false);
        if(!comment && !danmaku){
            issues.add(new ToolArguments.Issue("$",ToolArguments.Problem.EVIDENCE_REQUIRED));
            return new ToolArguments.Validation(issues,false);
        }
        var selected=contract.textSources();
        for(var source:AnalysisContract.Source.values()){
            if(source==AnalysisContract.Source.VIDEO)continue;
            boolean expected=selected.contains(source);
            boolean provided=source==AnalysisContract.Source.COMMENT?comment:danmaku;
            if(expected!=provided)issues.add(new ToolArguments.Issue(
                    source==AnalysisContract.Source.COMMENT?"comment_evidence_id":"danmaku_evidence_id",
                    ToolArguments.Problem.EVIDENCE_SELECTION));
        }
        return new ToolArguments.Validation(issues,false);
    }
    public ToolResult execute(ToolCall call,ToolContext context){
        if(!call.arguments().containsKey("evidence_id"))return context.createPreparedReport(
                call.arguments().containsKey("comment_evidence_id")?ToolArguments.text(call,"comment_evidence_id"):null,
                call.arguments().containsKey("danmaku_evidence_id")?ToolArguments.text(call,"danmaku_evidence_id"):null);
        if(call.arguments().size()!=1)throw new AgentException("INVALID_ARGUMENTS","Do not mix legacy analysis and source evidence");
        var evidence=context.evidence(ToolArguments.text(call,"evidence_id"));
        if(evidence.feedback()==null || context.analysisContract()==null || !context.analysisContract().reportRequired())throw new AgentException("TASK_CONSTRAINT_VIOLATION","A requested feedback report requires completed analysis evidence");
        context.checkFeedback(evidence.feedback().request());var asset=service.asset(evidence.feedback().jobId(),context.scope(),evidence.feedback().request());
        var document=service.reportDocument(asset);
        var report=context.saveReport(evidence.id(),FeedbackReportRenderer.render(document));
        return document.immediateResult(name(),report,evidence.id());
    }
}
