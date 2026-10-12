package org.reasonnotes.probe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RevisionGraph {
    private final Map<String, RevisionEnvelope> revisions = new LinkedHashMap<>();

    public void add(RevisionEnvelope revision) {
        RevisionEnvelope existing = revisions.get(revision.revisionId());
        if (existing != null && !existing.equals(revision)) throw new IllegalArgumentException("revision collision");
        revisions.put(revision.revisionId(), revision);
    }

    public List<RevisionEnvelope> heads(UUID noteId) {
        List<RevisionEnvelope> complete = revisions.values().stream()
                .filter(item -> item.noteId().equals(noteId) && complete(item.revisionId(), new HashSet<>(), new HashMap<>()))
                .toList();
        ArrayList<RevisionEnvelope> heads = new ArrayList<>();
        for (RevisionEnvelope candidate : complete) {
            boolean ancestor = complete.stream().anyMatch(other -> !candidate.equals(other)
                    && isAncestor(candidate.revisionId(), other.revisionId()));
            if (!ancestor) heads.add(candidate);
        }
        heads.sort((left, right) -> left.revisionId().compareTo(right.revisionId()));
        return List.copyOf(heads);
    }

    public List<String> pending(UUID noteId) {
        ArrayList<String> pending = new ArrayList<>();
        Map<String, Boolean> cache = new HashMap<>();
        for (RevisionEnvelope revision : revisions.values()) {
            if (revision.noteId().equals(noteId) && !complete(revision.revisionId(), new HashSet<>(), cache)) {
                pending.add(revision.revisionId());
            }
        }
        pending.sort(String::compareTo);
        return List.copyOf(pending);
    }

    private boolean complete(String id, Set<String> visiting, Map<String, Boolean> cache) {
        if (cache.containsKey(id)) return cache.get(id);
        RevisionEnvelope revision = revisions.get(id);
        if (revision == null || !visiting.add(id)) return false;
        boolean result = true;
        for (String parentId : revision.parents()) {
            RevisionEnvelope parent = revisions.get(parentId);
            if (parent == null || !parent.noteId().equals(revision.noteId())
                    || !complete(parentId, new HashSet<>(visiting), cache)) {
                result = false;
                break;
            }
        }
        cache.put(id, result);
        return result;
    }

    private boolean isAncestor(String ancestor, String descendant) {
        if (ancestor.equals(descendant) || !revisions.containsKey(ancestor) || !revisions.containsKey(descendant)) return false;
        ArrayList<String> remaining = new ArrayList<>(revisions.get(descendant).parents());
        HashSet<String> seen = new HashSet<>();
        while (!remaining.isEmpty()) {
            String current = remaining.remove(remaining.size() - 1);
            if (current.equals(ancestor)) return true;
            if (!seen.add(current)) continue;
            RevisionEnvelope revision = revisions.get(current);
            if (revision != null) remaining.addAll(revision.parents());
        }
        return false;
    }

    public RevisionEnvelope resolve(UUID noteId, String selectedRevisionId, String resolverDevice, Instant modifiedUtc) {
        List<RevisionEnvelope> heads = heads(noteId);
        if (heads.size() < 2) throw new IllegalStateException("note is not conflicted");
        RevisionEnvelope selected = heads.stream()
                .filter(item -> item.revisionId().equals(selectedRevisionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("selected revision is not a current head"));
        RevisionEnvelope resolution = RevisionEnvelope.create(
                noteId,
                heads.stream().map(RevisionEnvelope::revisionId).toList(),
                resolverDevice,
                modifiedUtc,
                selected.deleted(),
                selected.body());
        add(resolution);
        return resolution;
    }
}
