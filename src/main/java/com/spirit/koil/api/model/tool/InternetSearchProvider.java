package com.spirit.koil.api.model.tool;

import java.util.List;

/** One public-search backend behind Koil's provider-neutral search router. */
public interface InternetSearchProvider {
    String id();

    List<InternetSearchResult> search(String query, int maximum) throws Exception;
}
