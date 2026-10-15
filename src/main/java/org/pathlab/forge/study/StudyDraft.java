package org.pathlab.forge.study;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public record StudyDraft(String id, String name, long revision, ObjectNode definition, ObjectNode associations,
                         List<String> issues, String previewChecksum, List<String> reviewedTaskIds,
                         String approvedChecksum, long updatedAt) {
    public StudyDraft {
        definition = definition.deepCopy(); associations = associations.deepCopy();
        issues = List.copyOf(issues); reviewedTaskIds = List.copyOf(reviewedTaskIds);
    }
}
