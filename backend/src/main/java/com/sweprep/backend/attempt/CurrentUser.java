package com.sweprep.backend.attempt;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who is practising. swe-prep is single-user forever (issue #1), so today this
 * always returns the one seeded user (migration {@code V2__app_user.sql}). It is a
 * component rather than a bare constant so that when a real account mechanism ever
 * lands, the change is here alone and every person-owned write already flows through
 * it - the same "every row carries a user_id" discipline the seed migration keeps.
 */
@Component
public class CurrentUser {

    /** The fixed id of the single seeded user, stable across environments. */
    public static final UUID SEEDED_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    public UUID id() {
        return SEEDED_USER_ID;
    }
}
