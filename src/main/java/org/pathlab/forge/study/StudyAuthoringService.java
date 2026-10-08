package org.pathlab.forge.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class StudyAuthoringService {
    private final String url;
    public StudyAuthoringService(Path root) throws IOException {
        Files.createDirectories(root);
        url = "jdbc:sqlite:" + root.resolve("study-authoring.sqlite").toAbsolutePath();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS study_drafts(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,record_json TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS study_draft_history(id TEXT NOT NULL,revision INTEGER NOT NULL,record_json TEXT NOT NULL,PRIMARY KEY(id,revision))");
            statement.execute("CREATE TABLE IF NOT EXISTS study_exports(checksum TEXT PRIMARY KEY,pack_key TEXT NOT NULL,version TEXT NOT NULL,definition_json TEXT NOT NULL,UNIQUE(pack_key,version))");
        } catch (SQLException error) { throw new IOException("Study authoring store could not be opened",error); }
    }
    public synchronized List<StudyDraft> listDrafts() throws IOException {
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT record_json FROM study_drafts ORDER BY rowid DESC")) {
            var drafts = new ArrayList<StudyDraft>();
            while(rows.next()) drafts.add(StudyPackCanonicalJson.mapper().readValue(rows.getString(1),StudyDraft.class));
            return List.copyOf(drafts);
        } catch(SQLException error) { throw new IOException("Study drafts could not be listed",error); }
    }
    public synchronized StudyDraft getDraft(String id) throws IOException {
        try (var connection = DriverManager.getConnection(url); var statement = connection.prepareStatement("SELECT record_json FROM study_drafts WHERE id=?")) {
            statement.setString(1,id);
            try(var rows=statement.executeQuery()) {
                if(!rows.next()) throw new IllegalArgumentException("Study draft was not found");
                return StudyPackCanonicalJson.mapper().readValue(rows.getString(1),StudyDraft.class);
            }
        } catch(SQLException error) { throw new IOException("Study draft could not be read",error); }
    }
    public StudyDraft createDraft(String name,String definitionJson,String associationsJson) throws IOException {
        var definition = definitionJson == null || definitionJson.isBlank() ? emptyDefinition() : StudyPackCanonicalJson.core(StudyPackCanonicalJson.parse(definitionJson));
        draftShape(definition);
        var associations = associationsJson == null || associationsJson.isBlank() ? StudyPackCanonicalJson.mapper().createObjectNode() : StudyPackCanonicalJson.parse(associationsJson);
        var draft=new StudyDraft(UUID.randomUUID().toString(),name(name),1,definition,associations,issues(definition),"",List.of(),"",System.currentTimeMillis());
        save(draft,0); return draft;
    }
    public synchronized StudyDraft saveDraft(String id,String name,long expectedRevision,String definitionJson,String associationsJson) throws IOException {
        var current=getDraft(id);
        if(current.revision()!=expectedRevision) throw new IllegalStateException("Study draft revision changed; reload before saving");
        var definition=StudyPackCanonicalJson.core(StudyPackCanonicalJson.parse(definitionJson));
        draftShape(definition);
        var associations=StudyPackCanonicalJson.parse(associationsJson == null ? "{}" : associationsJson);
        var draft=new StudyDraft(id,name(name),current.revision()+1,definition,associations,issues(definition),"",List.of(),"",System.currentTimeMillis());
        save(draft,expectedRevision); return draft;
    }
    public synchronized StudyDraft duplicate(String id,String name,boolean nextVersion) throws IOException {
        var source=getDraft(id); var definition=source.definition().deepCopy();
        if(nextVersion) definition.put("version",definition.path("version").bigIntegerValue().add(java.math.BigInteger.ONE));
        return createDraft(name,StudyPackCanonicalJson.canonicalize(definition),StudyPackCanonicalJson.canonicalize(source.associations()));
    }
    public synchronized List<StudyDraft> history(String id) throws IOException {
        getDraft(id);
        try(var connection=DriverManager.getConnection(url);var statement=connection.prepareStatement("SELECT record_json FROM study_draft_history WHERE id=? ORDER BY revision DESC LIMIT 100")) {
            statement.setString(1,id);var result=new ArrayList<StudyDraft>();
            try(var rows=statement.executeQuery()) { while(rows.next()) result.add(StudyPackCanonicalJson.mapper().readValue(rows.getString(1),StudyDraft.class)); }
            return List.copyOf(result);
        } catch(SQLException error) { throw new IOException("Draft history could not be read",error); }
    }
    public StudyDraft recover(String id,long historicalRevision,long expectedRevision) throws IOException {
        var previous=history(id).stream().filter(draft->draft.revision()==historicalRevision).findFirst().orElseThrow(()->new IllegalArgumentException("Historical draft revision was not found"));
        return saveDraft(id,previous.name(),expectedRevision,StudyPackCanonicalJson.canonicalize(previous.definition()),StudyPackCanonicalJson.canonicalize(previous.associations()));
    }
    public synchronized StudyDraft preview(String id,long expectedRevision) throws IOException {
        var current=expected(id,expectedRevision); StudyPackContract.validateCore(current.definition());
        var checksum=StudyPackCanonicalJson.checksum(current.definition());
        var preview=new StudyDraft(id,current.name(),current.revision()+1,current.definition(),current.associations(),List.of(),checksum,List.of(),"",System.currentTimeMillis());
        save(preview,expectedRevision);return preview;
    }
    public synchronized StudyDraft reviewTask(String id,long expectedRevision,String checksum,String taskId) throws IOException {
        var current=expected(id,expectedRevision);requirePreview(current,checksum);
        boolean found=false;for(var task:current.definition().path("tasks")) if(task.path("id").asText().equals(taskId)) found=true;
        if(!found) throw new IllegalArgumentException("Preview task was not found");
        var reviewed=new java.util.TreeSet<>(current.reviewedTaskIds());reviewed.add(taskId);
        var draft=new StudyDraft(id,current.name(),current.revision()+1,current.definition(),current.associations(),List.of(),checksum,List.copyOf(reviewed),"",System.currentTimeMillis());
        save(draft,expectedRevision);return draft;
    }
    public synchronized StudyDraft approve(String id,long expectedRevision,String checksum) throws IOException {
        var current=expected(id,expectedRevision);requirePreview(current,checksum);
        var tasks=current.definition().path("tasks");
        if(current.reviewedTaskIds().size()!=tasks.size()) throw new IllegalStateException("Review every task, key, explanation and source before approving");
        var published=current.definition().deepCopy();published.put("checksum",checksum);
        var faculty=published.putObject("facultyPreview");faculty.put("packChecksum",checksum);faculty.put("previewVersion",StudyPackContract.PREVIEW_VERSION);faculty.put("reviewedAt",Instant.now().toString());
        validateApproved(published);
        var canonical=StudyPackCanonicalJson.canonicalize(published);
        var draft=new StudyDraft(id,current.name(),current.revision()+1,current.definition(),current.associations(),List.of(),checksum,current.reviewedTaskIds(),checksum,System.currentTimeMillis());
        try(var connection=DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try {
                try(var statement=connection.prepareStatement("INSERT OR IGNORE INTO study_exports VALUES(?,?,?,?)")) {
                    statement.setString(1,checksum);statement.setString(2,published.path("packKey").asText());statement.setString(3,published.path("version").asText());statement.setString(4,canonical);statement.executeUpdate();
                }
                try(var statement=connection.prepareStatement("SELECT checksum FROM study_exports WHERE pack_key=? AND version=?")) {
                    statement.setString(1,published.path("packKey").asText());statement.setString(2,published.path("version").asText());
                    try(var rows=statement.executeQuery()) { if(!rows.next() || !checksum.equals(rows.getString(1))) throw new IllegalStateException("Approved pack versions are immutable; duplicate into a new version"); }
                }
                writeDraft(connection,draft,expectedRevision);connection.commit();
            } catch(SQLException|RuntimeException|IOException error) {connection.rollback();throw error;}
        }catch(SQLException error){throw new IOException("Approved Study Pack could not be saved",error);}
        return draft;
    }
    public synchronized String approved(String checksum) throws IOException {
        if(checksum==null || !checksum.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid Study Pack checksum");
        try(var connection=DriverManager.getConnection(url);var statement=connection.prepareStatement("SELECT definition_json FROM study_exports WHERE checksum=?")) {
            statement.setString(1,checksum);try(var rows=statement.executeQuery()) {
                if(!rows.next())throw new IllegalArgumentException("Approved Study Pack was not found");
                var body=rows.getString(1);var definition=StudyPackCanonicalJson.parse(body);validateApproved(definition);
                if(!checksum.equals(StudyPackCanonicalJson.checksum(definition)))throw new IOException("Stored approved Study Pack checksum changed");return body;
            }
        }catch(SQLException error){throw new IOException("Approved Study Pack could not be read",error);}
    }
    public String exportDraft(String id) throws IOException {
        var draft=getDraft(id);var bundle=StudyPackCanonicalJson.mapper().createObjectNode();bundle.put("schema","pathlab.study-draft/1");
        bundle.put("name",draft.name());bundle.set("definition",draft.definition());bundle.set("localAssociations",draft.associations());return StudyPackCanonicalJson.canonicalize(bundle);
    }
    public StudyDraft importDraft(String body) throws IOException {
        var bundle=StudyPackCanonicalJson.parse(body);
        if(bundle.path("schema").asText().equals("pathlab.study-draft/1")) {
            if(!bundle.path("definition").isObject() || !bundle.path("localAssociations").isObject())throw new IllegalArgumentException("Draft bundle definition/associations are invalid");
            return createDraft(bundle.path("name").asText(),StudyPackCanonicalJson.canonicalize(bundle.path("definition")),StudyPackCanonicalJson.canonicalize(bundle.path("localAssociations")));
        }
        return createDraft(bundle.path("title").asText("Imported pack"),body,"{}");
    }
    public synchronized StudyDraft importQuestions(String id,long expectedRevision,String format,String text,String slideId)throws IOException {
        var current=expected(id,expectedRevision);var result=StudyQuestionImports.parse(format,text,slideId);var definition=current.definition().deepCopy();
        if(!definition.path("tasks").isArray())definition.putArray("tasks");var tasks=(com.fasterxml.jackson.databind.node.ArrayNode)definition.path("tasks");
        if(tasks.size()+result.tasks().size()>StudyPackContract.MAX_TASKS)throw new IllegalArgumentException("Question count exceeds 500");
        var ids=new java.util.HashSet<String>();tasks.forEach(task->ids.add(task.path("id").asText()));
        var associations=current.associations().deepCopy();var imports=associations.withArray("imports");
        for(var task:result.tasks()){
            if(!ids.add(task.path("id").asText()))throw new IllegalArgumentException("Imported question id collides; rename the source ids before importing");
            var provenance=imports.addObject();provenance.put("taskId",task.path("id").asText());provenance.put("format",format);
            for(var key:List.of("importedXml","importedName","importedTitle","importedScoring"))if(task.has(key)){provenance.set(key,task.get(key));task.remove(key);}
            tasks.add(task);
        }
        if(!result.warnings().isEmpty()){var warnings=associations.withArray("importWarnings");result.warnings().forEach(warnings::add);}
        return saveDraft(id,current.name(),expectedRevision,definition.toString(),associations.toString());
    }
    public String exportCsv(String id)throws IOException{return StudyQuestionImports.exportCsv(getDraft(id).definition());}
    public StudyDraft importCsv(String text)throws IOException{var definition=StudyQuestionImports.importCsvPack(text);return createDraft(definition.path("title").asText("Imported CSV pack"),definition.toString(),"{}");}
    public static Score score(JsonNode task,JsonNode submission) {
        if(task.path("type").asText().equals("multiple-choice")) {
            if(!task.path("answerKey").isTextual() || task.path("answerKey").asText().isBlank())throw new IllegalArgumentException("Faculty key is incomplete");
            return new Score(submission.path("selectedOption").isTextual() && submission.path("selectedOption").asText().equals(task.path("answerKey").asText()),null);
        }
        if(!task.path("type").asText().equals("spatial"))throw new IllegalArgumentException("Task requires faculty conversion");
        var x=submission.get("x");var y=submission.get("y");
        if(x==null || y==null || !x.isNumber() || !y.isNumber() || !Double.isFinite(x.asDouble()) || !Double.isFinite(y.asDouble()) || x.asDouble()<0 || x.asDouble()>1 || y.asDouble()<0 || y.asDouble()>1)throw new IllegalArgumentException("Spatial submission must contain normalized finite x/y");
        var targetX=task.path("targetX").asDouble();var targetY=task.path("targetY").asDouble();var width=task.path("targetWidth").asDouble();var height=task.path("targetHeight").asDouble();var tolerance=task.path("tolerance").asDouble();
        for(var key:List.of("targetX","targetY","targetWidth","targetHeight","tolerance"))if(!task.path(key).isNumber() || !Double.isFinite(task.path(key).asDouble()))throw new IllegalArgumentException("Faculty spatial target is incomplete");
        if(targetX<0 || targetY<0 || width<=0 || height<=0 || targetX+width>1 || targetY+height>1 || tolerance<=0 || tolerance>.5)throw new IllegalArgumentException("Faculty spatial target is incomplete");
        return new Score(x.asDouble()>=targetX-tolerance && x.asDouble()<=targetX+width+tolerance && y.asDouble()>=targetY-tolerance && y.asDouble()<=targetY+height+tolerance,
                Math.min(1,Math.hypot(x.asDouble()-targetX-width/2,y.asDouble()-targetY-height/2)/Math.sqrt(2)));
    }
    public static void validateApproved(ObjectNode definition) {
        StudyPackContract.validateCore(StudyPackCanonicalJson.core(definition));var checksum=StudyPackCanonicalJson.checksum(definition);
        var preview=definition.path("facultyPreview");
        if(!checksum.equals(definition.path("checksum").asText()) || !checksum.equals(preview.path("packChecksum").asText()) || !StudyPackContract.PREVIEW_VERSION.equals(preview.path("previewVersion").asText()) || preview.path("reviewedAt").asText().isBlank())throw new IllegalArgumentException("Study Pack checksum/faculty preview does not match");
    }
    private StudyDraft expected(String id,long revision)throws IOException{var draft=getDraft(id);if(draft.revision()!=revision)throw new IllegalStateException("Study draft revision changed");return draft;}
    private static void requirePreview(StudyDraft draft,String checksum){if(checksum==null || !checksum.equals(draft.previewChecksum()) || !checksum.equals(StudyPackCanonicalJson.checksum(draft.definition())))throw new IllegalStateException("Any edit invalidates the faculty preview");}
    private static String name(String name){if(name==null || name.isBlank() || name.length()>240)throw new IllegalArgumentException("Draft name is required and limited to 240 characters");return name.strip();}
    private static List<String> issues(ObjectNode definition){try{StudyPackContract.validateCore(definition);return List.of();}catch(IllegalArgumentException error){return List.of(error.getMessage());}}
    private static ObjectNode emptyDefinition(){var node=StudyPackCanonicalJson.mapper().createObjectNode();node.put("schema",StudyPackContract.SCHEMA);node.put("packKey","");node.put("version",1);for(var key:List.of("title","author","license","provenance","revision"))node.put(key,"");node.putArray("languages").add("en");node.putArray("slides");node.putArray("tasks");return node;}
    private static void draftShape(ObjectNode definition) {
        var defaults=emptyDefinition();defaults.fieldNames().forEachRemaining(key->{if(!definition.has(key))definition.set(key,defaults.get(key));});
        for(var key:List.of("schema","packKey","title","author","license","provenance","revision"))if(!definition.path(key).isTextual())throw new IllegalArgumentException("Draft "+key+" must be text");
        if(!definition.path("version").isIntegralNumber())throw new IllegalArgumentException("Draft version must be an integer");
        for(var key:List.of("languages","slides","tasks"))if(!definition.path(key).isArray())throw new IllegalArgumentException("Draft "+key+" must be an array");
        for(var language:definition.path("languages"))if(!language.isTextual())throw new IllegalArgumentException("Draft language must be text");
        for(var slide:definition.path("slides")){if(!slide.isObject())throw new IllegalArgumentException("Draft slide must be an object");for(var key:List.of("viewerSlideId","sha256","displayName"))if(!slide.path(key).isTextual())throw new IllegalArgumentException("Draft slide "+key+" must be text");}
        for(var task:definition.path("tasks")) {
            if(!(task instanceof ObjectNode object))throw new IllegalArgumentException("Draft task must be an object");
            for(var key:List.of("id","slideId","prompt","explanation")){if(!object.has(key))object.put(key,"");else if(!object.path(key).isTextual())throw new IllegalArgumentException("Draft task "+key+" must be text");}
            if(!object.has("type"))object.put("type","faculty-conversion");
            for(var key:List.of("hints","sources")){if(!object.has(key))object.putArray(key);else if(!object.path(key).isArray())throw new IllegalArgumentException("Draft task "+key+" must be an array");}
            if(!object.path("type").isTextual())throw new IllegalArgumentException("Draft task type must be text");
            for(var hint:object.path("hints"))if(!hint.isTextual())throw new IllegalArgumentException("Draft hint must be text");
            for(var source:object.path("sources"))if(!source.isObject() || !source.path("title").isTextual() || !source.path("url").isTextual())throw new IllegalArgumentException("Draft source requires text title and URL");
            if(object.has("answerKey")&&!object.path("answerKey").isTextual())throw new IllegalArgumentException("Draft key must be text");
            if(object.has("options")&&!object.path("options").isArray())throw new IllegalArgumentException("Draft choices must be an array");
            for(var option:object.path("options"))if(!option.isTextual())throw new IllegalArgumentException("Draft choice must be text");
        }
    }
    private void save(StudyDraft draft,long expected)throws IOException{
        try(var connection=DriverManager.getConnection(url)){connection.setAutoCommit(false);try{writeDraft(connection,draft,expected);connection.commit();}catch(SQLException|RuntimeException|IOException error){connection.rollback();throw error;}}
        catch(SQLException error){throw new IOException("Study draft could not be saved",error);}
    }
    private static void writeDraft(java.sql.Connection connection,StudyDraft draft,long expected)throws SQLException,IOException{
        var json=StudyPackCanonicalJson.mapper().writeValueAsString(draft);
        if(json.getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw new IllegalArgumentException("Draft and local associations exceed 2 MiB");
        if(expected==0){try(var statement=connection.prepareStatement("INSERT INTO study_drafts VALUES(?,?,?)")){statement.setString(1,draft.id());statement.setLong(2,draft.revision());statement.setString(3,json);statement.executeUpdate();}}
        else{try(var statement=connection.prepareStatement("UPDATE study_drafts SET revision=?,record_json=? WHERE id=? AND revision=?")){statement.setLong(1,draft.revision());statement.setString(2,json);statement.setString(3,draft.id());statement.setLong(4,expected);if(statement.executeUpdate()!=1)throw new IllegalStateException("Study draft revision changed");}}
        try(var statement=connection.prepareStatement("INSERT INTO study_draft_history VALUES(?,?,?)")){statement.setString(1,draft.id());statement.setLong(2,draft.revision());statement.setString(3,json);statement.executeUpdate();}
        try(var statement=connection.prepareStatement("DELETE FROM study_draft_history WHERE id=? AND revision NOT IN(SELECT revision FROM study_draft_history WHERE id=? ORDER BY revision DESC LIMIT 100)")){statement.setString(1,draft.id());statement.setString(2,draft.id());statement.executeUpdate();}
    }
    public record Score(boolean correct,Double normalizedError){}
}
