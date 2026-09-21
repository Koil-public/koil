package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Koil-owned canonical content-artifact surface. Rich-file conversion is provider-backed, authorization remains Koil-owned. */
public final class ContentIntelligenceModelToolRegistry {
    public static final String INSPECT = "content.inspect";
    public static final String NORMALIZE = "content.normalize";
    public static final String EXTRACT = "content.extract";
    public static final String SECTIONS = "content.sections";
    public static final String SEARCH = "content.search";
    public static final String RELEASE = "content.release";
    private static final int MAX_LOCAL_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ARTIFACT_CHARS = 1_000_000;
    private static final int MAX_ARTIFACTS = 32;
    private static final long TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final Map<String, Artifact> ARTIFACTS = new ConcurrentHashMap<>();
    private static final Map<String, ModelToolDefinition> DEFINITIONS = definitions();
    private static volatile ContentProvider provider = MarkItDownMcpProvider.managed();

    private ContentIntelligenceModelToolRegistry() {}

    public static List<ModelToolDefinition> modelTools() { return List.copyOf(DEFINITIONS.values()); }
    public static boolean supports(String id) { return id != null && DEFINITIONS.containsKey(id); }
    public static void configureProvider(ContentProvider value) { if (value != null) provider = value; }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) return CompletableFuture.completedFuture(failure(call,"unknown_content_tool","Unknown content capability."));
        cleanup();
        try {
            return switch (call.toolId()) {
                case INSPECT -> CompletableFuture.completedFuture(inspect(call));
                case NORMALIZE -> normalize(call);
                case EXTRACT -> CompletableFuture.completedFuture(extract(call));
                case SECTIONS -> CompletableFuture.completedFuture(sections(call));
                case SEARCH -> CompletableFuture.completedFuture(search(call));
                case RELEASE -> CompletableFuture.completedFuture(release(call));
                default -> CompletableFuture.completedFuture(failure(call,"unknown_content_tool","Unknown content capability."));
            };
        } catch (Exception e) {
            return CompletableFuture.completedFuture(failure(call,"content_operation_failed",message(e)));
        }
    }

    private static ModelToolResult inspect(ModelToolCall call) throws Exception {
        Source source = source(call.arguments());
        JsonObject out = source.provenance().deepCopy();
        out.addProperty("provider", provider.id());
        if (source.path() != null) {
            long size = Files.size(source.path());
            out.addProperty("bytes", size);
            out.addProperty("regularFile", Files.isRegularFile(source.path()));
            out.addProperty("readable", Files.isReadable(source.path()));
            out.addProperty("sha256", sha256(source.path()));
            String type = Files.probeContentType(source.path());
            if (type != null) out.addProperty("mimeType", type);
        } else {
            out.addProperty("authorizedPublicUri", true);
        }
        out.add("diagnostics", provider.diagnostics());
        return completed(call,out,"Content source inspected through Koil authorization.");
    }

    private static CompletableFuture<ModelToolResult> normalize(ModelToolCall call) throws Exception {
        Source source = source(call.arguments());
        if (source.path() != null) {
            long size = Files.size(source.path());
            if (size > MAX_LOCAL_BYTES) return CompletableFuture.completedFuture(failure(call,"content_too_large","Local content exceeds the 32 MiB normalization bound."));
        }
        CompletableFuture<JsonObject> conversion = source.path() != null ? provider.normalize(source.path()) : provider.normalize(source.uri());
        return conversion.handle((result,error) -> {
            if (error != null) return failure(call,"content_provider_failed",message(error));
            String markdown = result.has("markdown") && result.get("markdown").isJsonPrimitive() ? result.get("markdown").getAsString() : "";
            boolean truncated = markdown.length() > MAX_ARTIFACT_CHARS;
            if (truncated) markdown = markdown.substring(0,MAX_ARTIFACT_CHARS);
            if (ARTIFACTS.size() >= MAX_ARTIFACTS) cleanup(true);
            if (ARTIFACTS.size() >= MAX_ARTIFACTS) return failure(call,"content_artifact_limit","Too many active content artifacts.");
            String id = "content-" + UUID.randomUUID().toString().substring(0,12);
            long expires = System.currentTimeMillis() + TTL_MILLIS;
            ARTIFACTS.put(id,new Artifact(id,markdown,source.provenance().deepCopy(),expires));
            JsonObject out = new JsonObject();
            out.addProperty("artifactId",id);
            out.addProperty("characters",markdown.length());
            out.addProperty("truncated",truncated);
            out.addProperty("expiresAtMillis",expires);
            out.addProperty("provider",provider.id());
            out.add("provenance",source.provenance().deepCopy());
            out.add("diagnostics",provider.diagnostics());
            return completed(call,out,"Content normalized into a bounded canonical Markdown artifact.");
        });
    }

    private static ModelToolResult extract(ModelToolCall call) {
        Artifact a = artifact(call.arguments());
        int start = integer(call.arguments(),"start",0,0,a.markdown().length());
        int max = integer(call.arguments(),"maxCharacters",12000,1,32000);
        int end = Math.min(a.markdown().length(),start+max);
        JsonObject out = artifactHeader(a);
        out.addProperty("start",start); out.addProperty("end",end); out.addProperty("text",a.markdown().substring(start,end));
        return completed(call,out,"A bounded content slice was extracted.");
    }

    private static ModelToolResult sections(ModelToolCall call) {
        Artifact a = artifact(call.arguments());
        JsonArray sections = new JsonArray();
        String[] lines = a.markdown().split("\\R",-1);
        int offset = 0;
        for (String line : lines) {
            if (line.startsWith("#")) {
                int hashes=0; while(hashes<line.length() && line.charAt(hashes)=='#') hashes++;
                if (hashes<=6 && hashes<line.length() && Character.isWhitespace(line.charAt(hashes))) {
                    JsonObject section=new JsonObject(); section.addProperty("level",hashes); section.addProperty("title",line.substring(hashes).strip()); section.addProperty("offset",offset); sections.add(section);
                    if (sections.size()>=200) break;
                }
            }
            offset += line.length()+1;
        }
        JsonObject out=artifactHeader(a); out.add("sections",sections); out.addProperty("sectionCount",sections.size());
        return completed(call,out,"Content section structure extracted.");
    }

    private static ModelToolResult search(ModelToolCall call) {
        Artifact a=artifact(call.arguments()); String query=required(call.arguments(),"query"); int max=integer(call.arguments(),"limit",12,1,40);
        String lower=a.markdown().toLowerCase(java.util.Locale.ROOT), needle=query.toLowerCase(java.util.Locale.ROOT); JsonArray matches=new JsonArray(); int from=0;
        while(matches.size()<max) { int at=lower.indexOf(needle,from); if(at<0) break; int s=Math.max(0,at-180), e=Math.min(a.markdown().length(),at+needle.length()+300); JsonObject m=new JsonObject(); m.addProperty("offset",at); m.addProperty("text",a.markdown().substring(s,e)); matches.add(m); from=at+Math.max(1,needle.length()); }
        JsonObject out=artifactHeader(a); out.addProperty("query",query); out.add("matches",matches); out.addProperty("matchCount",matches.size());
        return completed(call,out,"Bounded search completed within the normalized artifact.");
    }

    private static ModelToolResult release(ModelToolCall call) {
        String id=required(call.arguments(),"artifactId"); boolean removed=ARTIFACTS.remove(id)!=null; JsonObject out=new JsonObject(); out.addProperty("artifactId",id); out.addProperty("released",removed); return completed(call,out,"Content artifact lease released.");
    }

    private static Source source(JsonObject args) throws Exception {
        String url=optional(args,"url");
        if(!url.isBlank()) { URI uri=PublicNetworkPolicy.validate(URI.create(url)); JsonObject p=new JsonObject();p.addProperty("sourceType","remote");p.addProperty("uri",uri.toString());return new Source(null,uri,p); }
        String workspace=required(args,"workspace"); String path=required(args,"path"); ModelWorkspaceRegistry.ResolvedPath resolved=ModelWorkspaceRegistry.inspect(workspace,path,false);
        if(!Files.isRegularFile(resolved.path())) throw new IllegalArgumentException("Authorized content path is not a regular file.");
        JsonObject p=new JsonObject();p.addProperty("sourceType","workspace");p.addProperty("workspace",resolved.workspace().id());p.addProperty("path",resolved.relativePath());return new Source(resolved.path(),null,p);
    }

    private static Artifact artifact(JsonObject args) { String id=required(args,"artifactId"); Artifact a=ARTIFACTS.get(id); if(a==null||a.expiresAt()<System.currentTimeMillis()) {ARTIFACTS.remove(id);throw new IllegalArgumentException("Unknown or expired content artifact.");} return a; }
    private static JsonObject artifactHeader(Artifact a){JsonObject o=new JsonObject();o.addProperty("artifactId",a.id());o.addProperty("characters",a.markdown().length());o.add("provenance",a.provenance().deepCopy());return o;}
    private static void cleanup(){cleanup(false);} private static void cleanup(boolean pressure){long now=System.currentTimeMillis();ARTIFACTS.entrySet().removeIf(e->e.getValue().expiresAt()<now);if(pressure&&ARTIFACTS.size()>=MAX_ARTIFACTS)ARTIFACTS.entrySet().stream().min(Map.Entry.comparingByValue((a,b)->Long.compare(a.expiresAt(),b.expiresAt()))).ifPresent(e->ARTIFACTS.remove(e.getKey()));}
    private static String sha256(Path p)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(p)){byte[]b=new byte[8192];for(int n;(n=in.read(b))>=0;)if(n>0)d.update(b,0,n);}return java.util.HexFormat.of().formatHex(d.digest());}

    private static Map<String,ModelToolDefinition> definitions(){LinkedHashMap<String,ModelToolDefinition>m=new LinkedHashMap<>();JsonObject src=sourceSchema();m.put(INSPECT,ro(INSPECT,"Inspect one authorized local workspace file or validated public HTTPS content source.",src));m.put(NORMALIZE,ro(NORMALIZE,"Normalize one authorized rich-content source into a canonical bounded Markdown artifact.",src));JsonObject aid=artifactSchema();JsonObject extract=artifactSchema();prop(extract,"start",integerSchema(0,Integer.MAX_VALUE));prop(extract,"maxCharacters",integerSchema(1,32000));m.put(EXTRACT,ro(EXTRACT,"Extract a bounded text window from a normalized content artifact.",extract));m.put(SECTIONS,ro(SECTIONS,"List bounded Markdown heading structure from a normalized content artifact.",aid));JsonObject search=artifactSchema();prop(search,"query",str());prop(search,"limit",integerSchema(1,40));req(search,"query");m.put(SEARCH,ro(SEARCH,"Search normalized content without reinjecting the whole artifact into model context.",search));m.put(RELEASE,ro(RELEASE,"Release a temporary normalized content artifact.",aid));return Map.copyOf(m);}
    private static ModelToolDefinition ro(String id,String desc,JsonObject schema){return new ModelToolDefinition(id,desc,schema,List.of(),Set.of(),true,Duration.ofSeconds(70),true,false,Set.of("completed","failed"),ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.REMOTE,ToolExecutionPolicy.CostClass.MODERATE));}
    private static JsonObject sourceSchema(){JsonObject o=objectSchema();prop(o,"workspace",str());prop(o,"path",str());prop(o,"url",str());return o;}
    private static JsonObject artifactSchema(){JsonObject o=objectSchema();prop(o,"artifactId",str());req(o,"artifactId");return o;}
    private static JsonObject objectSchema(){JsonObject o=new JsonObject();o.addProperty("type","object");o.add("properties",new JsonObject());o.addProperty("additionalProperties",false);return o;} private static JsonObject str(){JsonObject o=new JsonObject();o.addProperty("type","string");return o;} private static JsonObject integerSchema(int min,int max){JsonObject o=new JsonObject();o.addProperty("type","integer");o.addProperty("minimum",min);o.addProperty("maximum",max);return o;} private static void prop(JsonObject s,String n,JsonObject v){s.getAsJsonObject("properties").add(n,v);} private static void req(JsonObject s,String...n){JsonArray a=s.has("required")?s.getAsJsonArray("required"):new JsonArray();for(String x:n)a.add(x);s.add("required",a);} private static String required(JsonObject o,String k){if(o==null||!o.has(k)||o.get(k).isJsonNull()||o.get(k).getAsString().isBlank())throw new IllegalArgumentException(k+" is required.");return o.get(k).getAsString().strip();} private static String optional(JsonObject o,String k){return o!=null&&o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString().strip():"";} private static int integer(JsonObject o,String k,int d,int min,int max){return o==null||!o.has(k)?d:Math.max(min,Math.min(max,o.get(k).getAsInt()));}
    private static ModelToolResult completed(ModelToolCall c,JsonObject o,String d){return new ModelToolResult(c.id(),c.toolId(),"completed",o,"",d);} private static ModelToolResult failure(ModelToolCall c,String code,String d){return new ModelToolResult(c==null?"":c.id(),c==null?"":c.toolId(),"failed",new JsonObject(),code,d);} private static String message(Throwable e){Throwable c=e;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
    private record Source(Path path,URI uri,JsonObject provenance){} private record Artifact(String id,String markdown,JsonObject provenance,long expiresAt){}
}
