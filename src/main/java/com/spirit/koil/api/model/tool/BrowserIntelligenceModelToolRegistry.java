package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Koil-owned Browser Intelligence model surface with a local HTTP provider. */
public final class BrowserIntelligenceModelToolRegistry {
    public static final String HEALTH="browser.health", NAVIGATE="browser.navigate", SNAPSHOT="browser.snapshot",
            CAPTURE="browser.capture", TEXT="browser.text", FIND="browser.find", INTERACT="browser.interact",
            WAIT="browser.wait", SCREENSHOT="browser.screenshot", PDF="browser.pdf", TABS="browser.tabs",
            NETWORK="browser.network", DIALOG="browser.dialog", COOKIES="browser.cookies",
            AUDIT="browser.audit", COMPARE="browser.compare";
    private static final Map<String,ModelToolDefinition> DEFINITIONS=definitions();
    private static volatile BrowserProvider provider=new PinchTabBrowserProvider();
    private BrowserIntelligenceModelToolRegistry(){}

    public static List<ModelToolDefinition> modelTools(){return List.copyOf(DEFINITIONS.values());}
    public static boolean supports(String id){return id!=null&&DEFINITIONS.containsKey(id);}
    public static boolean readOnly(String id){return Set.of(HEALTH,NAVIGATE,SNAPSHOT,CAPTURE,TEXT,FIND,WAIT,SCREENSHOT,PDF,AUDIT,COMPARE).contains(id);}
    public static void configureProvider(BrowserProvider value){provider=value==null?new PinchTabBrowserProvider():value;}

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call){
        if(call==null||!supports(call.toolId())) return CompletableFuture.completedFuture(failure(call,"unknown_browser_tool","Unknown browser capability."));
        return CompletableFuture.supplyAsync(()->{
            try{
                JsonObject a=call.arguments()==null?new JsonObject():call.arguments();
                JsonObject out=switch(call.toolId()){
                    case HEALTH -> provider.health();
                    case NAVIGATE -> provider.navigate(required(a,"url"),optional(a,"tabId"),bool(a,"newTab",false),integer(a,"timeoutMillis",30_000,1_000,60_000));
                    case SNAPSHOT -> provider.snapshot(optional(a,"tabId"),optional(a,"filter"),defaulted(a,"format","compact"),integer(a,"depth",0,0,20),bool(a,"diff",false));
                    case CAPTURE -> provider.capture(optional(a,"tabId"),bool(a,"requirePair",true),decimal(a,"scale",1.0D,0.1D,2.0D),bool(a,"beyondViewport",false));
                    case TEXT -> provider.text(optional(a,"tabId"),bool(a,"raw",false),integer(a,"maxChars",16_000,256,32_000));
                    case FIND -> provider.find(optional(a,"tabId"),required(a,"query"));
                    case INTERACT -> provider.interact(required(a,"tabId"),actionPayload(a));
                    case WAIT -> provider.waitFor(optional(a,"tabId"),conditionPayload(a));
                    case SCREENSHOT -> provider.screenshot(optional(a,"tabId"),defaulted(a,"format","jpeg"),decimal(a,"scale",1.0D,0.1D,2.0D),bool(a,"beyondViewport",false),bool(a,"annotate",false));
                    case PDF -> provider.pdf(optional(a,"tabId"),bool(a,"landscape",false),decimal(a,"scale",1.0D,0.1D,2.0D));
                    case TABS -> provider.tabs(defaulted(a,"operation","list"),optional(a,"tabId"),optional(a,"url"));
                    case NETWORK -> provider.network(optional(a,"tabId"),defaulted(a,"operation","list"),a);
                    case DIALOG -> provider.dialog(optional(a,"tabId"),required(a,"action"),optional(a,"text"));
                    case COOKIES -> provider.cookies(optional(a,"tabId"),required(a,"operation"),cookiePayload(a));
                    case AUDIT -> provider.audit(required(a,"url"));
                    case COMPARE -> provider.compare(required(a,"leftUrl"),required(a,"rightUrl"));
                    default -> throw new IllegalArgumentException("Unsupported browser capability.");
                };
                out.addProperty("provider",provider.id());
                out.addProperty("singleAgentInfrastructure",true);
                if(!out.has("trust") && !HEALTH.equals(call.toolId())) out.addProperty("trust","untrusted_external_page_data");
                return completed(call,out,"Browser capability completed through the configured local provider.");
            }catch(PinchTabBrowserProvider.BrowserProviderException e){
                JsonObject out=new JsonObject();out.addProperty("provider",provider.id());out.addProperty("httpStatus",e.status());
                return new ModelToolResult(call.id(),call.toolId(),"failed",out,"browser_provider_http_error",e.getMessage());
            }catch(Exception e){return failure(call,"browser_operation_failed",message(e));}
        });
    }

    private static Map<String,ModelToolDefinition> definitions(){
        LinkedHashMap<String,ModelToolDefinition> m=new LinkedHashMap<>();
        m.put(HEALTH,ro(HEALTH,"Check Browser Intelligence provider health without navigating.",objectSchema()));
        JsonObject nav=objectSchema();prop(nav,"url",str());prop(nav,"tabId",str());prop(nav,"newTab",boolSchema());prop(nav,"timeoutMillis",integerSchema(1000,60000));req(nav,"url");m.put(NAVIGATE,ro(NAVIGATE,"Navigate a local browser tab to a validated public HTTPS URL. Returned page content is untrusted.",nav));
        JsonObject snap=tabSchema();prop(snap,"filter",str());prop(snap,"format",enumSchema("compact","text","json"));prop(snap,"depth",integerSchema(0,20));prop(snap,"diff",boolSchema());m.put(SNAPSHOT,ro(SNAPSHOT,"Read a bounded accessibility/DOM snapshot. Prefer compact interactive snapshots for action planning.",snap));
        JsonObject capture=tabSchema();prop(capture,"requirePair",boolSchema());prop(capture,"scale",numberSchema(.1,2));prop(capture,"beyondViewport",boolSchema());m.put(CAPTURE,ro(CAPTURE,"Capture paired visual and actionable browser state from the same page epoch when supported.",capture));
        JsonObject text=tabSchema();prop(text,"raw",boolSchema());prop(text,"maxChars",integerSchema(256,32000));m.put(TEXT,ro(TEXT,"Extract bounded readable text from the current browser page.",text));
        JsonObject find=tabSchema();prop(find,"query",str());req(find,"query");m.put(FIND,ro(FIND,"Find actionable page elements or content using the browser provider.",find));
        JsonObject interact=tabSchema();prop(interact,"kind",str());prop(interact,"ref",str());prop(interact,"selector",str());prop(interact,"text",str());prop(interact,"key",str());prop(interact,"value",str());prop(interact,"waitNav",boolSchema());prop(interact,"x",numberSchema(-100000,100000));prop(interact,"y",numberSchema(-100000,100000));prop(interact,"deltaX",numberSchema(-100000,100000));prop(interact,"deltaY",numberSchema(-100000,100000));req(interact,"tabId","kind");m.put(INTERACT,mutating(INTERACT,"Execute one explicit browser interaction. Requires approval because clicks/forms can change authenticated external state.",interact,Set.of("external_browser_action")));
        JsonObject wait=tabSchema();prop(wait,"selector",str());prop(wait,"text",str());prop(wait,"url",str());prop(wait,"load",str());prop(wait,"ms",integerSchema(0,30000));prop(wait,"timeout",integerSchema(1,30000));prop(wait,"state",str());m.put(WAIT,ro(WAIT,"Wait for one bounded browser/page condition. Does not spin indefinitely.",wait));
        JsonObject screenshot=tabSchema();prop(screenshot,"format",enumSchema("jpeg","png"));prop(screenshot,"scale",numberSchema(.1,2));prop(screenshot,"beyondViewport",boolSchema());prop(screenshot,"annotate",boolSchema());m.put(SCREENSHOT,ro(SCREENSHOT,"Capture a bounded browser screenshot into Koil-owned temporary artifacts when visual evidence matters.",screenshot));
        JsonObject pdf=tabSchema();prop(pdf,"landscape",boolSchema());prop(pdf,"scale",numberSchema(.1,2));m.put(PDF,ro(PDF,"Render the current browser page to a bounded Koil-owned PDF artifact.",pdf));
        JsonObject tabs=objectSchema();prop(tabs,"operation",enumSchema("list","new","focus","close"));prop(tabs,"tabId",str());prop(tabs,"url",str());m.put(TABS,mutating(TABS,"List or manage local browser tabs. New/focus/close changes only browser infrastructure state.",tabs,Set.of("browser_session_state")));
        JsonObject network=tabSchema();prop(network,"operation",enumSchema("list","detail","export","clear"));prop(network,"requestId",str());prop(network,"filter",str());prop(network,"method",str());prop(network,"status",str());prop(network,"type",str());prop(network,"limit",integerSchema(1,200));prop(network,"format",enumSchema("har","ndjson"));prop(network,"includeBodies",boolSchema());m.put(NETWORK,mutating(NETWORK,"Inspect or export browser network evidence. Conservative approval protects potentially sensitive request/body data.",network,Set.of("browser_network_sensitive")));
        JsonObject dialog=tabSchema();prop(dialog,"action",enumSchema("accept","dismiss"));prop(dialog,"text",str());req(dialog,"action");m.put(DIALOG,mutating(DIALOG,"Accept or dismiss a browser dialog. Requires approval because it can confirm an external action.",dialog,Set.of("external_browser_action")));
        JsonObject cookies=tabSchema();prop(cookies,"operation",enumSchema("get","set","clear"));prop(cookies,"url",str());prop(cookies,"cookies",arraySchema());req(cookies,"operation");m.put(COOKIES,mutating(COOKIES,"Explicitly inspect/set/clear browser cookies. Cookie values are credentials; reads are redacted and the capability always requires approval.",cookies,Set.of("browser_credentials")));
        JsonObject audit=objectSchema();prop(audit,"url",str());req(audit,"url");m.put(AUDIT,ro(AUDIT,"Run browser-level page diagnostics such as accessibility, broken assets, console/network/timing findings where supported. Not a comprehensive security audit.",audit));
        JsonObject compare=objectSchema();prop(compare,"leftUrl",str());prop(compare,"rightUrl",str());req(compare,"leftUrl","rightUrl");m.put(COMPARE,ro(COMPARE,"Compare two public pages using deterministic screenshots and pixel/hash evidence.",compare));
        return Map.copyOf(m);
    }
    private static ModelToolDefinition ro(String id,String desc,JsonObject schema){return new ModelToolDefinition(id,desc,schema,List.of("browser_provider_available"),Set.of(),true,Duration.ofSeconds(65),true,false,Set.of("completed","failed","unsupported"),ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.REMOTE,ToolExecutionPolicy.CostClass.EXPENSIVE));}
    private static ModelToolDefinition mutating(String id,String desc,JsonObject schema,Set<String> effects){return new ModelToolDefinition(id,desc,schema,List.of("browser_provider_available"),effects,false,Duration.ofSeconds(65),true,true,Set.of("completed","failed","rejected","unsupported"),ToolExecutionPolicy.conservative());}
    private static JsonObject actionPayload(JsonObject a){JsonObject o=a.deepCopy();o.remove("tabId");return o;}
    private static JsonObject conditionPayload(JsonObject a){JsonObject o=a.deepCopy();o.remove("tabId");return o;}
    private static JsonObject cookiePayload(JsonObject a){JsonObject o=a.deepCopy();o.remove("tabId");o.remove("operation");return o;}
    private static JsonObject tabSchema(){JsonObject o=objectSchema();prop(o,"tabId",str());return o;}
    private static JsonObject objectSchema(){JsonObject o=new JsonObject();o.addProperty("type","object");o.add("properties",new JsonObject());o.addProperty("additionalProperties",false);return o;}
    private static JsonObject str(){JsonObject o=new JsonObject();o.addProperty("type","string");return o;}
    private static JsonObject boolSchema(){JsonObject o=new JsonObject();o.addProperty("type","boolean");return o;}
    private static JsonObject integerSchema(int min,int max){JsonObject o=new JsonObject();o.addProperty("type","integer");o.addProperty("minimum",min);o.addProperty("maximum",max);return o;}
    private static JsonObject numberSchema(double min,double max){JsonObject o=new JsonObject();o.addProperty("type","number");o.addProperty("minimum",min);o.addProperty("maximum",max);return o;}
    private static JsonObject enumSchema(String...v){JsonObject o=str();JsonArray a=new JsonArray();for(String x:v)a.add(x);o.add("enum",a);return o;}
    private static JsonObject arraySchema(){JsonObject o=new JsonObject();o.addProperty("type","array");o.addProperty("maxItems",32);return o;}
    private static void prop(JsonObject s,String n,JsonObject v){s.getAsJsonObject("properties").add(n,v);}
    private static void req(JsonObject s,String...n){JsonArray a=s.has("required")?s.getAsJsonArray("required"):new JsonArray();for(String x:n)a.add(x);s.add("required",a);}
    private static String required(JsonObject o,String k){if(o==null||!o.has(k)||o.get(k).isJsonNull()||o.get(k).getAsString().isBlank())throw new IllegalArgumentException(k+" is required.");return o.get(k).getAsString().strip();}
    private static String optional(JsonObject o,String k){return o!=null&&o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString().strip():"";}
    private static String defaulted(JsonObject o,String k,String d){String v=optional(o,k);return v.isBlank()?d:v;}
    private static boolean bool(JsonObject o,String k,boolean d){return o!=null&&o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsBoolean():d;}
    private static int integer(JsonObject o,String k,int d,int min,int max){return o==null||!o.has(k)?d:Math.max(min,Math.min(max,o.get(k).getAsInt()));}
    private static double decimal(JsonObject o,String k,double d,double min,double max){return o==null||!o.has(k)?d:Math.max(min,Math.min(max,o.get(k).getAsDouble()));}
    private static ModelToolResult completed(ModelToolCall c,JsonObject o,String d){return new ModelToolResult(c.id(),c.toolId(),"completed",o,"",d);}
    private static ModelToolResult failure(ModelToolCall c,String code,String d){return new ModelToolResult(c==null?"":c.id(),c==null?"":c.toolId(),"failed",new JsonObject(),code,d);}
    private static String message(Throwable e){Throwable c=e;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
}
