package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Environment-aware dependency inspection and installation without arbitrary shell synthesis. */
public final class WorkspacePackageModelToolRegistry {
    public static final String INSPECT="package.inspect", INSTALL="package.install";
    private static final List<ModelToolDefinition> DEFINITIONS=definitions();
    private WorkspacePackageModelToolRegistry(){}
    public static String version(){return "workspace-package-tools-v1";}
    public static List<ModelToolDefinition> modelTools(){return DEFINITIONS;}
    public static boolean supports(String id){return INSPECT.equals(id)||INSTALL.equals(id);}

    public static CompletableFuture<ModelToolResult> execute(UUID request,ModelToolCall call,boolean preapproved){
        if(call==null||!supports(call.toolId()))return CompletableFuture.completedFuture(failed(call,"unknown_package_tool","Unknown package capability."));
        if(INSPECT.equals(call.toolId()))return CompletableFuture.supplyAsync(()->inspect(call));
        String detail=string(call.arguments(),"manager","auto")+" install "+String.join(", ",strings(call.arguments(),"packages"));
        return ModelToolApproval.require(request,call,preapproved,"Dependency installation approval",detail).thenApply(ok->{
            if(!ok)return failed(call,"approval_required","Dependency installation was not approved.");
            try{return install(call);}catch(Exception e){return failed(call,"package_operation_failed",message(e));}
        });
    }
    private static ModelToolResult inspect(ModelToolCall c){
        try{
            JsonObject a=c.arguments()==null?new JsonObject():c.arguments(); String ws=string(a,"workspace","project");
            Path cwd=ModelWorkspaceRegistry.resolve(ws,string(a,"cwd",""),false).path(); String manager=detect(cwd,string(a,"manager","auto"));
            JsonObject out=new JsonObject();out.addProperty("manager",manager);out.addProperty("cwd",cwd.toString());
            JsonArray manifests=new JsonArray(); for(String n:List.of("requirements.txt","pyproject.toml","package.json","package-lock.json","pnpm-lock.yaml","yarn.lock","pom.xml","build.gradle","build.gradle.kts","Cargo.toml","Cargo.lock"))if(Files.exists(cwd.resolve(n)))manifests.add(n);
            out.add("manifests",manifests);
            List<String> cmd=switch(manager){case "pip"->List.of(python(cwd),"-m","pip","list","--format=json");case "npm"->List.of("npm","ls","--depth=0","--json");case "cargo"->List.of("cargo","metadata","--no-deps","--format-version","1");case "maven"->Files.isRegularFile(cwd.resolve("mvnw"))?List.of("bash",cwd.resolve("mvnw").toString(),"dependency:list","-DincludeScope=runtime"):List.of("mvn","dependency:list","-DincludeScope=runtime");case "gradle"->Files.isRegularFile(cwd.resolve("gradlew"))?List.of("bash",cwd.resolve("gradlew").toString(),"dependencies"):List.of("gradle","dependencies");default->List.of();};
            if(!cmd.isEmpty()){var r=WorkspaceProcessService.run(cwd,cmd,safeEnv(),45);out.addProperty("stdout",r.stdout());out.addProperty("stderr",r.stderr());out.addProperty("exitCode",r.exitCode());}
            return completed(c,out,"Inspected dependency environment.");
        }catch(Exception e){return failed(c,"package_inspect_failed",message(e));}
    }
    private static ModelToolResult install(ModelToolCall c)throws Exception{
        JsonObject a=c.arguments()==null?new JsonObject():c.arguments();String ws=string(a,"workspace","project");Path cwd=ModelWorkspaceRegistry.resolve(ws,string(a,"cwd",""),false).path();String m=detect(cwd,string(a,"manager","auto"));List<String> pkgs=strings(a,"packages");if(pkgs.isEmpty())throw new IllegalArgumentException("packages is required.");
        ArrayList<String> cmd=new ArrayList<>();switch(m){case"pip"->{cmd.add(python(cwd));cmd.add("-m");cmd.add("pip");cmd.add("install");}case"npm"->{cmd.add("npm");cmd.add("install");}case"cargo"->{cmd.add("cargo");cmd.add("add");}case"maven"->{if(Files.isRegularFile(cwd.resolve("mvnw"))){cmd.add("bash");cmd.add(cwd.resolve("mvnw").toString());}else cmd.add("mvn");cmd.add("dependency:get");for(String p:pkgs){cmd.add("-Dartifact="+p);break;}}case"gradle"->throw new IllegalArgumentException("Gradle dependencies are declaration-driven; edit build.gradle/build.gradle.kts then use workspace.build.");default->throw new IllegalArgumentException("Unsupported package manager: "+m);}
        if(!"maven".equals(m))cmd.addAll(pkgs);var r=WorkspaceProcessService.run(cwd,cmd,safeEnv(),integer(a,"timeoutSeconds",300,1,900));JsonObject out=runOut(m,cmd,r);return r.exitCode()==0&&!r.timedOut()?completed(c,out,"Dependency operation completed."):new ModelToolResult(c.id(),c.toolId(),"failed",out,r.timedOut()?"process_timeout":"process_exit_nonzero","Package manager did not complete successfully.");
    }
    private static String detect(Path cwd,String requested){if(!"auto".equalsIgnoreCase(requested))return requested.toLowerCase(Locale.ROOT);if(Files.exists(cwd.resolve("pyproject.toml"))||Files.exists(cwd.resolve("requirements.txt"))||Files.isDirectory(cwd.resolve(".venv")))return"pip";if(Files.exists(cwd.resolve("package.json")))return"npm";if(Files.exists(cwd.resolve("Cargo.toml")))return"cargo";if(Files.exists(cwd.resolve("pom.xml"))||Files.exists(cwd.resolve("mvnw")))return"maven";if(Files.exists(cwd.resolve("build.gradle"))||Files.exists(cwd.resolve("build.gradle.kts"))||Files.exists(cwd.resolve("gradlew")))return"gradle";return"pip";}
    private static String python(Path cwd){Path p=cwd.resolve(".venv/bin/python");return Files.isRegularFile(p)?p.toString():"python3";}
    private static JsonObject runOut(String m,List<String>cmd,WorkspaceProcessService.RunResult r){JsonObject o=new JsonObject();o.addProperty("manager",m);JsonArray a=new JsonArray();for(String s:cmd)a.add(s);o.add("argv",a);o.addProperty("stdout",r.stdout());o.addProperty("stderr",r.stderr());o.addProperty("exitCode",r.exitCode());o.addProperty("timedOut",r.timedOut());return o;}
    private static Map<String,String>safeEnv(){HashMap<String,String>e=new HashMap<>(System.getenv());e.keySet().removeIf(k->{String s=k.toLowerCase(Locale.ROOT);return s.contains("token")||s.contains("secret")||s.contains("password")||s.contains("credential")||s.contains("api_key")||s.contains("apikey");});return e;}
    private static List<ModelToolDefinition>definitions(){JsonObject common=object();props(common).add("workspace",str());props(common).add("cwd",str());props(common).add("manager",enm("auto","pip","npm","maven","gradle","cargo"));JsonObject ins=common.deepCopy();JsonObject install=common.deepCopy();props(install).add("packages",arrStr());props(install).add("timeoutSeconds",integerSchema(1,900));require(install,"packages");return List.of(new ModelToolDefinition(INSPECT,"Inspect dependency manifests and installed packages using the environment-appropriate package manager.",ins,List.of("automation_mode_enabled"),Set.of(),false,Duration.ofSeconds(50),true,false,Set.of("completed","failed"),ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.WORKSPACE,ToolExecutionPolicy.CostClass.MODERATE)),new ModelToolDefinition(INSTALL,"Install declared packages with pip/npm/Maven/Cargo. Requires approval; Gradle dependencies should be edited in the build file then built.",install,List.of("automation_mode_enabled"),Set.of("workspace_dependencies"),true,Duration.ofMinutes(15),true,true,Set.of("completed","failed"),ToolExecutionPolicy.validateOnlyMutation(ToolExecutionPolicy.CostClass.EXPENSIVE)));}
    private static JsonObject object(){JsonObject o=new JsonObject();o.addProperty("type","object");o.addProperty("additionalProperties",false);o.add("properties",new JsonObject());return o;}private static JsonObject props(JsonObject o){return o.getAsJsonObject("properties");}private static JsonObject str(){JsonObject o=new JsonObject();o.addProperty("type","string");return o;}private static JsonObject arrStr(){JsonObject o=new JsonObject();o.addProperty("type","array");o.add("items",str());return o;}private static JsonObject enm(String...v){JsonObject o=str();JsonArray a=new JsonArray();for(String s:v)a.add(s);o.add("enum",a);return o;}private static JsonObject integerSchema(int min,int max){JsonObject o=new JsonObject();o.addProperty("type","integer");o.addProperty("minimum",min);o.addProperty("maximum",max);return o;}private static void require(JsonObject o,String...n){JsonArray a=new JsonArray();for(String s:n)a.add(s);o.add("required",a);}private static String string(JsonObject a,String k,String f){return a!=null&&a.has(k)&&!a.get(k).isJsonNull()?a.get(k).getAsString().strip():f;}private static List<String>strings(JsonObject a,String k){if(a==null||!a.has(k)||!a.get(k).isJsonArray())return List.of();ArrayList<String>r=new ArrayList<>();a.getAsJsonArray(k).forEach(x->r.add(x.getAsString()));return r;}private static int integer(JsonObject a,String k,int f,int min,int max){int v=a!=null&&a.has(k)?a.get(k).getAsInt():f;return Math.max(min,Math.min(max,v));}
    private static ModelToolResult completed(ModelToolCall c,JsonObject o,String d){return new ModelToolResult(c.id(),c.toolId(),"completed",o,"",d);}private static ModelToolResult failed(ModelToolCall c,String code,String d){return new ModelToolResult(c==null?"":c.id(),c==null?"":c.toolId(),"failed",new JsonObject(),code,d==null?"":d);}private static String message(Throwable t){String s=t==null?"":t.getMessage();return s==null||s.isBlank()?t.getClass().getSimpleName():s;}
}
