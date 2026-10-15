package org.pathlab.forge.study;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
final class StudyQuestionImportsTest {
    @Test void csvRoundTripPreservesAuthoredKeysAndMetadata()throws Exception {
        var input=new String(getClass().getResourceAsStream("/study/viewer-v1.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        var core=StudyPackCanonicalJson.core(StudyPackCanonicalJson.parse(input));
        var csv=StudyQuestionImports.exportCsv(core);
        assertEquals(core,StudyQuestionImports.importCsvPack(csv));
        assertThrows(IllegalArgumentException.class,()->StudyQuestionImports.rows("a,b\n\"unclosed",','));
    }
    @Test void xmlImportsPreserveExplicitKeyMappingAndRejectExternalEntities()throws Exception {
        var qti="""
          <assessmentItem identifier="q1"><responseDeclaration identifier="RESPONSE" cardinality="single" baseType="identifier"><correctResponse><value>B</value></correctResponse></responseDeclaration><itemBody><choiceInteraction responseIdentifier="RESPONSE" maxChoices="1"><prompt>Supplied prompt</prompt><simpleChoice identifier="A">Supplied A</simpleChoice><simpleChoice identifier="B">Supplied B</simpleChoice></choiceInteraction></itemBody></assessmentItem>
          """;
        var result=StudyQuestionImports.parse("qti",qti,"slide");
        assertEquals("Supplied B",result.tasks().get(0).path("answerKey").asText());
        assertEquals("multiple-choice",result.tasks().get(0).path("type").asText());
        assertTrue(result.tasks().get(0).has("importedXml"));
        assertThrows(java.io.IOException.class,()->StudyQuestionImports.parse("moodle","<!DOCTYPE quiz [<!ENTITY x SYSTEM 'file:///private'>]><quiz>&x;</quiz>","slide"));
        var moodle="<quiz><question type='multichoice'><questiontext><text><![CDATA[<script>bad()</script><p>Supplied prompt</p>]]></text></questiontext><single>true</single><answer fraction='0'><text>A</text></answer><answer fraction='100'><text>B</text></answer></question></quiz>";
        var task=StudyQuestionImports.parse("moodle",moodle,"slide").tasks().get(0);
        assertEquals("Supplied prompt",task.path("prompt").asText());assertEquals("B",task.path("answerKey").asText());
    }
    @Test void unsupportedFlashcardsStayIncompleteWithoutInventedDistractors()throws Exception {
        var result=StudyQuestionImports.parse("anki","Front\tBack\nSupplied question\tSupplied answer", "slide");
        var task=result.tasks().get(0);
        assertEquals("faculty-conversion",task.path("type").asText());assertEquals(0,task.path("options").size());assertEquals("",task.path("answerKey").asText());
        assertEquals("Supplied answer",task.path("explanation").asText());assertFalse(result.warnings().isEmpty());
    }
}
