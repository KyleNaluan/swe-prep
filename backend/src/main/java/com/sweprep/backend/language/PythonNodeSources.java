package com.sweprep.backend.language;

import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Python sibling of {@link JavaNodeSources}: the {@code ListNode}/{@code TreeNode}
 * classes a solution is written against and the build/serialise helpers the generated
 * harness calls, emitted as one {@code Structures.py} module whenever a signature
 * declares a linked structure.
 *
 * <p>One module rather than Java's three files simply because Python has no
 * one-public-class-per-file rule; the serialised forms, the cycle input form and the
 * refusal to serialise a cyclic structure are identical, which is what makes a case
 * authored once run in both languages.
 *
 * <p>Written as a plain constant with explicit absolute indentation, for the same
 * reason {@link PythonLanguageAdapter} avoids text-block splicing: Python's
 * indentation is syntactically significant.
 */
final class PythonNodeSources {

    static final String STRUCTURES_FILE = "Structures.py";

    private PythonNodeSources() {}

    static final String STRUCTURES_SOURCE =
            """
            # Builds the runtime structures a case's JSON describes, and serialises them
            # back - generated support for LIST_NODE / TREE_NODE, do not edit.


            class ListNode:
                def __init__(self, val=0, next=None):
                    self.val = val
                    self.next = next


            class TreeNode:
                def __init__(self, val=0, left=None, right=None):
                    self.val = val
                    self.left = left
                    self.right = right


            def build_list(value):
                \"\"\"A list argument: a plain array of values, or LeetCode's cycle form
                {"values": [...], "pos": k}, where the tail is joined back to index k
                (k < 0, or an absent "pos", means no cycle). None and [] are both empty.
                \"\"\"
                if value is None:
                    return None
                if isinstance(value, dict):
                    values = value.get("values")
                    pos = value.get("pos", -1)
                else:
                    values = value
                    pos = -1
                if not values:
                    return None
                nodes = [ListNode(item) for item in values]
                for index in range(len(nodes) - 1):
                    nodes[index].next = nodes[index + 1]
                if pos is not None and 0 <= pos < len(nodes):
                    nodes[-1].next = nodes[pos]
                return nodes[0]


            def serialize_list(head):
                \"\"\"The values in order, as a plain list. Refuses a cyclic list rather than
                walking it forever: only acyclic structures are ever serialised back.
                \"\"\"
                out = []
                seen = set()
                node = head
                while node is not None:
                    if id(node) in seen:
                        raise ValueError("a cyclic linked list cannot be serialised as an answer")
                    seen.add(id(node))
                    out.append(node.val)
                    node = node.next
                return out


            def build_tree(value):
                \"\"\"LeetCode's level-order-with-nulls array: each non-null node consumes the
                next two entries as its children, a null entry is an absent child and
                contributes none of its own. None and [] are both the empty tree.
                \"\"\"
                if not value or value[0] is None:
                    return None
                root = TreeNode(value[0])
                queue = [root]
                cursor = 0
                nxt = 1
                while cursor < len(queue) and nxt < len(value):
                    parent = queue[cursor]
                    cursor += 1
                    left = value[nxt]
                    nxt += 1
                    if left is not None:
                        parent.left = TreeNode(left)
                        queue.append(parent.left)
                    if nxt < len(value):
                        right = value[nxt]
                        nxt += 1
                        if right is not None:
                            parent.right = TreeNode(right)
                            queue.append(parent.right)
                return root


            def serialize_tree(root):
                \"\"\"The inverse of build_tree: level order, an absent child written as None,
                trailing Nones trimmed. Refuses a structure that revisits a node, so a
                solution that wired a cycle fails its case instead of hanging.
                \"\"\"
                if root is None:
                    return []
                values = []
                seen = set()
                queue = [root]
                cursor = 0
                while cursor < len(queue):
                    node = queue[cursor]
                    cursor += 1
                    if node is None:
                        values.append(None)
                        continue
                    if id(node) in seen:
                        raise ValueError("a cyclic tree cannot be serialised as an answer")
                    seen.add(id(node))
                    values.append(node.val)
                    queue.append(node.left)
                    queue.append(node.right)
                while values and values[-1] is None:
                    values.pop()
                return values
            """;

    /** The support module a signature needs - nothing unless it uses a linked structure. */
    static Map<String, String> supportSources(Signature signature) {
        return usesLinkedStructure(signature)
                ? Map.of(STRUCTURES_FILE, STRUCTURES_SOURCE)
                : Map.of();
    }

    /**
     * The import line the stub carries so the solver's own file resolves the node types
     * the harness supplies. Only the types this signature actually uses are imported,
     * and an empty string when it uses none.
     */
    static String stubImport(Signature signature) {
        List<String> names = new ArrayList<>();
        if (uses(signature, DataType.LIST_NODE)) {
            names.add("ListNode");
        }
        if (uses(signature, DataType.TREE_NODE)) {
            names.add("TreeNode");
        }
        if (names.isEmpty()) {
            return "";
        }
        return "from Structures import " + String.join(", ", names) + "\n\n\n";
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
