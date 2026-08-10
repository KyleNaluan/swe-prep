package com.sweprep.backend.web;

import com.sweprep.backend.language.LanguageAdapterRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The editor's language picker (issue #26): every language a code exercise can be
 * solved in, Java first (the default). This is the whole surface the picker needs -
 * the choice itself is per-request, never stored, so there is nothing here to select
 * or persist beyond the list.
 */
@RestController
@RequestMapping("/api/languages")
public class LanguageController {

    private final LanguageAdapterRegistry adapters;

    public LanguageController(LanguageAdapterRegistry adapters) {
        this.adapters = adapters;
    }

    @GetMapping
    public List<String> list() {
        return adapters.available();
    }
}
