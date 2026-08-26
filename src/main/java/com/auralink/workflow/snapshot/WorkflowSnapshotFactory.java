package com.auralink.workflow.snapshot;

import org.springframework.stereotype.Component;

import com.auralink.workflow.graph.CanonicalWorkflowGraph;
import com.auralink.workflow.graph.WorkflowGraphCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Creates detached, byte-stable workflow snapshots without persistence. */
@Component
public class WorkflowSnapshotFactory {

    public static final int SNAPSHOT_VERSION = 1;

    private final WorkflowGraphCodec graphCodec;
    private final ObjectMapper canonicalMapper;

    public WorkflowSnapshotFactory(WorkflowGraphCodec graphCodec) {
        this.graphCodec = graphCodec;
        canonicalMapper = graphCodec.canonicalMapperCopy();
    }

    public WorkflowSnapshotResult create(
            String workflowId,
            String workflowName,
            int workflowSchemaVersion,
            CanonicalWorkflowGraph graph) {
        CanonicalWorkflowGraph detachedGraph = graphCodec.decode(graphCodec.encode(graph));
        WorkflowSnapshot snapshot = new WorkflowSnapshot(
                SNAPSHOT_VERSION,
                workflowId,
                workflowName,
                workflowSchemaVersion,
                detachedGraph);
        try {
            return new WorkflowSnapshotResult(
                    snapshot, canonicalMapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize workflow snapshot", exception);
        }
    }
}
