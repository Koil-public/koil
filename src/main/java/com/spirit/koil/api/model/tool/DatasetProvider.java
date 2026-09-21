package com.spirit.koil.api.model.tool;

import com.google.gson.JsonObject;

/** Provider-neutral backend for Koil Dataset Intelligence. */
public interface DatasetProvider {
    String id();
    JsonObject search(String query, int limit) throws Exception;
    JsonObject inspect(String dataset) throws Exception;
    JsonObject rows(String dataset, String config, String split, int offset, int length) throws Exception;
    JsonObject query(String dataset, String config, String split, String query, int offset, int length) throws Exception;
    JsonObject filter(String dataset, String config, String split, String where, String orderBy, int offset, int length) throws Exception;
    JsonObject stats(String dataset, String config, String split) throws Exception;
    JsonObject parquet(String dataset) throws Exception;
}
