package com.sweprep.backend.language;

import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Signature;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Java source the harness needs when a signature declares a linked structure
 * ({@link DataType#LIST_NODE} / {@link DataType#TREE_NODE}): the node classes the
 * solver writes against, and the build/serialise helpers the generated harness calls.
 *
 * <p>These are constants rather than generated text because nothing about them varies
 * with the problem - only <em>whether</em> they are emitted does, which is what
 * {@link #supportSources(Signature)} answers. Keeping them out of {@link
 * JavaLanguageAdapter} keeps that class about the one thing that genuinely is
 * generated per signature: argument binding and the call.
 *
 * <p>{@link #LIST_NODE_DEFINITION} and {@link #TREE_NODE_DEFINITION} are deliberately
 * LeetCode's own definitions verbatim (issue #6 adopted its convention rather than
 * inventing one), so a solution pasted either way round compiles unchanged.
 *
 * <p>Serialising is cycle-safe by construction: {@code Structures} tracks the nodes it
 * has already emitted by identity and throws on the second visit, so a cyclic
 * structure - which the {@code pos} input form can legitimately build - fails that case
 * rather than looping forever. The harness catches it like any other throw.
 */
final class JavaNodeSources {

    static final String LIST_NODE_FILE = "ListNode.java";
    static final String TREE_NODE_FILE = "TreeNode.java";
    static final String STRUCTURES_FILE = "Structures.java";

    private JavaNodeSources() {}

    /** LeetCode's own singly-linked list node, verbatim. */
    static final String LIST_NODE_DEFINITION =
            """
            public class ListNode {
                int val;
                ListNode next;
                ListNode() {}
                ListNode(int val) { this.val = val; }
                ListNode(int val, ListNode next) { this.val = val; this.next = next; }
            }
            """;

    /** LeetCode's own binary tree node, verbatim. */
    static final String TREE_NODE_DEFINITION =
            """
            public class TreeNode {
                int val;
                TreeNode left;
                TreeNode right;
                TreeNode() {}
                TreeNode(int val) { this.val = val; }
                TreeNode(int val, TreeNode left, TreeNode right) {
                    this.val = val;
                    this.left = left;
                    this.right = right;
                }
            }
            """;

    /**
     * The build/serialise helpers. Only the harness calls these; a submission never
     * needs to know the serialised form exists.
     */
    static final String STRUCTURES_SOURCE =
            """
            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.fasterxml.jackson.databind.node.ArrayNode;
            import java.util.ArrayList;
            import java.util.IdentityHashMap;
            import java.util.List;
            import java.util.Map;

            // Builds the runtime structures a case's JSON describes, and serialises them
            // back - generated support for LIST_NODE / TREE_NODE, do not edit.
            public final class Structures {

                private Structures() {}

                /**
                 * A list argument: either a plain array of values, or LeetCode's cycle form
                 * {"values": [...], "pos": k} where the tail is joined back to index k
                 * (k < 0, or an absent "pos", means no cycle). null and [] are both empty.
                 */
                static ListNode buildList(JsonNode node) {
                    if (node == null || node.isNull()) {
                        return null;
                    }
                    JsonNode values = node.isObject() ? node.get("values") : node;
                    int pos = -1;
                    if (node.isObject() && node.hasNonNull("pos")) {
                        pos = node.get("pos").asInt();
                    }
                    if (values == null || values.isNull()) {
                        return null;
                    }
                    List<ListNode> nodes = new ArrayList<>();
                    for (JsonNode value : values) {
                        nodes.add(new ListNode(value.asInt()));
                    }
                    if (nodes.isEmpty()) {
                        return null;
                    }
                    for (int i = 0; i + 1 < nodes.size(); i++) {
                        nodes.get(i).next = nodes.get(i + 1);
                    }
                    if (pos >= 0 && pos < nodes.size()) {
                        nodes.get(nodes.size() - 1).next = nodes.get(pos);
                    }
                    return nodes.get(0);
                }

                /**
                 * The values in order, as a plain JSON array. Refuses a cyclic list rather
                 * than walking it forever: only acyclic structures are ever serialised back.
                 */
                static ArrayNode serializeList(ListNode head, ObjectMapper mapper) {
                    ArrayNode out = mapper.createArrayNode();
                    Map<ListNode, Boolean> seen = new IdentityHashMap<>();
                    for (ListNode node = head; node != null; node = node.next) {
                        if (seen.put(node, Boolean.TRUE) != null) {
                            throw new IllegalStateException(
                                    "a cyclic linked list cannot be serialised as an answer");
                        }
                        out.add(node.val);
                    }
                    return out;
                }

                /**
                 * LeetCode's level-order-with-nulls array: each non-null node consumes the
                 * next two entries as its children, a null entry is an absent child and
                 * contributes none of its own. null and [] are both the empty tree.
                 */
                static TreeNode buildTree(JsonNode node) {
                    if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
                        return null;
                    }
                    JsonNode rootValue = node.get(0);
                    if (rootValue == null || rootValue.isNull()) {
                        return null;
                    }
                    TreeNode root = new TreeNode(rootValue.asInt());
                    List<TreeNode> queue = new ArrayList<>();
                    queue.add(root);
                    int cursor = 0;
                    int next = 1;
                    while (cursor < queue.size() && next < node.size()) {
                        TreeNode parent = queue.get(cursor++);
                        JsonNode left = node.get(next++);
                        if (left != null && !left.isNull()) {
                            parent.left = new TreeNode(left.asInt());
                            queue.add(parent.left);
                        }
                        if (next < node.size()) {
                            JsonNode right = node.get(next++);
                            if (right != null && !right.isNull()) {
                                parent.right = new TreeNode(right.asInt());
                                queue.add(parent.right);
                            }
                        }
                    }
                    return root;
                }

                /**
                 * The inverse of {@link #buildTree}: level order, an absent child written as
                 * null, trailing nulls trimmed. Refuses a structure that revisits a node, so
                 * a solution that wired a cycle fails its case instead of hanging.
                 */
                static ArrayNode serializeTree(TreeNode root, ObjectMapper mapper) {
                    ArrayNode out = mapper.createArrayNode();
                    if (root == null) {
                        return out;
                    }
                    Map<TreeNode, Boolean> seen = new IdentityHashMap<>();
                    // A plain list walked by index, not an ArrayDeque: absent children are
                    // enqueued as nulls, which a Deque refuses to hold.
                    List<TreeNode> queue = new ArrayList<>();
                    List<Integer> values = new ArrayList<>();
                    queue.add(root);
                    for (int i = 0; i < queue.size(); i++) {
                        TreeNode current = queue.get(i);
                        if (current == null) {
                            values.add(null);
                            continue;
                        }
                        if (seen.put(current, Boolean.TRUE) != null) {
                            throw new IllegalStateException(
                                    "a cyclic tree cannot be serialised as an answer");
                        }
                        values.add(current.val);
                        queue.add(current.left);
                        queue.add(current.right);
                    }
                    int end = values.size();
                    while (end > 0 && values.get(end - 1) == null) {
                        end--;
                    }
                    for (int i = 0; i < end; i++) {
                        Integer value = values.get(i);
                        if (value == null) {
                            out.addNull();
                        } else {
                            out.add(value.intValue());
                        }
                    }
                    return out;
                }
            }
            """;

    /**
     * The support files a signature needs, keyed by filename: nothing at all unless it
     * mentions a linked structure, and then both node classes rather than only the one
     * in use, since {@code Structures} is one class referencing both. Emitting an unused
     * node class costs a trivial compile and keeps the support a single unit - the same
     * shape Python's one {@code Structures.py} module already has. What the <em>solver</em>
     * sees is still only the type they need: that is {@link #stubPreamble(Signature)}'s job.
     */
    static Map<String, String> supportSources(Signature signature) {
        Map<String, String> sources = new LinkedHashMap<>();
        if (!usesLinkedStructure(signature)) {
            return sources;
        }
        sources.put(LIST_NODE_FILE, harnessSuppliedFile(LIST_NODE_DEFINITION));
        sources.put(TREE_NODE_FILE, harnessSuppliedFile(TREE_NODE_DEFINITION));
        sources.put(STRUCTURES_FILE, STRUCTURES_SOURCE);
        return sources;
    }

    /**
     * The definition comment shown above the stub, the way LeetCode shows it: the
     * solver writes against {@code ListNode}/{@code TreeNode} and needs to see their
     * fields, but the class itself is the harness's to supply, not theirs to edit.
     */
    static String stubPreamble(Signature signature) {
        StringBuilder preamble = new StringBuilder();
        if (uses(signature, DataType.LIST_NODE)) {
            preamble.append(commented("Definition for singly-linked list.", LIST_NODE_DEFINITION));
        }
        if (uses(signature, DataType.TREE_NODE)) {
            preamble.append(commented("Definition for a binary tree node.", TREE_NODE_DEFINITION));
        }
        return preamble.toString();
    }

    /** The node file as written next to the submission: the definition plus a note. */
    private static String harnessSuppliedFile(String definition) {
        return "// Supplied by the harness - do not edit.\n" + definition;
    }

    /**
     * A definition rendered as a Javadoc block, the way LeetCode shows it above the
     * stub. The definitions themselves carry no comment of their own, so nothing here
     * can close the block early.
     */
    private static String commented(String heading, String definition) {
        StringBuilder out = new StringBuilder("/**\n * ").append(heading).append('\n');
        for (String line : definition.strip().lines().toList()) {
            out.append(" * ").append(line).append('\n');
        }
        return out.append(" */\n").toString();
    }

    static boolean usesLinkedStructure(Signature signature) {
        return signature.returnType().isLinkedStructure()
                || signature.parameters().stream().anyMatch(p -> p.type().isLinkedStructure());
    }

    private static boolean uses(Signature signature, DataType type) {
        return signature.returnType() == type
                || signature.parameters().stream().anyMatch(p -> p.type() == type);
    }
}
