package org.pathlab.forge.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public final class StudyQuestionImports {
    private StudyQuestionImports() {}
    public static Result parse(String format,String text,String slideId)throws IOException {
        if(text==null || text.getBytes(StandardCharsets.UTF_8).length>StudyPackContract.MAX_PACK_BYTES)throw new IllegalArgumentException("Question import exceeds 2 MiB");
        return switch(format.toLowerCase(java.util.Locale.ROOT)) {
            case "csv" -> tabular(text,',',slideId,false);
            case "anki", "tsv" -> tabular(text,'\t',slideId,true);
            case "json" -> json(text,slideId);
            case "qti", "moodle" -> xml(format,text,slideId);
            default -> throw new IllegalArgumentException("Unsupported question format; use bounded JSON, CSV, QTI XML, Moodle XML or Anki TSV");
        };
    }
    private static Result json(String text,String slide)throws IOException {
        var root=StudyPackCanonicalJson.parse(text);var tasks=root.path("tasks");
        if(!tasks.isArray() || tasks.size()>StudyPackContract.MAX_TASKS)throw new IllegalArgumentException("Question JSON requires at most 500 tasks");
        var result=new ArrayList<ObjectNode>();for(var task:tasks){if(!(task instanceof ObjectNode object))throw new IllegalArgumentException("Imported task must be an object");var copy=object.deepCopy();sanitizeTask(copy);if(!copy.has("slideId"))copy.put("slideId",slide);result.add(copy);}return new Result(result,List.of());
    }
    private static Result tabular(String text,char delimiter,String slide,boolean anki)throws IOException {
        var rows=rows(text,delimiter);if(rows.isEmpty())throw new IllegalArgumentException("Question table is empty");
        var header=rows.get(0);if(new java.util.HashSet<>(header).size()!=header.size())throw new IllegalArgumentException("Duplicate table column");
        var tasks=new ArrayList<ObjectNode>();var warnings=new ArrayList<String>();
        boolean hasHeader=header.contains("prompt") || header.contains("Front");
        if(!hasHeader && !anki)throw new IllegalArgumentException("CSV requires a prompt column");
        if(!hasHeader)header=List.of("Front","Back");
        for(var row:rows.subList(hasHeader?1:0,rows.size())) {
            if(row.stream().allMatch(String::isBlank))continue;
            if(row.size()!=header.size())throw new IllegalArgumentException("Table row width does not match header");
            var values=new LinkedHashMap<String,String>();for(var index=0;index<header.size();index++)values.put(header.get(index),row.get(index));
            var task=values.containsKey("taskJson") && !values.get("taskJson").isBlank()
                    ? StudyPackCanonicalJson.parse(values.get("taskJson"))
                    : base(values.getOrDefault("id","import-"+(tasks.size()+1)),values.getOrDefault("slideId",slide),plain(values.getOrDefault("prompt",values.getOrDefault("Front",""))));
            if(values.containsKey("id"))task.put("id",values.get("id"));
            if(values.containsKey("slideId"))task.put("slideId",values.get("slideId"));
            if(values.containsKey("prompt"))task.put("prompt",plain(values.get("prompt")));
            task.put("type",values.getOrDefault("type","multiple-choice"));
            task.put("explanation",plain(values.getOrDefault("explanation",values.getOrDefault("Back",""))));
            if(task.has("answerKey") || !values.getOrDefault("answerKey","").isBlank())task.put("answerKey",plain(values.getOrDefault("answerKey","")));
            for(var field:List.of("options","hints","sources")) {
                var value=values.get(field);
                if(value!=null && !value.isBlank())task.set(field,StudyPackCanonicalJson.mapper().readTree(value));
            }
            for(var field:List.of("targetX","targetY","targetWidth","targetHeight","tolerance")){
                var value=values.get(field);if(value!=null && !value.isBlank())task.set(field,StudyPackCanonicalJson.mapper().readTree(value));
            }
            sanitizeTask(task);
            if(anki && (task.path("options").size()<2 || task.path("answerKey").asText().isBlank())){
                task.put("type","faculty-conversion");warnings.add(task.path("id").asText()+": flashcard preserved; supply explicit choices/key/type and sources manually");
            }
            tasks.add(task);if(tasks.size()>StudyPackContract.MAX_TASKS)throw new IllegalArgumentException("Question count exceeds 500");
        }
        return new Result(tasks,warnings);
    }
    private static Result xml(String format,String text,String slide)throws IOException {
        try{
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setExpandEntityReferences(false);
            var document=factory.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
            var elements=document.getElementsByTagNameNS("*",format.equalsIgnoreCase("moodle")?"question":"assessmentItem");
            if(elements.getLength()==0 && format.equalsIgnoreCase("qti"))elements=document.getElementsByTagName("item");
            if(elements.getLength()>StudyPackContract.MAX_TASKS)throw new IllegalArgumentException("Question count exceeds 500");
            var tasks=new ArrayList<ObjectNode>();var warnings=new ArrayList<String>();
            for(var index=0;index<elements.getLength();index++){
                var element=(Element)elements.item(index);ObjectNode task;
                if(format.equalsIgnoreCase("moodle"))task=moodle(element,slide,index);
                else task=qti(element,slide,index);
                task.put("importedXml",serialize(element));
                if(task.path("type").asText().equals("faculty-conversion"))warnings.add(task.path("id").asText()+": unsupported response/scoring retained for faculty conversion");
                tasks.add(task);
            }
            if(tasks.isEmpty())throw new IllegalArgumentException("No supported question container found");return new Result(tasks,warnings);
        }catch(org.xml.sax.SAXException|javax.xml.parsers.ParserConfigurationException error){throw new IOException("Question XML rejected; DTDs/external entities and malformed XML are not accepted",error);}
    }
    private static ObjectNode moodle(Element element,String slide,int index) {
        var task=base("import-"+(index+1),slide,plain(content(element,"questiontext")));task.put("explanation",plain(content(element,"generalfeedback")));
        var answers=children(element,"answer");var options=task.withArray("options");String answer="";int positive=0;
        for(var option:answers){var text=plain(content(option,"text"));options.add(text);if(option.getAttribute("fraction").equals("100")){answer=text;positive++;}}
        var scoring=task.putArray("importedScoring");for(var option:answers){var value=scoring.addObject();value.put("option",plain(content(option,"text")));value.put("fraction",option.getAttribute("fraction"));}
        var single=content(element,"single");
        if(!element.getAttribute("type").equals("multichoice") || positive!=1 || (!single.isBlank() && !single.equals("true") && !single.equals("1")))task.put("type","faculty-conversion");
        else{task.put("type","multiple-choice");task.put("answerKey",answer);}
        task.put("importedName",plain(content(element,"name")));return task;
    }
    private static ObjectNode qti(Element element,String slide,int index) {
        var id=element.getAttribute("identifier");if(id.isBlank())id=element.getAttribute("ident");if(id.isBlank())id="import-"+(index+1);
        var task=base(id,slide,plain(content(element,"prompt")));task.put("type","faculty-conversion");
        var choices=children(element,"simpleChoice");var identifiers=new LinkedHashMap<String,String>();
        for(var choice:choices){var text=plain(choice.getTextContent());identifiers.put(choice.getAttribute("identifier"),text);task.withArray("options").add(text);}
        var correct=children(element,"correctResponse");var declarations=children(element,"responseDeclaration");var interactions=children(element,"choiceInteraction");
        var processing=children(element,"responseProcessing");
        var standardProcessing=processing.isEmpty() || (processing.size()==1 && processing.get(0).getAttribute("template").equals("http://www.imsglobal.org/question/qti_v2p1/rptemplates/match_correct") && processing.get(0).getElementsByTagName("*").getLength()==0 && processing.get(0).getTextContent().isBlank());
        if(choices.size()>=2 && correct.size()==1 && declarations.size()==1 && interactions.size()==1
                && declarations.get(0).getAttribute("cardinality").equals("single") && declarations.get(0).getAttribute("baseType").equals("identifier")
                && interactions.get(0).getAttribute("maxChoices").equals("1")
                && interactions.get(0).getAttribute("responseIdentifier").equals(declarations.get(0).getAttribute("identifier")) && standardProcessing) {
            var values=children(correct.get(0),"value");if(values.size()==1 && identifiers.containsKey(values.get(0).getTextContent().strip())) {
                task.put("type","multiple-choice");task.put("answerKey",identifiers.get(values.get(0).getTextContent().strip()));
            }
        }
        if(task.path("prompt").asText().isBlank())task.put("prompt",plain(content(element,"mattext")));
        task.put("explanation",plain(content(element,"feedbackBlock")));task.put("importedTitle",plain(element.getAttribute("title")));return task;
    }
    private static List<Element> children(Element parent,String tag){var nodes=parent.getElementsByTagNameNS("*",tag);if(nodes.getLength()==0)nodes=parent.getElementsByTagName(tag);var result=new ArrayList<Element>();for(var index=0;index<nodes.getLength();index++)result.add((Element)nodes.item(index));return result;}
    private static String content(Element element,String tag){var nodes=children(element,tag);return nodes.isEmpty()?"":nodes.get(0).getTextContent().strip();}
    private static String serialize(Element element)throws IOException {
        try {
            var factory=javax.xml.transform.TransformerFactory.newInstance();factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
            var transformer=factory.newTransformer();transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION,"yes");
            var output=new java.io.StringWriter();transformer.transform(new javax.xml.transform.dom.DOMSource(element),new javax.xml.transform.stream.StreamResult(output));return output.toString();
        } catch(javax.xml.transform.TransformerException error){throw new IOException("Imported XML provenance could not be preserved",error);}
    }
    private static ObjectNode base(String id,String slide,String prompt){var task=StudyPackCanonicalJson.mapper().createObjectNode();task.put("id",id);task.put("type","multiple-choice");task.put("slideId",slide);task.put("prompt",prompt);task.put("answerKey","");task.put("explanation","");task.putArray("options");task.putArray("hints");task.putArray("sources");return task;}
    private static void sanitizeTask(ObjectNode task){for(var field:List.of("prompt","explanation","answerKey"))if(task.path(field).isTextual())task.put(field,plain(task.path(field).asText()));for(var field:List.of("options","hints")){var array=task.path(field);if(array.isArray())for(var index=0;index<array.size();index++)if(array.get(index).isTextual())((com.fasterxml.jackson.databind.node.ArrayNode)array).set(index,StudyPackCanonicalJson.mapper().getNodeFactory().textNode(plain(array.get(index).asText())));}}
    private static String plain(String html){return html.replaceAll("(?is)<(script|style)\\b[^>]*>.*?(?:</\\1\\s*>|$)","").replaceAll("(?s)<[^>]*>","").replace("&nbsp;"," ").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&").strip();}
    public static List<List<String>> rows(String text,char delimiter){
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var field=new StringBuilder();boolean quoted=false;boolean closed=false;
        for(var index=0;index<text.length();index++){
            var character=text.charAt(index);
            if(quoted){if(character=='"'){if(index+1<text.length()&&text.charAt(index+1)=='"'){field.append('"');index++;}else{quoted=false;closed=true;}}else field.append(character);}
            else if(character=='"'){if(field.length()!=0||closed)throw new IllegalArgumentException("Malformed quoted table field");quoted=true;}
            else if(character==delimiter){row.add(field.toString());field.setLength(0);closed=false;}
            else if(character=='\n'||character=='\r'){if(character=='\r'&&index+1<text.length()&&text.charAt(index+1)=='\n')index++;row.add(field.toString());rows.add(List.copyOf(row));row.clear();field.setLength(0);closed=false;if(rows.size()>501)throw new IllegalArgumentException("Question count exceeds 500");}
            else {if(closed)throw new IllegalArgumentException("Characters after quoted table field");field.append(character);}
        }
        if(quoted)throw new IllegalArgumentException("Unterminated quoted table field");
        if(field.length()>0||!row.isEmpty()||closed){row.add(field.toString());rows.add(List.copyOf(row));}
        return List.copyOf(rows);
    }
    public static String exportCsv(ObjectNode definition){
        var header=List.of("id","type","slideId","prompt","options","answerKey","hints","explanation","sources","targetX","targetY","targetWidth","targetHeight","tolerance","taskJson","packMetadataJson");
        var metadata=definition.deepCopy();metadata.remove("tasks");metadata.remove("checksum");metadata.remove("facultyPreview");
        var output=new StringBuilder(String.join(",",header)).append('\n');boolean first=true;
        for(var task:definition.path("tasks")){var fields=new ArrayList<String>();for(var name:header){var value=name.equals("taskJson")?StudyPackCanonicalJson.canonicalize(task):name.equals("packMetadataJson")?(first?StudyPackCanonicalJson.canonicalize(metadata):""):task.path(name).isMissingNode()?"":task.path(name).isTextual()?task.path(name).asText():StudyPackCanonicalJson.canonicalize(task.path(name));fields.add('"'+value.replace("\"","\"\"")+'"');}output.append(String.join(",",fields)).append('\n');first=false;}
        return output.toString();
    }
    public static ObjectNode importCsvPack(String text)throws IOException {
        var rows=rows(text,',');if(rows.size()<2)throw new IllegalArgumentException("CSV pack has no task rows");
        var metadataIndex=rows.get(0).indexOf("packMetadataJson");if(metadataIndex<0 || rows.get(1).size()<=metadataIndex || rows.get(1).get(metadataIndex).isBlank())throw new IllegalArgumentException("CSV pack metadata is required; import questions into an existing draft instead");
        var definition=StudyPackCanonicalJson.parse(rows.get(1).get(metadataIndex));var tasks=definition.putArray("tasks");for(var task:parse("csv",text,"").tasks())tasks.add(task);return definition;
    }
    public record Result(List<ObjectNode> tasks,List<String> warnings){public Result{tasks=List.copyOf(tasks);warnings=List.copyOf(warnings);}}
}
