package com.sweprep.backend.exercise;

/**
 * The language-neutral type vocabulary a {@link Signature} is written in.
 *
 * <p>These names carry no language syntax. Each {@code LanguageAdapter} is
 * responsible for mapping a {@code DataType} onto its own concrete type when it
 * generates a stub and a harness, which is what lets a single signature (and the
 * test cases written against it) run in every language ever added.
 *
 * <p>Only the members the problems authored so far need are defined; new members
 * are added as new problems require them, never a Java-specific type here.
 *
 * <h2>Linked lists and binary trees</h2>
 *
 * <p>{@link #LIST_NODE} and {@link #TREE_NODE} are still plain JSON on the wire, so
 * a case remains language-neutral data (issue #6). The serialisation is <em>LeetCode's
 * own</em>, adopted rather than reinvented - the decision in issue #6 is explicit that
 * the captain reads LeetCode problem statements anyway, so the app must not invent a
 * competing convention:
 *
 * <ul>
 *   <li><b>{@code LIST_NODE}</b> - a JSON array of the values in order, so
 *       {@code [1,2,3]} is the list {@code 1 -> 2 -> 3}. {@code []} and {@code null}
 *       both mean the empty list; {@code []} is the canonical serialised form of one.
 *       An <em>argument</em> may instead be {@code {"values": [3,2,0,-4], "pos": 1}},
 *       LeetCode's own way of posing a cycle ("the tail connects to the node at index
 *       {@code pos}"; {@code -1}, or an omitted {@code pos}, means no cycle). That
 *       richer form is accepted only on the way <em>in</em>: a value serialised back
 *       out is always a plain array, and only an acyclic structure is ever serialised,
 *       so a cyclic list can never hang the harness.</li>
 *   <li><b>{@code TREE_NODE}</b> - LeetCode's level-order-with-nulls array, so
 *       {@code [3,9,20,null,null,15,7]} is the tree whose root is 3, with children 9
 *       and 20, and 20's children 15 and 7. A {@code null} entry is an absent child and
 *       contributes no children of its own; trailing nulls are trimmed. {@code []} and
 *       {@code null} both mean the empty tree.</li>
 * </ul>
 *
 * <p>The solver never sees any of this: an adapter's harness builds the runtime
 * structure from the case's JSON and hands the submission an idiomatic {@code ListNode}
 * / {@code TreeNode} it supplies, then serialises a returned structure back to the same
 * JSON for the grader to compare under the exercise's {@link Comparison}.
 */
public enum DataType {
    INT,
    INT_ARRAY,
    INT_MATRIX,
    BOOLEAN,
    STRING,
    LIST_NODE,
    TREE_NODE;

    /**
     * Whether this type is a linked structure the harness has to build from, and
     * serialise back to, JSON - rather than a value a JSON library binds directly.
     * The one place that distinction is named, so an adapter, a parser or a validator
     * asks rather than re-listing the members.
     */
    public boolean isLinkedStructure() {
        return this == LIST_NODE || this == TREE_NODE;
    }
}
