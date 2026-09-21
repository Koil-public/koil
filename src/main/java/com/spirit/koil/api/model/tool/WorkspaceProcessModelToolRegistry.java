package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Inspect and control background processes launched by workspace.exec(background=true). */
public final class WorkspaceProcessModelToolRegistry {
    public static final String LIST = "process.list";
    public static final String STATUS = "process.status";
    public static final String OUTPUT = "process.output";
    public static final String STOP = "process.stop";
    private static final List<ModelToolDefinition> DEFINITIONS = definitions();
    private WorkspaceProcessModelToolRegistry() {}
    public static String version(){return "workspace-process-tools-v1";}
    public static List<ModelToolDefinition> modelTools(){return DEFINITIONS;}
    public static boolean supports(String id){return LIST.equals(id)||STATUS.equals(id)||OUTPUT.equals(id)||STOP.equals(id);}

    public static CompletableFuture<ModelToolResult> execute(UUID displayRequestId, ModelToolCall call, boolean preapproved) {
        if (call==null || !supports(call.toolId())) return CompletableFuture.completedFuture(failed(call,"unknown_process_tool","Unknown process capability."));
        if (!STOP.equals(call.toolId())) return CompletableFuture.completedFuture(read(call));
        String id=string(call.arguments(),"processId","");
        return ModelToolApproval.require(displayRequestId, call, preapproved, "Stop background process", id)
                .thenApply(ok -> !ok ? failed(call,"approval_required","Process termination was not approved.") : stop(call,id));
    }
    private static ModelToolResult read(ModelToolCall call){
        if (LIST.equals(call.toolId())) {
            JsonArray arr=new JsonArray();
            WorkspaceProcessService.list().stream().sorted(Comparator.comparingLong((WorkspaceProcessService.ManagedProcess p)->p.startedAt).reversed()).limit(32)
                    .forEach(p->arr.add(WorkspaceProcessService.describe(p,20)));
            JsonObject out=new JsonObject(); out.add("processes",arr); out.addProperty("count",arr.size()); return completed(call,out,"Listed managed background processes.");
        }
        String id=string(call.arguments(),"processId","");
        WorkspaceProcessService.ManagedProcess p=WorkspaceProcessService.get(id);
        if(p==null)return failed(call,"process_not_found","No managed process exists with that ID.");
        int lines=integer(call.arguments(),"tailLines",80,1,500);
        if (OUTPUT.equals(call.toolId())) {
            long outCursor=longValue(call.arguments(),"stdoutCursor",0L,0L,Long.MAX_VALUE);
            long errCursor=longValue(call.arguments(),"stderrCursor",0L,0L,Long.MAX_VALUE);
            return completed(call,WorkspaceProcessService.incrementalOutput(p,outCursor,errCursor,lines),"Read incremental process output; pass returned cursors to poll only new lines next time.");
        }
        JsonObject out=WorkspaceProcessService.describe(p,lines);
        return completed(call,out,"Read process status.");
    }
    private static ModelToolResult stop(ModelToolCall call,String id){
        WorkspaceProcessService.ManagedProcess p=WorkspaceProcessService.get(id);
        if(p==null)return failed(call,"process_not_found","No managed process exists with that ID.");
        boolean force=bool(call.arguments(),"force",false);
        WorkspaceProcessService.stop(id,force);
        return completed(call,WorkspaceProcessService.describe(p,40),force?"Force-terminated managed process.":"Requested graceful process termination.");
    }
    private static List<ModelToolDefinition> definitions(){
        List<ModelToolDefinition> t=new ArrayList<>();
        t.add(def(LIST,"List background processes started by workspace.exec with background=true.",object(),false));
        JsonObject selector=object(); props(selector).add("processId",stringSchema()); props(selector).add("tailLines",integerSchema(1,500)); require(selector,"processId");
        t.add(def(STATUS,"Inspect whether a managed process is still running, its PID, exit code, command, recent output, and output cursors.",selector,false));
        JsonObject output=selector.deepCopy(); props(output).add("stdoutCursor",integerSchema(0,Integer.MAX_VALUE)); props(output).add("stderrCursor",integerSchema(0,Integer.MAX_VALUE));
        t.add(def(OUTPUT,"Poll/stream only new bounded stdout/stderr from a managed process. Reuse returned stdoutCursor/stderrCursor on the next call; this never blocks waiting for more output.",output,false));
        JsonObject stop=object(); props(stop).add("processId",stringSchema()); props(stop).add("force",booleanSchema()); require(stop,"processId");
        t.add(new ModelToolDefinition(STOP,"Terminate a managed background process. Use force only when graceful termination fails.",stop,List.of("automation_mode_enabled"),Set.of("local_process"),true,Duration.ofSeconds(5),true,true,Set.of("completed","failed"),ToolExecutionPolicy.validateOnlyMutation(ToolExecutionPolicy.CostClass.CHEAP)));
        return List.copyOf(t);
    }
    private static ModelToolDefinition def(String id,String d,JsonObject s,boolean ignored){return new ModelToolDefinition(id,d,s,List.of("automation_mode_enabled"),Set.of(),false,Duration.ofSeconds(3),false,false,Set.of("completed","failed"),ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.LIVE, ToolExecutionPolicy.CostClass.CHEAP));}
    private static JsonObject object(){JsonObject o=new JsonObject();o.addProperty("type","object");o.addProperty("additionalProperties",false);o.add("properties",new JsonObject());return o;}
    private static JsonObject props(JsonObject o){return o.getAsJsonObject("properties");}
    private static JsonObject stringSchema(){JsonObject o=new JsonObject();o.addProperty("type","string");return o;}
    private static JsonObject booleanSchema(){JsonObject o=new JsonObject();o.addProperty("type","boolean");return o;}
    private static JsonObject integerSchema(int min,int max){JsonObject o=new JsonObject();o.addProperty("type","integer");o.addProperty("minimum",min);o.addProperty("maximum",max);return o;}
    private static void require(JsonObject o,String...n){JsonArray a=new JsonArray();for(String s:n)a.add(s);o.add("required",a);}
    private static String string(JsonObject a,String k,String f){return a!=null&&a.has(k)&&!a.get(k).isJsonNull()?a.get(k).getAsString().strip():f;}
    private static boolean bool(JsonObject a,String k,boolean f){return a!=null&&a.has(k)&&!a.get(k).isJsonNull()?a.get(k).getAsBoolean():f;}
    private static int integer(JsonObject a,String k,int f,int min,int max){int v=a!=null&&a.has(k)?a.get(k).getAsInt():f;return Math.max(min,Math.min(max,v));}
    private static long longValue(JsonObject a,String k,long f,long min,long max){long v=a!=null&&a.has(k)?a.get(k).getAsLong():f;return Math.max(min,Math.min(max,v));}
    private static ModelToolResult completed(ModelToolCall c,JsonObject o,String d){return new ModelToolResult(c.id(),c.toolId(),"completed",o,"",d);}
    private static ModelToolResult failed(ModelToolCall c,String code,String d){return new ModelToolResult(c==null?"":c.id(),c==null?"":c.toolId(),"failed",new JsonObject(),code,d);}
}
